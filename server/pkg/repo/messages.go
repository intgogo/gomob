// 消息 / 会话仓储 — 详见 docs/architecture/server/02-api-contract.md §7 / §8。
//
// 关键设计：server_seq 单调递增由 conversations.next_seq 行锁保证（migration 0006）。
// 不用 SELECT MAX(seq)+1 — 那个有竞争窗口，UNIQUE(conversation_id, server_seq)
// 兜底虽能防重复但要重试，复杂度反而高。
package repo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Conversation struct {
	ID        int64
	Kind      string  // p2p / group / system
	Title     *string
	P2PKey    *string // 仅 p2p 有
	NextSeq   int64
	CreatedAt time.Time
}

type Message struct {
	ID             int64
	ConversationID int64
	SenderID       *int64
	ServerSeq      int64
	Kind           string          // text / image / video_call / video_clip / system
	Payload        json.RawMessage
	CreatedAt      time.Time
}

type ConversationRepo struct {
	pool *pgxpool.Pool
}

func NewConversationRepo(pool *pgxpool.Pool) *ConversationRepo {
	return &ConversationRepo{pool: pool}
}

// p2pKey 把 (a, b) 归一化成 "min:max"，保证 (a,b) 与 (b,a) 同 key。
func p2pKey(a, b int64) string {
	if a > b {
		a, b = b, a
	}
	return fmt.Sprintf("%d:%d", a, b)
}

// GetOrCreateP2P 拿/建 a 与 b 的 p2p 会话。
//
// 并发安全：靠 conversations.p2p_key 的 partial unique index +
// "INSERT ... ON CONFLICT DO NOTHING + 二次查询" 兜底。
// 不依赖应用层锁。
func (r *ConversationRepo) GetOrCreateP2P(ctx context.Context, a, b int64) (*Conversation, error) {
	if a == b {
		return nil, errors.New("p2p 会话不能是自己跟自己")
	}
	key := p2pKey(a, b)

	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// 已存在则直接返回
	conv, err := r.findByP2PKeyTx(ctx, tx, key)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	if conv != nil {
		_ = tx.Commit(ctx)
		return conv, nil
	}

	// 插入会话 + 双方成员
	const insertConv = `
		INSERT INTO conversations(kind, p2p_key) VALUES('p2p', $1)
		ON CONFLICT DO NOTHING
		RETURNING id, kind, title, p2p_key, next_seq, created_at`
	row := tx.QueryRow(ctx, insertConv, key)
	c := &Conversation{}
	if err := row.Scan(&c.ID, &c.Kind, &c.Title, &c.P2PKey, &c.NextSeq, &c.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// 并发竞争：另一事务刚插入；重新查
			conv, err := r.findByP2PKeyTx(ctx, tx, key)
			if err != nil {
				return nil, err
			}
			_ = tx.Commit(ctx)
			return conv, nil
		}
		return nil, err
	}
	const insertMember = `
		INSERT INTO conversation_members(conversation_id, user_id) VALUES($1, $2), ($1, $3)
		ON CONFLICT DO NOTHING`
	if _, err := tx.Exec(ctx, insertMember, c.ID, a, b); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return c, nil
}

func (r *ConversationRepo) findByP2PKeyTx(ctx context.Context, tx pgx.Tx, key string) (*Conversation, error) {
	const q = `SELECT id, kind, title, p2p_key, next_seq, created_at FROM conversations WHERE p2p_key=$1`
	row := tx.QueryRow(ctx, q, key)
	c := &Conversation{}
	if err := row.Scan(&c.ID, &c.Kind, &c.Title, &c.P2PKey, &c.NextSeq, &c.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return c, nil
}

// IsMember 检查 user 是否为 conversation 成员。
func (r *ConversationRepo) IsMember(ctx context.Context, convID, userID int64) (bool, error) {
	var exists bool
	err := r.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM conversation_members WHERE conversation_id=$1 AND user_id=$2)`,
		convID, userID).Scan(&exists)
	return exists, err
}

// CounterpartIDs 返回 conv 中除 self 之外的成员 id 列表（用于推送）。
func (r *ConversationRepo) CounterpartIDs(ctx context.Context, convID, self int64) ([]int64, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT user_id FROM conversation_members WHERE conversation_id=$1 AND user_id<>$2`,
		convID, self)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// MessageRepo —— 消息持久化。
type MessageRepo struct {
	pool *pgxpool.Pool
}

func NewMessageRepo(pool *pgxpool.Pool) *MessageRepo {
	return &MessageRepo{pool: pool}
}

// Append 把消息原子写入：UPDATE conversations.next_seq 行锁分配 seq，再 INSERT messages。
//
// 关键：UPDATE ... RETURNING next_seq - 1 让两步在同一事务里串行化，
// 同 conversation 并发请求会互相等待行锁，server_seq 严格单调递增、不会跳号。
func (r *MessageRepo) Append(ctx context.Context, m *Message) error {
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// 行锁 + 自增（next_seq 现值就是要分配的 seq）
	var seq int64
	err = tx.QueryRow(ctx,
		`UPDATE conversations SET next_seq = next_seq + 1 WHERE id = $1 RETURNING next_seq - 1`,
		m.ConversationID).Scan(&seq)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}

	const ins = `
		INSERT INTO messages(conversation_id, sender_id, server_seq, kind, payload)
		VALUES($1, $2, $3, $4, $5)
		RETURNING id, created_at`
	if err := tx.QueryRow(ctx, ins,
		m.ConversationID, m.SenderID, seq, m.Kind, m.Payload,
	).Scan(&m.ID, &m.CreatedAt); err != nil {
		return err
	}
	m.ServerSeq = seq
	return tx.Commit(ctx)
}

// ListSince 返回 conversation 中 server_seq > since 的消息（升序）；用于离线补齐。
func (r *MessageRepo) ListSince(ctx context.Context, convID, since int64, limit int) ([]Message, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	const q = `
		SELECT id, conversation_id, sender_id, server_seq, kind, payload, created_at
		FROM messages
		WHERE conversation_id=$1 AND server_seq > $2
		ORDER BY server_seq ASC
		LIMIT $3`
	rows, err := r.pool.Query(ctx, q, convID, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Message, 0, limit)
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.ServerSeq,
			&m.Kind, &m.Payload, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// MaxSeq 返回当前 conversation 已经分配出去的最大 server_seq（即 next_seq - 1）。
func (r *MessageRepo) MaxSeq(ctx context.Context, convID int64) (int64, error) {
	var seq int64
	err := r.pool.QueryRow(ctx,
		`SELECT next_seq - 1 FROM conversations WHERE id=$1`, convID).Scan(&seq)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	return seq, nil
}
