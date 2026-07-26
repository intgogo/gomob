package repo

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestConversationHideForUserPostgres(t *testing.T) {
	pool := openConversationHideTestPool(t)
	ctx := context.Background()

	if _, err := pool.Exec(ctx, `
		INSERT INTO users(id, real_name, employee_id) VALUES
			(1, '用户甲', 'A001'),
			(2, '用户乙', 'B001');
		INSERT INTO conversations(id, kind) VALUES(10, 'p2p');
		INSERT INTO conversation_members(conversation_id, user_id) VALUES(10, 1), (10, 2);
		INSERT INTO conversation_member_states(conversation_id, user_id) VALUES(10, 1), (10, 2);
	`); err != nil {
		t.Fatalf("准备会话数据失败: %v", err)
	}

	conversations := NewConversationRepo(pool)
	messages := NewMessageRepo(pool)
	appendTextMessage(t, messages, 10, 2, "删除前一")
	appendTextMessage(t, messages, 10, 2, "删除前二")

	beforeDelete, _, err := conversations.ListForUser(ctx, 1, 20, 0)
	if err != nil {
		t.Fatalf("删除前读取用户甲会话失败: %v", err)
	}
	assertConversationSummary(t, beforeDelete, 2, 2)

	hiddenThrough, err := conversations.HideForUser(ctx, 10, 1)
	if err != nil {
		t.Fatalf("用户甲删除会话失败: %v", err)
	}
	if hiddenThrough != 2 {
		t.Fatalf("删除水位=%d want 2", hiddenThrough)
	}

	userAAfterDelete, _, err := conversations.ListForUser(ctx, 1, 20, 0)
	if err != nil {
		t.Fatalf("删除后读取用户甲会话失败: %v", err)
	}
	if len(userAAfterDelete) != 0 {
		t.Fatalf("用户甲删除后会话仍可见: %+v", userAAfterDelete)
	}

	userBAfterDelete, _, err := conversations.ListForUser(ctx, 2, 20, 0)
	if err != nil {
		t.Fatalf("用户甲删除后读取用户乙会话失败: %v", err)
	}
	assertConversationSummary(t, userBAfterDelete, 2, 0)

	floorA, err := conversations.HistoryFloor(ctx, 10, 1)
	if err != nil {
		t.Fatalf("读取用户甲历史水位失败: %v", err)
	}
	floorB, err := conversations.HistoryFloor(ctx, 10, 2)
	if err != nil {
		t.Fatalf("读取用户乙历史水位失败: %v", err)
	}
	if floorA != 2 || floorB != 0 {
		t.Fatalf("历史水位错误: 用户甲=%d 用户乙=%d", floorA, floorB)
	}
	assertMessageSeqs(t, listSince(t, messages, 10, floorA), nil)
	assertMessageSeqs(t, listSince(t, messages, 10, floorB), []int64{1, 2})

	var lastRead int64
	var storedHidden *int64
	if err := pool.QueryRow(ctx, `
		SELECT last_read_seq, hidden_through_seq
		FROM conversation_member_states
		WHERE conversation_id=10 AND user_id=1
	`).Scan(&lastRead, &storedHidden); err != nil {
		t.Fatalf("读取用户甲成员状态失败: %v", err)
	}
	if lastRead != 2 || storedHidden == nil || *storedHidden != 2 {
		t.Fatalf("用户甲成员状态错误: last_read=%d hidden=%v", lastRead, storedHidden)
	}

	newMessage := appendTextMessage(t, messages, 10, 2, "删除后新消息")
	if newMessage.ServerSeq != 3 {
		t.Fatalf("新消息 server_seq=%d want 3", newMessage.ServerSeq)
	}

	userARecovered, _, err := conversations.ListForUser(ctx, 1, 20, 0)
	if err != nil {
		t.Fatalf("新消息后读取用户甲会话失败: %v", err)
	}
	assertConversationSummary(t, userARecovered, 3, 1)
	assertMessageSeqs(t, listSince(t, messages, 10, floorA), []int64{3})
	latest, err := messages.ListLatestAfter(ctx, 10, floorA, 30)
	if err != nil {
		t.Fatalf("读取用户甲最新窗口失败: %v", err)
	}
	assertMessageSeqs(t, latest, []int64{3})
	assertMessageSeqs(t, listSince(t, messages, 10, floorB), []int64{1, 2, 3})
}

func appendTextMessage(t *testing.T, messages *MessageRepo, conversationID, senderID int64, text string) Message {
	t.Helper()
	m := Message{
		ConversationID: conversationID,
		SenderID:       &senderID,
		Kind:           "text",
		Payload:        json.RawMessage(fmt.Sprintf(`{"text":%q}`, text)),
	}
	if err := messages.Append(context.Background(), &m); err != nil {
		t.Fatalf("写入消息 %q 失败: %v", text, err)
	}
	return m
}

func listSince(t *testing.T, messages *MessageRepo, conversationID, since int64) []Message {
	t.Helper()
	items, err := messages.ListSince(context.Background(), conversationID, since, 100)
	if err != nil {
		t.Fatalf("读取消息历史失败: %v", err)
	}
	return items
}

func assertConversationSummary(t *testing.T, items []ConversationSummary, wantLastSeq, wantUnread int64) {
	t.Helper()
	if len(items) != 1 {
		t.Fatalf("会话数量=%d want 1: %+v", len(items), items)
	}
	if items[0].LastMessage == nil || items[0].LastMessage.ServerSeq != wantLastSeq {
		t.Fatalf("最后消息=%+v want seq=%d", items[0].LastMessage, wantLastSeq)
	}
	if items[0].UnreadCount != wantUnread {
		t.Fatalf("未读数=%d want %d", items[0].UnreadCount, wantUnread)
	}
}

func assertMessageSeqs(t *testing.T, items []Message, want []int64) {
	t.Helper()
	if len(items) != len(want) {
		t.Fatalf("消息数=%d want %d: %+v", len(items), len(want), items)
	}
	for i := range items {
		if items[i].ServerSeq != want[i] {
			t.Fatalf("第 %d 条 server_seq=%d want %d", i, items[i].ServerSeq, want[i])
		}
	}
}

func openConversationHideTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("GOMOB_TEST_DB_DSN")
	if dsn == "" {
		t.Skip("未设置 GOMOB_TEST_DB_DSN，跳过 PostgreSQL 会话删除语义测试")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatalf("连接 PostgreSQL 失败: %v", err)
	}
	t.Cleanup(admin.Close)

	schema := fmt.Sprintf("message_hide_%d", time.Now().UnixNano())
	quotedSchema := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+quotedSchema); err != nil {
		t.Fatalf("创建隔离 schema 失败: %v", err)
	}
	t.Cleanup(func() {
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA IF EXISTS "+quotedSchema+" CASCADE")
	})

	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatalf("解析 PostgreSQL DSN 失败: %v", err)
	}
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = make(map[string]string)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("连接隔离 schema 失败: %v", err)
	}
	t.Cleanup(pool.Close)

	if _, err := pool.Exec(ctx, conversationHideTestDDL); err != nil {
		t.Fatalf("创建隔离消息表失败: %v", err)
	}
	return pool
}

const conversationHideTestDDL = `
	CREATE TABLE users (
		id BIGINT PRIMARY KEY,
		real_name TEXT NOT NULL,
		employee_id TEXT NOT NULL
	);
	CREATE TABLE conversations (
		id BIGINT PRIMARY KEY,
		kind TEXT NOT NULL,
		title TEXT,
		p2p_key TEXT,
		subject_kind TEXT,
		subject_id BIGINT,
		next_seq BIGINT NOT NULL DEFAULT 1,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
	);
	CREATE TABLE conversation_members (
		conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
		user_id BIGINT NOT NULL REFERENCES users(id),
		joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		PRIMARY KEY(conversation_id, user_id)
	);
	CREATE TABLE conversation_member_states (
		conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
		user_id BIGINT NOT NULL REFERENCES users(id),
		last_read_seq BIGINT NOT NULL DEFAULT 0,
		muted BOOLEAN NOT NULL DEFAULT false,
		pinned BOOLEAN NOT NULL DEFAULT false,
		hidden_through_seq BIGINT,
		updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		PRIMARY KEY(conversation_id, user_id)
	);
	CREATE TABLE messages (
		id BIGSERIAL PRIMARY KEY,
		conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
		sender_id BIGINT REFERENCES users(id),
		server_seq BIGINT NOT NULL,
		kind TEXT NOT NULL,
		payload JSONB NOT NULL,
		client_msg_id TEXT,
		created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		edited_at TIMESTAMPTZ,
		deleted_at TIMESTAMPTZ,
		UNIQUE(conversation_id, server_seq)
	);
`
