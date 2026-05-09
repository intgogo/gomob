// 消息 / 会话仓储 — 详见 docs/architecture/server/02-api-contract.md §7 / §8。
//
// 关键设计：server_seq 单调递增由 conversations.next_seq 行锁保证（migration 0006）。
// 不用 SELECT MAX(seq)+1 — 那个有竞争窗口，UNIQUE(conversation_id, server_seq)
// 兜底虽能防重复但要重试，复杂度反而高。
package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Conversation struct {
	ID          int64
	Kind        string // p2p / group / system
	Title       *string
	P2PKey      *string // 仅 p2p 有
	SubjectKind *string
	SubjectID   *int64
	NextSeq     int64
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type Message struct {
	ID             int64
	ConversationID int64
	SenderID       *int64
	ServerSeq      int64
	Kind           string // text / image / voice / video_call / video_clip / system
	Payload        json.RawMessage
	ClientMsgID    *string
	CreatedAt      time.Time
	EditedAt       *time.Time
	DeletedAt      *time.Time
}

type ConversationPeer struct {
	ID         int64
	RealName   string
	EmployeeID string
}

type ConversationSummary struct {
	Conversation Conversation
	Peer         *ConversationPeer
	LastMessage  *Message
	LastReadSeq  int64
	UnreadCount  int64
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
	const insertState = `
		INSERT INTO conversation_member_states(conversation_id, user_id) VALUES($1, $2), ($1, $3)
		ON CONFLICT DO NOTHING`
	if _, err := tx.Exec(ctx, insertState, c.ID, a, b); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return c, nil
}

// GetOrCreateSubjectGroup 拿/建一个由业务 subject 唯一标识的固定群会话。
func (r *ConversationRepo) GetOrCreateSubjectGroup(
	ctx context.Context,
	title string,
	subjectKind string,
	subjectID int64,
	memberIDs []int64,
) (*Conversation, error) {
	if title == "" || subjectKind == "" || subjectID <= 0 {
		return nil, errors.New("subject group 参数无效")
	}
	members := uniquePositiveInt64(memberIDs)
	if len(members) == 0 {
		return nil, errors.New("subject group 至少需要一个成员")
	}

	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	conv, err := r.findBySubjectTx(ctx, tx, subjectKind, subjectID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	if conv == nil {
		const insertConv = `
			INSERT INTO conversations(kind, title, subject_kind, subject_id)
			VALUES('group', $1, $2, $3)
			ON CONFLICT (subject_kind, subject_id)
			WHERE subject_kind IS NOT NULL AND subject_id IS NOT NULL
			DO NOTHING
			RETURNING id, kind, title, p2p_key, subject_kind, subject_id, next_seq, created_at, updated_at`
		conv = &Conversation{}
		err = tx.QueryRow(ctx, insertConv, title, subjectKind, subjectID).Scan(
			&conv.ID, &conv.Kind, &conv.Title, &conv.P2PKey, &conv.SubjectKind, &conv.SubjectID,
			&conv.NextSeq, &conv.CreatedAt, &conv.UpdatedAt,
		)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				conv, err = r.findBySubjectTx(ctx, tx, subjectKind, subjectID)
				if err != nil {
					return nil, err
				}
			} else {
				return nil, err
			}
		}
	}

	for _, userID := range members {
		if _, err := tx.Exec(ctx,
			`INSERT INTO conversation_members(conversation_id, user_id) VALUES($1, $2)
			 ON CONFLICT DO NOTHING`,
			conv.ID, userID,
		); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO conversation_member_states(conversation_id, user_id) VALUES($1, $2)
			 ON CONFLICT DO NOTHING`,
			conv.ID, userID,
		); err != nil {
			return nil, err
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return conv, nil
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

func (r *ConversationRepo) findBySubjectTx(ctx context.Context, tx pgx.Tx, subjectKind string, subjectID int64) (*Conversation, error) {
	const q = `
		SELECT id, kind, title, p2p_key, subject_kind, subject_id, next_seq, created_at, updated_at
		FROM conversations
		WHERE subject_kind=$1 AND subject_id=$2`
	row := tx.QueryRow(ctx, q, subjectKind, subjectID)
	c := &Conversation{}
	if err := row.Scan(
		&c.ID, &c.Kind, &c.Title, &c.P2PKey, &c.SubjectKind, &c.SubjectID,
		&c.NextSeq, &c.CreatedAt, &c.UpdatedAt,
	); err != nil {
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

// FindForUser 返回当前用户可访问的单个会话摘要。
func (r *ConversationRepo) FindForUser(ctx context.Context, userID, convID int64) (*ConversationSummary, error) {
	const q = `
		SELECT c.id, c.kind, c.title, c.p2p_key, c.subject_kind, c.subject_id,
		       c.next_seq, c.created_at, c.updated_at,
		       COALESCE(cms.last_read_seq, 0) AS last_read_seq,
		       peer.id, peer.real_name, peer.employee_id,
		       GREATEST(c.next_seq - 1 - COALESCE(cms.last_read_seq, 0), 0) AS unread_count
		FROM conversations c
		JOIN conversation_members cm
		  ON cm.conversation_id = c.id AND cm.user_id = $1
		LEFT JOIN conversation_member_states cms
		  ON cms.conversation_id = c.id AND cms.user_id = $1
		LEFT JOIN LATERAL (
			SELECT u.id, u.real_name, u.employee_id
			FROM conversation_members cm2
			JOIN users u ON u.id = cm2.user_id
			WHERE c.kind = 'p2p' AND cm2.conversation_id = c.id AND cm2.user_id <> $1
			ORDER BY u.id
			LIMIT 1
		) peer ON true
		WHERE c.id = $2`
	var s ConversationSummary
	var title, p2pKey, subjectKind sql.NullString
	var subjectID sql.NullInt64
	var peerID sql.NullInt64
	var peerName, peerEmployee sql.NullString
	err := r.pool.QueryRow(ctx, q, userID, convID).Scan(
		&s.Conversation.ID, &s.Conversation.Kind, &title, &p2pKey, &subjectKind, &subjectID,
		&s.Conversation.NextSeq, &s.Conversation.CreatedAt, &s.Conversation.UpdatedAt, &s.LastReadSeq,
		&peerID, &peerName, &peerEmployee,
		&s.UnreadCount,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	s.Conversation.Title = nullStringPtr(title)
	s.Conversation.P2PKey = nullStringPtr(p2pKey)
	s.Conversation.SubjectKind = nullStringPtr(subjectKind)
	s.Conversation.SubjectID = nullInt64Ptr(subjectID)
	if peerID.Valid {
		s.Peer = &ConversationPeer{
			ID:         peerID.Int64,
			RealName:   peerName.String,
			EmployeeID: peerEmployee.String,
		}
	}
	return &s, nil
}

// ListForUser 返回当前用户参与的会话摘要，按 updated_at/id 倒序。
func (r *ConversationRepo) ListForUser(ctx context.Context, userID int64, limit int, cursor int64) ([]ConversationSummary, int64, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	const q = `
		WITH member_convs AS (
			SELECT c.id, c.kind, c.title, c.p2p_key, c.subject_kind, c.subject_id,
			       c.next_seq, c.created_at, c.updated_at,
			       COALESCE(cms.last_read_seq, 0) AS last_read_seq
			FROM conversations c
			JOIN conversation_members cm
			  ON cm.conversation_id = c.id AND cm.user_id = $1
			LEFT JOIN conversation_member_states cms
			  ON cms.conversation_id = c.id AND cms.user_id = $1
			WHERE ($2 = 0 OR c.id < $2)
			  AND COALESCE(c.subject_kind, '') <> 'online_help'
			ORDER BY c.updated_at DESC, c.id DESC
			LIMIT $3
		)
		SELECT mc.id, mc.kind, mc.title, mc.p2p_key, mc.subject_kind, mc.subject_id,
		       mc.next_seq, mc.created_at, mc.updated_at, mc.last_read_seq,
		       lm.id, lm.sender_id, lm.server_seq, lm.kind, lm.payload, lm.client_msg_id,
		       lm.created_at, lm.edited_at, lm.deleted_at,
		       peer.id, peer.real_name, peer.employee_id,
		       GREATEST(mc.next_seq - 1 - mc.last_read_seq, 0) AS unread_count
		FROM member_convs mc
		LEFT JOIN LATERAL (
			SELECT id, sender_id, server_seq, kind, payload, client_msg_id, created_at, edited_at, deleted_at
			FROM messages
			WHERE conversation_id = mc.id AND deleted_at IS NULL
			ORDER BY server_seq DESC
			LIMIT 1
		) lm ON true
		LEFT JOIN LATERAL (
			SELECT u.id, u.real_name, u.employee_id
			FROM conversation_members cm2
			JOIN users u ON u.id = cm2.user_id
			WHERE mc.kind = 'p2p' AND cm2.conversation_id = mc.id AND cm2.user_id <> $1
			ORDER BY u.id
			LIMIT 1
		) peer ON true
		ORDER BY mc.updated_at DESC, mc.id DESC`
	rows, err := r.pool.Query(ctx, q, userID, cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	items := make([]ConversationSummary, 0, limit+1)
	for rows.Next() {
		var s ConversationSummary
		var title, p2pKey, subjectKind sql.NullString
		var subjectID sql.NullInt64
		var lmID, lmSenderID, lmSeq sql.NullInt64
		var lmKind, lmClientMsgID sql.NullString
		var lmPayload []byte
		var lmCreatedAt, lmEditedAt, lmDeletedAt sql.NullTime
		var peerID sql.NullInt64
		var peerName, peerEmployee sql.NullString
		if err := rows.Scan(
			&s.Conversation.ID, &s.Conversation.Kind, &title, &p2pKey, &subjectKind, &subjectID,
			&s.Conversation.NextSeq, &s.Conversation.CreatedAt, &s.Conversation.UpdatedAt, &s.LastReadSeq,
			&lmID, &lmSenderID, &lmSeq, &lmKind, &lmPayload, &lmClientMsgID,
			&lmCreatedAt, &lmEditedAt, &lmDeletedAt,
			&peerID, &peerName, &peerEmployee,
			&s.UnreadCount,
		); err != nil {
			return nil, 0, err
		}
		s.Conversation.Title = nullStringPtr(title)
		s.Conversation.P2PKey = nullStringPtr(p2pKey)
		s.Conversation.SubjectKind = nullStringPtr(subjectKind)
		s.Conversation.SubjectID = nullInt64Ptr(subjectID)
		if lmID.Valid {
			msg := &Message{
				ID:             lmID.Int64,
				ConversationID: s.Conversation.ID,
				ServerSeq:      lmSeq.Int64,
				Kind:           lmKind.String,
				Payload:        append(json.RawMessage(nil), lmPayload...),
				ClientMsgID:    nullStringPtr(lmClientMsgID),
				CreatedAt:      lmCreatedAt.Time,
				EditedAt:       nullTimePtr(lmEditedAt),
				DeletedAt:      nullTimePtr(lmDeletedAt),
			}
			if lmSenderID.Valid {
				v := lmSenderID.Int64
				msg.SenderID = &v
			}
			s.LastMessage = msg
		}
		if peerID.Valid {
			s.Peer = &ConversationPeer{
				ID:         peerID.Int64,
				RealName:   peerName.String,
				EmployeeID: peerEmployee.String,
			}
		}
		items = append(items, s)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	var next int64
	if len(items) > limit {
		next = items[limit-1].Conversation.ID
		items = items[:limit]
	}
	return items, next, nil
}

// EnsureMemberState 补齐 conversation_members 对应的本地状态行。
func (r *ConversationRepo) EnsureMemberState(ctx context.Context, convID, userID int64) error {
	_, err := r.pool.Exec(ctx,
		`INSERT INTO conversation_member_states(conversation_id, user_id) VALUES($1, $2)
		 ON CONFLICT DO NOTHING`, convID, userID)
	return err
}

// MarkRead 更新用户在会话内的已读水位，返回更新后的未读数。
func (r *ConversationRepo) MarkRead(ctx context.Context, convID, userID, lastReadSeq int64) (int64, error) {
	if lastReadSeq < 0 {
		return 0, errors.New("last_read_seq must be non-negative")
	}
	ok, err := r.IsMember(ctx, convID, userID)
	if err != nil {
		return 0, err
	}
	if !ok {
		return 0, ErrNotFound
	}
	const upsert = `
		INSERT INTO conversation_member_states(conversation_id, user_id, last_read_seq, updated_at)
		VALUES($1, $2, $3, now())
		ON CONFLICT (conversation_id, user_id) DO UPDATE
		SET last_read_seq = GREATEST(conversation_member_states.last_read_seq, EXCLUDED.last_read_seq),
		    updated_at = now()`
	if _, err := r.pool.Exec(ctx, upsert, convID, userID, lastReadSeq); err != nil {
		return 0, err
	}
	return r.UnreadCount(ctx, convID, userID)
}

func (r *ConversationRepo) UnreadCount(ctx context.Context, convID, userID int64) (int64, error) {
	const q = `
		SELECT GREATEST(c.next_seq - 1 - COALESCE(cms.last_read_seq, 0), 0)
		FROM conversations c
		JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.user_id = $2
		LEFT JOIN conversation_member_states cms ON cms.conversation_id = c.id AND cms.user_id = $2
		WHERE c.id = $1`
	var unread int64
	if err := r.pool.QueryRow(ctx, q, convID, userID).Scan(&unread); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	return unread, nil
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
	inserted, err := r.AppendIdempotent(ctx, m, "")
	if err != nil {
		return err
	}
	_ = inserted
	return nil
}

// AppendIdempotent 写入消息；clientMsgID 非空时按 (sender_id, client_msg_id) 幂等。
//
// 返回 inserted=false 表示此前已经写过同一个 client_msg_id，m 会被填充为既有消息。
func (r *MessageRepo) AppendIdempotent(ctx context.Context, m *Message, clientMsgID string) (bool, error) {
	if clientMsgID != "" && m.SenderID != nil {
		if existing, err := r.FindByClientMsgID(ctx, *m.SenderID, clientMsgID); err == nil {
			*m = *existing
			return false, nil
		} else if !errors.Is(err, ErrNotFound) {
			return false, err
		}
	}

	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// 行锁 + 自增（next_seq 现值就是要分配的 seq）
	var seq int64
	err = tx.QueryRow(ctx,
		`UPDATE conversations SET next_seq = next_seq + 1, updated_at = now()
		 WHERE id = $1 RETURNING next_seq - 1`,
		m.ConversationID).Scan(&seq)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, ErrNotFound
		}
		return false, err
	}

	const ins = `
		INSERT INTO messages(conversation_id, sender_id, server_seq, kind, payload, client_msg_id)
		VALUES($1, $2, $3, $4, $5, NULLIF($6, ''))
		RETURNING id, created_at`
	if err := tx.QueryRow(ctx, ins,
		m.ConversationID, m.SenderID, seq, m.Kind, m.Payload, clientMsgID,
	).Scan(&m.ID, &m.CreatedAt); err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok && pgErr.ConstraintName == "uq_messages_sender_client_msg" && m.SenderID != nil {
			if existing, findErr := r.FindByClientMsgID(ctx, *m.SenderID, clientMsgID); findErr == nil {
				*m = *existing
				return false, nil
			}
		}
		return false, err
	}
	m.ServerSeq = seq
	if clientMsgID != "" {
		cid := clientMsgID
		m.ClientMsgID = &cid
	}
	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	return true, nil
}

// ListSince 返回 conversation 中 server_seq > since 的消息（升序）；用于离线补齐。
func (r *MessageRepo) ListSince(ctx context.Context, convID, since int64, limit int) ([]Message, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	const q = `
		SELECT id, conversation_id, sender_id, server_seq, kind, payload, client_msg_id,
		       created_at, edited_at, deleted_at
		FROM messages
		WHERE conversation_id=$1 AND server_seq > $2 AND deleted_at IS NULL
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
		var clientMsgID sql.NullString
		var editedAt, deletedAt sql.NullTime
		if err := rows.Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.ServerSeq,
			&m.Kind, &m.Payload, &clientMsgID, &m.CreatedAt, &editedAt, &deletedAt); err != nil {
			return nil, err
		}
		m.ClientMsgID = nullStringPtr(clientMsgID)
		m.EditedAt = nullTimePtr(editedAt)
		m.DeletedAt = nullTimePtr(deletedAt)
		out = append(out, m)
	}
	return out, rows.Err()
}

// FindByClientMsgID 返回某发送者 client_msg_id 对应的已入库消息。
func (r *MessageRepo) FindByClientMsgID(ctx context.Context, senderID int64, clientMsgID string) (*Message, error) {
	if clientMsgID == "" {
		return nil, ErrNotFound
	}
	const q = `
		SELECT id, conversation_id, sender_id, server_seq, kind, payload, client_msg_id,
		       created_at, edited_at, deleted_at
		FROM messages
		WHERE sender_id=$1 AND client_msg_id=$2`
	var m Message
	var client sql.NullString
	var editedAt, deletedAt sql.NullTime
	if err := r.pool.QueryRow(ctx, q, senderID, clientMsgID).Scan(
		&m.ID, &m.ConversationID, &m.SenderID, &m.ServerSeq, &m.Kind, &m.Payload, &client,
		&m.CreatedAt, &editedAt, &deletedAt,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	m.ClientMsgID = nullStringPtr(client)
	m.EditedAt = nullTimePtr(editedAt)
	m.DeletedAt = nullTimePtr(deletedAt)
	return &m, nil
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

func nullStringPtr(v sql.NullString) *string {
	if !v.Valid {
		return nil
	}
	s := v.String
	return &s
}

func nullInt64Ptr(v sql.NullInt64) *int64 {
	if !v.Valid {
		return nil
	}
	n := v.Int64
	return &n
}

func nullTimePtr(v sql.NullTime) *time.Time {
	if !v.Valid {
		return nil
	}
	t := v.Time
	return &t
}

func uniquePositiveInt64(items []int64) []int64 {
	seen := make(map[int64]struct{}, len(items))
	out := make([]int64, 0, len(items))
	for _, item := range items {
		if item <= 0 {
			continue
		}
		if _, ok := seen[item]; ok {
			continue
		}
		seen[item] = struct{}{}
		out = append(out, item)
	}
	return out
}
