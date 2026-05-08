// gomob-wsharness —— signaling 服务的端到端 harness 客户端。
//
// 不是生产二进制，专门服务于 tests/harness/ws_message_order/run.sh：
//
//   - 通过 gateway 注册 / 登录两个 harness 用户
//   - 同时拉两条 ws，执行：单聊顺序 / 并发顺序 / 离线兜底 / SDP/ICE 转发 / 取消 / 错误
//   - 写 results.jsonl（每行 {scenario, ok, http_code, code, latency_ms, note} 风格）
//
// 设计要点：
//
//   - 每条 ws 后台 goroutine 不停 ReadJSON，进 in chan；用例按需 pull，超时未到的事件视为缺失。
//   - server_seq 单调性 / 无重复 / 无空洞 在并发场景下用集合 + 计数验证。
//   - 离线 invite TTL 用 GOMOB_PENDING_CALL_TTL=3s 让"过期"在合理时间内可观测。
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
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

var (
	gateway    = flag.String("gateway", "http://127.0.0.1:18808", "gateway 入口")
	wsGateway  = flag.String("ws", "ws://127.0.0.1:18808/v1/ws", "ws 入口")
	resultPath = flag.String("out", ".dev/ws_message_order/results.jsonl", "结果 jsonl 路径")

	// 并发场景配置
	burst = flag.Int("burst", 100, "并发场景总消息数")
	conc  = flag.Int("concurrency", 5, "并发 goroutine 数")
)

type result struct {
	Scenario     string `json:"scenario"`
	OK           bool   `json:"ok"`
	HTTPCode     int    `json:"http_code"`
	ExpectedHTTP int    `json:"expected_http"`
	Code         any    `json:"code"`
	ExpectedCode any    `json:"expected_code"`
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

// ============================================================================
// HTTP helpers
// ============================================================================

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

// 注册 + dev 自动激活 + 登录 → access token。
//
// 注意：API 把 int64 ID 序列化为 JSON 字符串避开 JS 精度损失，所以本函数对
// user_id / data.user.id 都按字符串解析。
func registerAndLogin(suffix, role string) (userID int64, token string, err error) {
	username := fmt.Sprintf("ws_%s_%s", role, suffix)
	emp := fmt.Sprintf("WS%s%s", role, suffix)
	status, body, err := httpJSON("POST", *gateway+"/v1/auth/register", map[string]any{
		"username":    username,
		"password":    "ws-pass-1",
		"real_name":   "WS Harness " + role,
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
		"password": "ws-pass-1",
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

// parseFlexInt 容忍 JSON 数值的两种序列化：float64（标准）和 string（big-int safe）。
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

func authHeader(token string) map[string]string {
	return map[string]string{"Authorization": "Bearer " + token}
}

func dataField(body map[string]any, key string) any {
	if body == nil {
		return nil
	}
	data, _ := body["data"].(map[string]any)
	if data == nil {
		return nil
	}
	return data[key]
}

func nestedField(obj map[string]any, outer, inner string) any {
	if obj == nil {
		return nil
	}
	nested, _ := obj[outer].(map[string]any)
	if nested == nil {
		return nil
	}
	return nested[inner]
}

func findConversation(body map[string]any, convID int64) map[string]any {
	if body == nil {
		return nil
	}
	data, _ := body["data"].(map[string]any)
	if data == nil {
		return nil
	}
	items, _ := data["items"].([]any)
	for _, it := range items {
		obj, _ := it.(map[string]any)
		if parseFlexInt(obj["id"]) == convID {
			return obj
		}
	}
	return nil
}

// ============================================================================
// WS 客户端封装
// ============================================================================

type envelope struct {
	Type     string          `json:"type"`
	Payload  json.RawMessage `json:"payload,omitempty"`
	FrameSeq int64           `json:"frame_seq,omitempty"`
	Code     int             `json:"code,omitempty"`
	Message  string          `json:"message,omitempty"`
}

type wsClient struct {
	UserID  int64
	conn    *websocket.Conn
	in      chan envelope
	writeMu sync.Mutex // gorilla/websocket 不支持并发 Write，自己串行化
	closed  atomic.Bool
}

func dialWS(token string, userID int64) (*wsClient, error) {
	u, _ := url.Parse(*wsGateway)
	q := u.Query()
	q.Set("token", token)
	u.RawQuery = q.Encode()
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	c, resp, err := dialer.Dial(u.String(), nil)
	if err != nil {
		body := ""
		if resp != nil {
			b, _ := io.ReadAll(resp.Body)
			body = string(b)
			_ = resp.Body.Close()
		}
		return nil, fmt.Errorf("dial %s: %w (body=%s)", u.String(), err, body)
	}
	wc := &wsClient{UserID: userID, conn: c, in: make(chan envelope, 1024)}
	go wc.readLoop()
	return wc, nil
}

func (w *wsClient) readLoop() {
	defer close(w.in)
	for {
		var e envelope
		if err := w.conn.ReadJSON(&e); err != nil {
			return
		}
		w.in <- e
	}
}

func (w *wsClient) write(e envelope) error {
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	return w.conn.WriteJSON(e)
}

// expect 拉一条特定 type 的消息；超时返回 nil + false。
func (w *wsClient) expect(typ string, timeout time.Duration) (envelope, bool) {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		select {
		case e, ok := <-w.in:
			if !ok {
				return envelope{}, false
			}
			if e.Type == typ {
				return e, true
			}
			// 不匹配的事件丢回？这里直接丢弃，简化用例（单个 ws 一次 expect 时不应该有杂音）
		case <-deadline.C:
			return envelope{}, false
		}
	}
}

// drainUntil 收集到收到 type 为 stop 的为止；可用于把多条无序的 msg.recv 收下来。
func (w *wsClient) drainUntil(stop string, timeout time.Duration) []envelope {
	var out []envelope
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		select {
		case e, ok := <-w.in:
			if !ok {
				return out
			}
			out = append(out, e)
			if e.Type == stop {
				return out
			}
		case <-deadline.C:
			return out
		}
	}
}

// drainFor 在 timeout 内把所有事件都收下来。
func (w *wsClient) drainFor(timeout time.Duration) []envelope {
	var out []envelope
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		select {
		case e, ok := <-w.in:
			if !ok {
				return out
			}
			out = append(out, e)
		case <-deadline.C:
			return out
		}
	}
}

func (w *wsClient) close() {
	if w.closed.Swap(true) {
		return
	}
	_ = w.conn.WriteMessage(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	_ = w.conn.Close()
}

// ============================================================================
// 主流程
// ============================================================================

func main() {
	flag.Parse()
	_ = os.MkdirAll(filepathDir(*resultPath), 0o755)
	f, err := os.Create(*resultPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "创建 results.jsonl 失败:", err)
		os.Exit(2)
	}
	defer f.Close()
	rec := &recorder{f: f}

	suffix := fmt.Sprintf("%d", time.Now().UnixNano())

	// S1 注册 + 登录
	t0 := time.Now()
	uidA, tokenA, err := registerAndLogin(suffix, "A")
	if err != nil || uidA == 0 || tokenA == "" {
		rec.write(result{Scenario: "S1.register_login_A", OK: false, Note: errString(err)})
		fmt.Fprintln(os.Stderr, "用户 A 注册/登录失败:", err)
		os.Exit(3)
	}
	rec.write(result{Scenario: "S1.register_login_A", OK: true, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0), Note: fmt.Sprintf("uid=%d", uidA)})

	t0 = time.Now()
	uidB, tokenB, err := registerAndLogin(suffix, "B")
	if err != nil || uidB == 0 || tokenB == "" {
		rec.write(result{Scenario: "S1b.register_login_B", OK: false, Note: errString(err)})
		os.Exit(3)
	}
	rec.write(result{Scenario: "S1b.register_login_B", OK: true, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0), Note: fmt.Sprintf("uid=%d", uidB)})

	// S2 / S3 拉两条 ws
	t0 = time.Now()
	wsA, err := dialWS(tokenA, uidA)
	if err != nil {
		rec.write(result{Scenario: "S2.connect_A", OK: false, Note: err.Error()})
		os.Exit(3)
	}
	helloA, ok := wsA.expect("hello", 3*time.Second)
	rec.write(result{Scenario: "S2.connect_A", OK: ok && helloA.Type == "hello",
		HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0)})

	t0 = time.Now()
	wsB, err := dialWS(tokenB, uidB)
	if err != nil {
		rec.write(result{Scenario: "S3.connect_B", OK: false, Note: err.Error()})
		os.Exit(3)
	}
	helloB, ok := wsB.expect("hello", 3*time.Second)
	rec.write(result{Scenario: "S3.connect_B", OK: ok && helloB.Type == "hello",
		HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0)})
	defer wsA.close()
	defer func() { wsB.close() }()

	// S4 单条单聊：A → B
	t0 = time.Now()
	if err := wsA.write(envelope{
		Type:    "msg.send",
		Payload: jsonRaw(map[string]any{"to_user_id": uidB, "kind": "text", "content": jsonRaw("hello-1"), "client_msg_id": "c-1"}),
	}); err != nil {
		rec.write(result{Scenario: "S4.send_one", OK: false, Note: err.Error()})
		os.Exit(3)
	}
	delivered, dOK := wsA.expect("msg.delivered", 3*time.Second)
	recv, rOK := wsB.expect("msg.recv", 3*time.Second)
	seq1 := getSeq(delivered)
	seqRecv1 := getSeq(recv)
	s4ok := dOK && rOK && seq1 == 1 && seqRecv1 == 1
	rec.write(result{Scenario: "S4.send_one", OK: s4ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0), Note: fmt.Sprintf("delivered_seq=%d recv_seq=%d", seq1, seqRecv1)})

	// S5 顺序 50 条
	t0 = time.Now()
	N := 50
	for i := 2; i <= 1+N; i++ {
		_ = wsA.write(envelope{
			Type: "msg.send",
			Payload: jsonRaw(map[string]any{
				"to_user_id":    uidB,
				"kind":          "text",
				"content":       jsonRaw(fmt.Sprintf("seq-%d", i)),
				"client_msg_id": fmt.Sprintf("c-%d", i),
			}),
		})
	}
	// 收 N 个 delivered + N 个 recv（B 端）
	deliveredSeqs, recvSeqs := drainSeqs(wsA, "msg.delivered", N, 5*time.Second), drainSeqs(wsB, "msg.recv", N, 5*time.Second)
	// 期望 delivered = recv = [2..N+1]
	expSet := makeRange(2, N+1)
	s5ok := setEqual(deliveredSeqs, expSet) && setEqual(recvSeqs, expSet) &&
		isMonotone(recvSeqs) && noDuplicates(deliveredSeqs) && noDuplicates(recvSeqs)
	rec.write(result{Scenario: "S5.sequential_50", OK: s5ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note: fmt.Sprintf("delivered=%d recv=%d delivered_set=%v recv_mono=%v",
			len(deliveredSeqs), len(recvSeqs), setEqual(deliveredSeqs, expSet), isMonotone(recvSeqs))})

	// S6 并发 burst 条
	t0 = time.Now()
	startSeq := 1 + N + 1 // 52
	endSeq := startSeq + *burst - 1
	var wg sync.WaitGroup
	per := *burst / *conc
	for i := 0; i < *conc; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < per; j++ {
				_ = wsA.write(envelope{
					Type: "msg.send",
					Payload: jsonRaw(map[string]any{
						"to_user_id":    uidB,
						"kind":          "text",
						"content":       jsonRaw(fmt.Sprintf("burst-%d-%d", idx, j)),
						"client_msg_id": fmt.Sprintf("b-%d-%d", idx, j),
					}),
				})
			}
		}(i)
	}
	wg.Wait()
	deliveredBurst := drainSeqs(wsA, "msg.delivered", *burst, 10*time.Second)
	recvBurst := drainSeqs(wsB, "msg.recv", *burst, 10*time.Second)
	expBurst := makeRange(startSeq, endSeq)
	s6ok := setEqual(deliveredBurst, expBurst) && setEqual(recvBurst, expBurst) &&
		isMonotone(recvBurst) && noDuplicates(deliveredBurst) && noDuplicates(recvBurst)
	rec.write(result{Scenario: "S6.concurrent_burst", OK: s6ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note: fmt.Sprintf("burst=%d delivered=%d recv=%d set_eq=%v mono=%v dup=%v",
			*burst, len(deliveredBurst), len(recvBurst),
			setEqual(deliveredBurst, expBurst), isMonotone(recvBurst), !noDuplicates(recvBurst))})

	// S7 fetch since=0：应拿到全部 1..endSeq
	t0 = time.Now()
	convID := getConvIDFromDelivered(deliveredSeqs, deliveredBurst, wsA, wsB, uidA, uidB)
	if convID == 0 {
		// 兜底：A 再发一条以拿到 conversation_id
		_ = wsA.write(envelope{Type: "msg.send", Payload: jsonRaw(map[string]any{
			"to_user_id": uidB, "kind": "text", "content": jsonRaw("conv-probe"),
			"client_msg_id": "probe-1",
		})})
		probe, _ := wsA.expect("msg.delivered", 2*time.Second)
		_, _ = wsB.expect("msg.recv", 2*time.Second)
		convID = getConvID(probe)
	}
	_ = wsA.write(envelope{
		Type: "msg.fetch",
		Payload: jsonRaw(map[string]any{
			"conversation_id": convID,
			"since_seq":       0,
			"limit":           500,
		}),
	})
	fetched, fOK := wsA.expect("msg.fetch_result", 5*time.Second)
	allSeqs := extractFetchSeqs(fetched)
	s7ok := fOK && len(allSeqs) >= endSeq && isMonotone(allSeqs) && noDuplicates(allSeqs) && allSeqs[0] == 1
	rec.write(result{Scenario: "S7.fetch_since_0", OK: s7ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("fetched=%d expected≥%d mono=%v", len(allSeqs), endSeq, isMonotone(allSeqs))})

	// S8 invalid msg.send（无 to_user_id）→ error frame
	t0 = time.Now()
	_ = wsA.write(envelope{Type: "msg.send", Payload: jsonRaw(map[string]any{"kind": "text", "content": jsonRaw("oops")})})
	er, eOK := wsA.expect("error", 2*time.Second)
	rec.write(result{Scenario: "S8.invalid_send", OK: eOK && er.Code == 10001,
		HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0),
		Note: fmt.Sprintf("code=%d msg=%q", er.Code, er.Message)})

	// S16 幂等 client_msg_id：同一客户端消息重发，不重新分配 server_seq，也不重复推给 B。
	t0 = time.Now()
	idemClientID := fmt.Sprintf("idem-%d", time.Now().UnixNano())
	_ = wsA.write(envelope{
		Type: "msg.send",
		Payload: jsonRaw(map[string]any{
			"to_user_id":    uidB,
			"kind":          "text",
			"content":       jsonRaw(map[string]any{"text": "idempotent"}),
			"client_msg_id": idemClientID,
		}),
	})
	idemDelivered1, idemD1OK := wsA.expect("msg.delivered", 3*time.Second)
	idemRecv1, idemR1OK := wsB.expect("msg.recv", 3*time.Second)
	idemSeq1 := getSeq(idemDelivered1)
	_ = wsA.write(envelope{
		Type: "msg.send",
		Payload: jsonRaw(map[string]any{
			"to_user_id":    uidB,
			"kind":          "text",
			"content":       jsonRaw(map[string]any{"text": "idempotent"}),
			"client_msg_id": idemClientID,
		}),
	})
	idemDelivered2, idemD2OK := wsA.expect("msg.delivered", 3*time.Second)
	idemSeq2 := getSeq(idemDelivered2)
	dupRecv := 0
	for _, e := range wsB.drainFor(600 * time.Millisecond) {
		if e.Type == "msg.recv" && getSeq(e) == idemSeq1 {
			dupRecv++
		}
	}
	s16ok := idemD1OK && idemR1OK && idemD2OK &&
		idemSeq1 > 0 && idemSeq1 == idemSeq2 && getSeq(idemRecv1) == idemSeq1 && dupRecv == 0
	rec.write(result{Scenario: "S16.client_msg_id_idempotent", OK: s16ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("seq1=%d seq2=%d dup_recv=%d", idemSeq1, idemSeq2, dupRecv)})

	// S17 HTTP 标记已读：B 把当前最新 seq 标为已读，会话 unread_count 应归零。
	t0 = time.Now()
	statusCode, body, err := httpJSON("POST",
		fmt.Sprintf("%s/v1/conversations/%d/read", *gateway, convID),
		map[string]any{"last_read_seq": idemSeq1},
		authHeader(tokenB),
	)
	readUnread := parseFlexInt(dataField(body, "unread_count"))
	s17ok := err == nil && statusCode == 200 && readUnread == 0
	rec.write(result{Scenario: "S17.http_mark_read", OK: s17ok, HTTPCode: statusCode, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("unread=%d", readUnread)})

	// S18 HTTP 会话列表：last_message 与最新幂等消息一致，且 unread_count=0。
	t0 = time.Now()
	statusCode, body, err = httpJSON("GET", *gateway+"/v1/conversations?limit=20", nil, authHeader(tokenB))
	conv := findConversation(body, convID)
	lastSeq := parseFlexInt(nestedField(conv, "last_message", "server_seq"))
	listUnread := parseFlexInt(conv["unread_count"])
	s18ok := err == nil && statusCode == 200 && lastSeq == idemSeq1 && listUnread == 0
	rec.write(result{Scenario: "S18.http_conversation_list", OK: s18ok, HTTPCode: statusCode, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("last_seq=%d unread=%d", lastSeq, listUnread)})

	// S9 离线 invite：B 断开 → A 发 call.invite → A 应收 invite_ack online=false
	t0 = time.Now()
	wsB.close()
	time.Sleep(500 * time.Millisecond) // 等 hub.Unregister
	_ = wsA.write(envelope{
		Type: "call.invite",
		Payload: jsonRaw(map[string]any{
			"to_user_id": uidB,
			"sdp":        jsonRaw(map[string]any{"type": "offer", "sdp": "v=0\r\n"}),
		}),
	})
	ack, aOK := wsA.expect("call.invite_ack", 3*time.Second)
	online, callIDInvite := getInviteAckFields(ack)
	s9ok := aOK && !online && callIDInvite != ""
	rec.write(result{Scenario: "S9.invite_offline", OK: s9ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("online=%v call_id=%q", online, callIDInvite)})

	// S10 B 重连 → 应在 2s 内收到 pending invite (with pending=true)
	t0 = time.Now()
	wsB2, err := dialWS(tokenB, uidB)
	if err != nil {
		rec.write(result{Scenario: "S10.reconnect_B", OK: false, Note: err.Error()})
		os.Exit(3)
	}
	defer wsB2.close()
	_, _ = wsB2.expect("hello", 2*time.Second)
	pendingInvite, pOK := wsB2.expect("call.invite", 5*time.Second)
	pendingFlag, recvCallID := getCallInviteFields(pendingInvite)
	s10ok := pOK && pendingFlag && recvCallID == callIDInvite
	rec.write(result{Scenario: "S10.pending_delivered_on_reconnect", OK: s10ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("pending=%v call_id=%q", pendingFlag, recvCallID)})

	// S11 B 给 A 发 call.answer → A 应收
	t0 = time.Now()
	_ = wsB2.write(envelope{
		Type: "call.answer",
		Payload: jsonRaw(map[string]any{
			"call_id":    callIDInvite,
			"to_user_id": uidA,
			"sdp":        jsonRaw(map[string]any{"type": "answer", "sdp": "v=0-answer\r\n"}),
		}),
	})
	ans, ansOK := wsA.expect("call.answer", 3*time.Second)
	rec.write(result{Scenario: "S11.answer_relay", OK: ansOK && getCallID(ans) == callIDInvite,
		HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0)})

	// S12 ICE 双向：A → B
	t0 = time.Now()
	_ = wsA.write(envelope{
		Type: "call.ice",
		Payload: jsonRaw(map[string]any{
			"call_id":    callIDInvite,
			"to_user_id": uidB,
			"candidate":  jsonRaw(map[string]any{"candidate": "candidate:1 1 UDP 1 1.1.1.1 9 typ host"}),
		}),
	})
	ice1, ice1OK := wsB2.expect("call.ice", 3*time.Second)
	// B → A
	_ = wsB2.write(envelope{
		Type: "call.ice",
		Payload: jsonRaw(map[string]any{
			"call_id":    callIDInvite,
			"to_user_id": uidA,
			"candidate":  jsonRaw(map[string]any{"candidate": "candidate:2 1 UDP 1 2.2.2.2 9 typ host"}),
		}),
	})
	ice2, ice2OK := wsA.expect("call.ice", 3*time.Second)
	s12ok := ice1OK && ice2OK && getCallID(ice1) == callIDInvite && getCallID(ice2) == callIDInvite
	rec.write(result{Scenario: "S12.ice_bidi", OK: s12ok, HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0)})

	// S13 call.bye：A 挂断 → B 应收
	t0 = time.Now()
	_ = wsA.write(envelope{
		Type: "call.bye",
		Payload: jsonRaw(map[string]any{
			"call_id":    callIDInvite,
			"to_user_id": uidB,
			"reason":     "hangup",
		}),
	})
	bye, byeOK := wsB2.expect("call.bye", 3*time.Second)
	rec.write(result{Scenario: "S13.bye_relay", OK: byeOK && getCallID(bye) == callIDInvite,
		HTTPCode: 200, ExpectedHTTP: 200, LatencyMS: msSince(t0)})

	// S14 离线 invite TTL：B2 再断；A 发 invite；3s+清扫周期后查 pending_calls 状态应该是 expired。
	// 但本 harness 不直接查 PG（不引入 pgx）；通过 invite_ack online=false + 等 GOMOB_PENDING_CALL_TTL+sweep 后再开 B → 应不再收到该 invite
	// 来间接验证 TTL。
	t0 = time.Now()
	wsB2.close()
	time.Sleep(500 * time.Millisecond)
	_ = wsA.write(envelope{
		Type: "call.invite",
		Payload: jsonRaw(map[string]any{
			"to_user_id": uidB,
			"sdp":        jsonRaw(map[string]any{"type": "offer", "sdp": "ttl-test"}),
		}),
	})
	ttlAck, ttlOK := wsA.expect("call.invite_ack", 3*time.Second)
	_, ttlCallID := getInviteAckFields(ttlAck)
	// 等 ttl + 一个 sweep 周期；run.sh 设 TTL=3s SWEEP=1s，总等 5s。
	time.Sleep(5 * time.Second)
	wsB3, err := dialWS(tokenB, uidB)
	var sawExpiredInvite bool
	if err == nil {
		_, _ = wsB3.expect("hello", 2*time.Second)
		evs := wsB3.drainFor(2 * time.Second)
		for _, e := range evs {
			if e.Type == "call.invite" {
				_, cid := getCallInviteFields(e)
				if cid == ttlCallID {
					sawExpiredInvite = true
				}
			}
		}
		wsB3.close()
	}
	s14ok := ttlOK && ttlCallID != "" && !sawExpiredInvite
	rec.write(result{Scenario: "S14.invite_ttl_expire", OK: s14ok, HTTPCode: 200, ExpectedHTTP: 200,
		LatencyMS: msSince(t0),
		Note:      fmt.Sprintf("ttl_call_id=%q saw_after_expire=%v", ttlCallID, sawExpiredInvite)})

	// S15 audit：通过 admin 接口拉 audit；用例期望 message.send / call.invite 至少 N 条
	// admin 接口需要 admin 角色，注册时是 inspector — 这里跳过严格断言，仅观察。
	// 通过 signaling 自暴露的 /v1/signaling/online 间接验证连接已被注册：
	t0 = time.Now()
	statusCode, body, err = httpJSON("GET", *gateway+"/v1/signaling/online", nil, nil)
	onlineCount := 0
	if arr, ok := body["users"].([]any); ok {
		onlineCount = len(arr)
	}
	rec.write(result{Scenario: "S15.online_endpoint", OK: err == nil && statusCode == 200,
		HTTPCode: statusCode, ExpectedHTTP: 200, LatencyMS: msSince(t0),
		Note: fmt.Sprintf("online=%d", onlineCount)})

	// 总结
	_ = f.Sync()
	fmt.Println("✓ harness done")
}

// ============================================================================
// 工具
// ============================================================================

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func msSince(t time.Time) int64 { return time.Since(t).Milliseconds() }

func jsonRaw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func getSeq(e envelope) int64 {
	var p struct {
		ServerSeq int64 `json:"server_seq"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	return p.ServerSeq
}

func getConvID(e envelope) int64 {
	var p struct {
		ConversationID int64 `json:"conversation_id"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	return p.ConversationID
}

func getCallID(e envelope) string {
	var p struct {
		CallID string `json:"call_id"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	return p.CallID
}

func getInviteAckFields(e envelope) (online bool, callID string) {
	var p struct {
		Online bool   `json:"online"`
		CallID string `json:"call_id"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	return p.Online, p.CallID
}

func getCallInviteFields(e envelope) (pending bool, callID string) {
	var p struct {
		Pending bool   `json:"pending"`
		CallID  string `json:"call_id"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	return p.Pending, p.CallID
}

func extractFetchSeqs(e envelope) []int64 {
	var p struct {
		Items []struct {
			ServerSeq int64 `json:"server_seq"`
		} `json:"items"`
	}
	_ = json.Unmarshal(e.Payload, &p)
	out := make([]int64, 0, len(p.Items))
	for _, it := range p.Items {
		out = append(out, it.ServerSeq)
	}
	return out
}

func drainSeqs(w *wsClient, typ string, count int, timeout time.Duration) []int64 {
	out := make([]int64, 0, count)
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for len(out) < count {
		select {
		case e, ok := <-w.in:
			if !ok {
				return out
			}
			if e.Type != typ {
				continue
			}
			out = append(out, getSeq(e))
		case <-deadline.C:
			return out
		}
	}
	return out
}

func makeRange(from, to int) []int64 {
	out := make([]int64, 0, to-from+1)
	for i := from; i <= to; i++ {
		out = append(out, int64(i))
	}
	return out
}

func setEqual(a, b []int64) bool {
	if len(a) != len(b) {
		return false
	}
	ac := append([]int64{}, a...)
	bc := append([]int64{}, b...)
	sort.Slice(ac, func(i, j int) bool { return ac[i] < ac[j] })
	sort.Slice(bc, func(i, j int) bool { return bc[i] < bc[j] })
	for i := range ac {
		if ac[i] != bc[i] {
			return false
		}
	}
	return true
}

func isMonotone(a []int64) bool {
	for i := 1; i < len(a); i++ {
		if a[i] <= a[i-1] {
			return false
		}
	}
	return true
}

func noDuplicates(a []int64) bool {
	seen := make(map[int64]struct{}, len(a))
	for _, v := range a {
		if _, ok := seen[v]; ok {
			return false
		}
		seen[v] = struct{}{}
	}
	return true
}

func filepathDir(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' {
			return p[:i]
		}
	}
	return "."
}

// getConvIDFromDelivered 把第一波 deliver 中含 conversation_id 的事件拿出来。
// 这里 deliveredSeqs 是 []int64，并不携带 conv_id；保留这个函数签名是为了将来扩展。
// 当前直接走 fallback 写一条探针消息拿 conversation_id。
func getConvIDFromDelivered(_ []int64, _ []int64, _ *wsClient, _ *wsClient, _, _ int64) int64 {
	return 0
}
