// gomob-realtimeharness —— M5.2 实时消息重连补齐 harness 客户端。
//
// 它只验证控制面真实链路：注册登录、WebSocket 发送、断线、重连后 msg.fetch
// 补齐。媒体流和 Android UI 不在本 harness 范围内。
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	gateway    = flag.String("gateway", "http://127.0.0.1:18808", "gateway 入口")
	wsGateway  = flag.String("ws", "ws://127.0.0.1:18808/v1/ws", "ws 入口")
	resultPath = flag.String("out", ".dev/realtime_message_sync/results.jsonl", "结果 jsonl 路径")
)

type result struct {
	Scenario     string `json:"scenario"`
	OK           bool   `json:"ok"`
	HTTPCode     int    `json:"http_code"`
	ExpectedHTTP int    `json:"expected_http"`
	Code         any    `json:"code,omitempty"`
	ExpectedCode any    `json:"expected_code,omitempty"`
	LatencyMS    int64  `json:"latency_ms"`
	Note         string `json:"note,omitempty"`
}

type recorder struct {
	mu sync.Mutex
	f  *os.File
}

func (r *recorder) write(res result) {
	r.mu.Lock()
	defer r.mu.Unlock()
	b, _ := json.Marshal(res)
	_, _ = r.f.Write(b)
	_, _ = r.f.Write([]byte("\n"))
}

type envelope struct {
	Type     string          `json:"type"`
	Payload  json.RawMessage `json:"payload,omitempty"`
	FrameSeq int64           `json:"frame_seq,omitempty"`
	Code     int             `json:"code,omitempty"`
	Message  string          `json:"message,omitempty"`
}

type deliveredPayload struct {
	ClientMsgID    string `json:"client_msg_id"`
	ConversationID int64  `json:"conversation_id"`
	ServerSeq      int64  `json:"server_seq"`
	MessageID      int64  `json:"message_id"`
	CreatedAt      string `json:"created_at"`
}

type recvPayload struct {
	ConversationID int64           `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	SenderID       int64           `json:"sender_id"`
	Kind           string          `json:"kind"`
	Content        json.RawMessage `json:"content"`
	CreatedAt      string          `json:"created_at"`
}

type fetchPayload struct {
	ConversationID int64         `json:"conversation_id"`
	Items          []recvPayload `json:"items"`
	NextSinceSeq   int64         `json:"next_since_seq"`
}

type wsClient struct {
	name    string
	userID  int64
	token   string
	conn    *websocket.Conn
	in      chan envelope
	writeMu sync.Mutex
}

func main() {
	flag.Parse()
	if err := os.MkdirAll(filepath.Dir(*resultPath), 0o755); err != nil {
		panic(err)
	}
	f, err := os.Create(*resultPath)
	if err != nil {
		panic(err)
	}
	defer f.Close()
	rec := &recorder{f: f}

	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	var a, b *wsClient
	var convID, seq1, seq2, seq3 int64
	var dupSame bool
	exitCode := 0
	defer func() {
		if a != nil {
			a.close()
		}
		if b != nil {
			b.close()
		}
		os.Exit(exitCode)
	}()

	ok := step(rec, "R1.register_login", func() (string, bool) {
		uidA, tokenA, err := registerAndLogin("a_"+suffix, "inspector")
		if err != nil {
			return err.Error(), false
		}
		uidB, tokenB, err := registerAndLogin("b_"+suffix, "reviewer")
		if err != nil {
			return err.Error(), false
		}
		a = &wsClient{name: "A", userID: uidA, token: tokenA}
		b = &wsClient{name: "B", userID: uidB, token: tokenB}
		return fmt.Sprintf("uidA=%d uidB=%d", uidA, uidB), uidA > 0 && uidB > 0 && tokenA != "" && tokenB != ""
	})
	if !ok {
		exitCode = 1
		return
	}

	ok = step(rec, "R2.initial_online_sync", func() (string, bool) {
		if err := a.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := a.waitType("hello", 3*time.Second); err != nil {
			return "A hello: " + err.Error(), false
		}
		if err := b.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := b.waitType("hello", 3*time.Second); err != nil {
			return "B hello: " + err.Error(), false
		}
		if err := a.sendMessage(b.userID, "sync-1", "在线第一条"); err != nil {
			return err.Error(), false
		}
		del, err := a.waitDelivered("sync-1", 3*time.Second)
		if err != nil {
			return err.Error(), false
		}
		recv, err := b.waitRecv(3 * time.Second)
		if err != nil {
			return err.Error(), false
		}
		convID = del.ConversationID
		seq1 = del.ServerSeq
		pass := convID > 0 && del.ServerSeq == recv.ServerSeq && del.ServerSeq == 1
		return fmt.Sprintf("conversation_id=%d seq=%d recv_seq=%d", convID, del.ServerSeq, recv.ServerSeq), pass
	})
	if !ok {
		exitCode = 1
		return
	}

	ok = step(rec, "R3.offline_send_and_idempotent_retry", func() (string, bool) {
		b.close()
		time.Sleep(200 * time.Millisecond)

		if err := a.sendMessage(b.userID, "sync-2", "离线第二条"); err != nil {
			return err.Error(), false
		}
		del2, err := a.waitDelivered("sync-2", 3*time.Second)
		if err != nil {
			return err.Error(), false
		}
		if err := a.sendMessage(b.userID, "sync-3", "离线第三条"); err != nil {
			return err.Error(), false
		}
		del3a, err := a.waitDelivered("sync-3", 3*time.Second)
		if err != nil {
			return err.Error(), false
		}
		if err := a.sendMessage(b.userID, "sync-3", "离线第三条重试"); err != nil {
			return err.Error(), false
		}
		del3b, err := a.waitDelivered("sync-3", 3*time.Second)
		if err != nil {
			return err.Error(), false
		}

		seq2 = del2.ServerSeq
		seq3 = del3a.ServerSeq
		dupSame = del3a.ServerSeq == del3b.ServerSeq && del3a.MessageID == del3b.MessageID
		pass := seq2 == seq1+1 && seq3 == seq1+2 && dupSame
		return fmt.Sprintf("seq2=%d seq3=%d retry_seq=%d retry_same=%v", seq2, seq3, del3b.ServerSeq, dupSame), pass
	})
	if !ok {
		exitCode = 1
		return
	}

	var fetched fetchPayload
	ok = step(rec, "R4.reconnect_fetch_gapless", func() (string, bool) {
		if err := b.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := b.waitType("hello", 3*time.Second); err != nil {
			return "B reconnect hello: " + err.Error(), false
		}
		got, err := b.fetch(convID, seq1, 20)
		if err != nil {
			return err.Error(), false
		}
		fetched = got
		seqs := make([]int64, 0, len(got.Items))
		for _, item := range got.Items {
			seqs = append(seqs, item.ServerSeq)
		}
		pass := len(seqs) == 2 && seqs[0] == seq2 && seqs[1] == seq3 && got.NextSinceSeq == seq3
		return fmt.Sprintf("since=%d fetched=%v next=%d", seq1, seqs, got.NextSinceSeq), pass
	})
	if !ok {
		exitCode = 1
		return
	}

	ok = step(rec, "R5.no_duplicate_after_retry", func() (string, bool) {
		seen := map[int64]int{}
		for _, item := range fetched.Items {
			seen[item.ServerSeq]++
		}
		pass := dupSame && len(seen) == len(fetched.Items)
		for seq, count := range seen {
			if count != 1 || (seq != seq2 && seq != seq3) {
				pass = false
			}
		}
		return fmt.Sprintf("seen=%v retry_same=%v", seen, dupSame), pass
	})
	if !ok {
		exitCode = 1
		return
	}

	ok = step(rec, "R6.fetch_since_latest_empty", func() (string, bool) {
		got, err := b.fetch(convID, seq3, 20)
		if err != nil {
			return err.Error(), false
		}
		pass := len(got.Items) == 0 && got.NextSinceSeq == seq3
		return fmt.Sprintf("since=%d fetched=%d next=%d", seq3, len(got.Items), got.NextSinceSeq), pass
	})
	if !ok {
		exitCode = 1
	}
}

func step(rec *recorder, name string, fn func() (string, bool)) bool {
	start := time.Now()
	note, ok := fn()
	rec.write(result{
		Scenario:     name,
		OK:           ok,
		HTTPCode:     200,
		ExpectedHTTP: 200,
		LatencyMS:    time.Since(start).Milliseconds(),
		Note:         note,
	})
	return ok
}

func (c *wsClient) connect(base string) error {
	u, err := url.Parse(base)
	if err != nil {
		return err
	}
	q := u.Query()
	q.Set("token", c.token)
	u.RawQuery = q.Encode()
	conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		return fmt.Errorf("%s connect: %w", c.name, err)
	}
	c.conn = conn
	c.in = make(chan envelope, 128)
	go func() {
		defer close(c.in)
		for {
			var env envelope
			if err := conn.ReadJSON(&env); err != nil {
				return
			}
			c.in <- env
		}
	}()
	return nil
}

func (c *wsClient) close() {
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
	}
}

func (c *wsClient) waitType(kind string, timeout time.Duration) (envelope, error) {
	deadline := time.After(timeout)
	for {
		select {
		case env, ok := <-c.in:
			if !ok {
				return envelope{}, fmt.Errorf("%s ws 已关闭，等待 %s 失败", c.name, kind)
			}
			if env.Type == kind {
				return env, nil
			}
			if env.Type == "error" {
				return env, fmt.Errorf("%s 收到 error code=%d message=%s", c.name, env.Code, env.Message)
			}
		case <-deadline:
			return envelope{}, fmt.Errorf("%s 等待 %s 超时", c.name, kind)
		}
	}
}

func (c *wsClient) write(env envelope) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.conn == nil {
		return fmt.Errorf("%s ws 未连接", c.name)
	}
	return c.conn.WriteJSON(env)
}

func (c *wsClient) sendMessage(toUserID int64, clientMsgID, text string) error {
	return c.write(envelope{
		Type: "msg.send",
		Payload: jsonRaw(map[string]any{
			"to_user_id":    toUserID,
			"kind":          "text",
			"content":       jsonRaw(map[string]any{"text": text}),
			"client_msg_id": clientMsgID,
		}),
	})
}

func (c *wsClient) waitDelivered(clientMsgID string, timeout time.Duration) (deliveredPayload, error) {
	deadline := time.After(timeout)
	for {
		select {
		case env, ok := <-c.in:
			if !ok {
				return deliveredPayload{}, fmt.Errorf("%s ws 已关闭，等待 delivered 失败", c.name)
			}
			if env.Type == "error" {
				return deliveredPayload{}, fmt.Errorf("%s 收到 error code=%d message=%s", c.name, env.Code, env.Message)
			}
			if env.Type != "msg.delivered" {
				continue
			}
			var del deliveredPayload
			if err := json.Unmarshal(env.Payload, &del); err != nil {
				return deliveredPayload{}, err
			}
			if del.ClientMsgID == clientMsgID {
				return del, nil
			}
		case <-deadline:
			return deliveredPayload{}, fmt.Errorf("%s 等待 client_msg_id=%s delivered 超时", c.name, clientMsgID)
		}
	}
}

func (c *wsClient) waitRecv(timeout time.Duration) (recvPayload, error) {
	env, err := c.waitType("msg.recv", timeout)
	if err != nil {
		return recvPayload{}, err
	}
	var recv recvPayload
	if err := json.Unmarshal(env.Payload, &recv); err != nil {
		return recvPayload{}, err
	}
	return recv, nil
}

func (c *wsClient) fetch(conversationID, sinceSeq int64, limit int) (fetchPayload, error) {
	if err := c.write(envelope{
		Type: "msg.fetch",
		Payload: jsonRaw(map[string]any{
			"conversation_id": conversationID,
			"since_seq":       sinceSeq,
			"limit":           limit,
		}),
	}); err != nil {
		return fetchPayload{}, err
	}
	env, err := c.waitType("msg.fetch_result", 3*time.Second)
	if err != nil {
		return fetchPayload{}, err
	}
	var out fetchPayload
	if err := json.Unmarshal(env.Payload, &out); err != nil {
		return fetchPayload{}, err
	}
	return out, nil
}

func httpJSON(method, urlStr string, body any, headers map[string]string) (int, map[string]any, error) {
	var rdr io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, urlStr, rdr)
	if err != nil {
		return 0, nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	bs, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(bs, &out)
	return resp.StatusCode, out, nil
}

func registerAndLogin(suffix, role string) (userID int64, token string, err error) {
	username := fmt.Sprintf("rt_%s_%s", role, suffix)
	emp := fmt.Sprintf("RT%s%s", role, suffix)
	status, body, err := httpJSON("POST", *gateway+"/v1/auth/register", map[string]any{
		"username":    username,
		"password":    "rt-pass-1",
		"real_name":   "Realtime Harness " + role,
		"employee_id": emp,
	}, nil)
	if err != nil {
		return 0, "", err
	}
	if status != 200 {
		return 0, "", fmt.Errorf("register http=%d body=%v", status, body)
	}
	if data, ok := body["data"].(map[string]any); ok {
		userID = parseFlexInt(data["user_id"])
	}

	status, body, err = httpJSON("POST", *gateway+"/v1/auth/login", map[string]any{
		"username": username,
		"password": "rt-pass-1",
	}, nil)
	if err != nil {
		return 0, "", err
	}
	if status != 200 {
		return 0, "", fmt.Errorf("login http=%d body=%v", status, body)
	}
	data, _ := body["data"].(map[string]any)
	if data == nil {
		return 0, "", fmt.Errorf("login 返回不含 data: %v", body)
	}
	token, _ = data["access_token"].(string)
	if userID == 0 {
		if user, ok := data["user"].(map[string]any); ok {
			userID = parseFlexInt(user["id"])
		}
	}
	if userID == 0 || token == "" {
		return 0, "", fmt.Errorf("login 解析失败 uid=%d token_len=%d", userID, len(token))
	}
	return userID, token, nil
}

func parseFlexInt(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case string:
		var n int64
		_, _ = fmt.Sscan(x, &n)
		return n
	}
	return 0
}

func jsonRaw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
