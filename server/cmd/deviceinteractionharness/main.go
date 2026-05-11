// gomob-deviceinteractionharness —— 设备实时交互日志 harness 客户端。
//
// 目标是模拟“模拟器端”和“真机端”两个登录用户，跑真实 gateway / REST /
// WebSocket 消息链路，并探测 LiveKit 媒体控制面是否已经实现。媒体端点缺失
// 会记录为 warn/blocker，不伪装成直播已通过。
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	gateway    = flag.String("gateway", "http://127.0.0.1:18808", "gateway 入口")
	wsGateway  = flag.String("ws", "ws://127.0.0.1:18808/v1/ws", "ws 入口")
	resultPath = flag.String("out", ".dev/device_realtime_interaction/results.jsonl", "结果 jsonl 路径")
	timeout    = flag.Duration("timeout", 3*time.Second, "单步等待超时")
)

type result struct {
	Scenario       string `json:"scenario"`
	Area           string `json:"area"`
	Actor          string `json:"actor,omitempty"`
	Peer           string `json:"peer,omitempty"`
	OK             bool   `json:"ok"`
	Severity       string `json:"severity"`
	HTTPCode       int    `json:"http_code,omitempty"`
	ExpectedHTTP   any    `json:"expected_http,omitempty"`
	LatencyMS      int64  `json:"latency_ms"`
	ConversationID int64  `json:"conversation_id,omitempty"`
	ServerSeq      int64  `json:"server_seq,omitempty"`
	ClientMsgID    string `json:"client_msg_id,omitempty"`
	Note           string `json:"note,omitempty"`
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
	MessageID      int64           `json:"message_id"`
	ConversationID int64           `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	SenderID       int64           `json:"sender_id"`
	Kind           string          `json:"kind"`
	Content        json.RawMessage `json:"content"`
	ClientMsgID    string          `json:"client_msg_id"`
	CreatedAt      string          `json:"created_at"`
}

type fetchPayload struct {
	ConversationID int64         `json:"conversation_id"`
	Items          []recvPayload `json:"items"`
	NextSinceSeq   int64         `json:"next_since_seq"`
}

type uploadedAsset struct {
	AssetID     string
	ObjectKey   string
	DownloadURL string
	SHA256      string
	SizeBytes   int
	MIME        string
}

type wsClient struct {
	name    string
	role    string
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

	rec := &recorder{f: f}
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	emulator := &wsClient{name: "emulator-sim", role: "inspector"}
	phone := &wsClient{name: "phone-sim", role: "reviewer"}

	exitCode := 0
	defer func() {
		emulator.close()
		phone.close()
		_ = f.Close()
		if exitCode != 0 {
			os.Exit(exitCode)
		}
	}()

	if !step(rec, baseResult("D1.register_login", "message", "", ""), func(res *result) (string, bool) {
		uid, token, err := registerAndLogin("emulator_"+suffix, emulator.role)
		if err != nil {
			return err.Error(), false
		}
		emulator.userID, emulator.token = uid, token
		uid, token, err = registerAndLogin("phone_"+suffix, phone.role)
		if err != nil {
			return err.Error(), false
		}
		phone.userID, phone.token = uid, token
		return fmt.Sprintf("emulator_uid=%d phone_uid=%d", emulator.userID, phone.userID),
			emulator.userID > 0 && phone.userID > 0 && emulator.token != "" && phone.token != ""
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D2.websocket_online", "message", "emulator-sim", "phone-sim"), func(res *result) (string, bool) {
		if err := emulator.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := emulator.waitType("hello", *timeout); err != nil {
			return "emulator hello: " + err.Error(), false
		}
		if err := phone.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := phone.waitType("hello", *timeout); err != nil {
			return "phone hello: " + err.Error(), false
		}
		return "两个模拟设备均收到 hello", true
	}) {
		exitCode = 1
		return
	}

	var convID int64
	var seq1, seq2, seq3 int64
	if !step(rec, baseResult("D3.emulator_to_phone_message", "message", "emulator-sim", "phone-sim"), func(res *result) (string, bool) {
		clientMsgID := "emu-phone-" + suffix
		res.ClientMsgID = clientMsgID
		if err := emulator.sendMessage(phone.userID, clientMsgID, "模拟器发给真机的 harness 消息"); err != nil {
			return err.Error(), false
		}
		del, err := emulator.waitDelivered(clientMsgID, *timeout)
		if err != nil {
			return err.Error(), false
		}
		recv, err := phone.waitRecv(*timeout)
		if err != nil {
			return err.Error(), false
		}
		convID, seq1 = del.ConversationID, del.ServerSeq
		res.ConversationID = convID
		res.ServerSeq = seq1
		pass := convID > 0 && seq1 == 1 && recv.ConversationID == convID && recv.ServerSeq == seq1 && recv.SenderID == emulator.userID
		return fmt.Sprintf("conversation_id=%d delivered_seq=%d recv_seq=%d sender=%d", convID, seq1, recv.ServerSeq, recv.SenderID), pass
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D4.phone_to_emulator_message", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		clientMsgID := "phone-emu-" + suffix
		res.ClientMsgID = clientMsgID
		if err := phone.sendMessage(emulator.userID, clientMsgID, "真机发给模拟器的 harness 消息"); err != nil {
			return err.Error(), false
		}
		del, err := phone.waitDelivered(clientMsgID, *timeout)
		if err != nil {
			return err.Error(), false
		}
		recv, err := emulator.waitRecv(*timeout)
		if err != nil {
			return err.Error(), false
		}
		seq2 = del.ServerSeq
		res.ConversationID = del.ConversationID
		res.ServerSeq = seq2
		pass := del.ConversationID == convID && seq2 == seq1+1 && recv.ConversationID == convID && recv.ServerSeq == seq2 && recv.SenderID == phone.userID
		return fmt.Sprintf("conversation_id=%d delivered_seq=%d recv_seq=%d sender=%d", del.ConversationID, seq2, recv.ServerSeq, recv.SenderID), pass
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D5.rest_history_and_read", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		status, convBody, err := httpJSON("GET", fmt.Sprintf("%s/v1/conversations?limit=20", *gateway), nil, authHeader(phone.token))
		res.HTTPCode = status
		res.ExpectedHTTP = 200
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("conversations http=%d body=%v", status, convBody), false
		}
		if !conversationListHas(convBody, convID) {
			return fmt.Sprintf("会话列表未包含 conversation_id=%d body=%v", convID, convBody), false
		}
		status, msgBody, err := httpJSON("GET", fmt.Sprintf("%s/v1/conversations/%d/messages?since_seq=0&limit=20", *gateway, convID), nil, authHeader(phone.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			res.HTTPCode = status
			return fmt.Sprintf("messages http=%d body=%v", status, msgBody), false
		}
		seqs := messageSeqs(msgBody)
		if len(seqs) < 2 || seqs[0] != seq1 || seqs[1] != seq2 {
			return fmt.Sprintf("历史消息 seq 不连续 got=%v want=[%d %d]", seqs, seq1, seq2), false
		}
		status, readBody, err := httpJSON("POST", fmt.Sprintf("%s/v1/conversations/%d/read", *gateway, convID), map[string]any{"last_read_seq": seq2}, authHeader(phone.token))
		res.HTTPCode = status
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("read http=%d body=%v", status, readBody), false
		}
		res.ConversationID = convID
		res.ServerSeq = seq2
		return fmt.Sprintf("seqs=%v read_seq=%d", seqs, seq2), true
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D6.offline_reconnect_fetch", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		emulator.close()
		time.Sleep(200 * time.Millisecond)
		clientMsgID := "offline-phone-emu-" + suffix
		res.ClientMsgID = clientMsgID
		if err := phone.sendMessage(emulator.userID, clientMsgID, "真机离线补齐消息"); err != nil {
			return err.Error(), false
		}
		del, err := phone.waitDelivered(clientMsgID, *timeout)
		if err != nil {
			return err.Error(), false
		}
		seq3 = del.ServerSeq
		if err := emulator.connect(*wsGateway); err != nil {
			return err.Error(), false
		}
		if _, err := emulator.waitType("hello", *timeout); err != nil {
			return "emulator reconnect hello: " + err.Error(), false
		}
		got, err := emulator.fetch(convID, seq2, 20)
		if err != nil {
			return err.Error(), false
		}
		seqs := make([]int64, 0, len(got.Items))
		for _, item := range got.Items {
			seqs = append(seqs, item.ServerSeq)
		}
		res.ConversationID = convID
		res.ServerSeq = seq3
		pass := seq3 == seq2+1 && len(seqs) == 1 && seqs[0] == seq3 && got.NextSinceSeq == seq3
		return fmt.Sprintf("offline_seq=%d fetched=%v next=%d", seq3, seqs, got.NextSinceSeq), pass
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D7.http_fallback_realtime_push", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		clientMsgID := "rest-phone-emu-" + suffix
		res.ClientMsgID = clientMsgID
		status, body, err := httpJSON("POST", fmt.Sprintf("%s/v1/conversations/%d/messages", *gateway, convID), map[string]any{
			"client_msg_id": clientMsgID,
			"kind":          "text",
			"payload":       map[string]any{"text": "HTTP fallback 实时推送消息"},
		}, authHeader(phone.token))
		res.HTTPCode = status
		res.ExpectedHTTP = 200
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("http send http=%d body=%v", status, body), false
		}
		data := dataMap(body)
		httpSeq := parseFlexInt(data["server_seq"])
		httpConvID := parseFlexInt(data["conversation_id"])
		httpMessageID := parseFlexInt(data["id"])
		recv, err := emulator.waitRecv(*timeout)
		if err != nil {
			return err.Error(), false
		}
		res.ConversationID = httpConvID
		res.ServerSeq = httpSeq
		pass := httpConvID == convID &&
			httpSeq == seq3+1 &&
			recv.MessageID == httpMessageID &&
			recv.ConversationID == convID &&
			recv.ServerSeq == httpSeq &&
			recv.SenderID == phone.userID &&
			recv.Kind == "text" &&
			recv.ClientMsgID == clientMsgID
		return fmt.Sprintf(
			"http_seq=%d recv_seq=%d message_id=%d recv_message_id=%d sender=%d",
			httpSeq,
			recv.ServerSeq,
			httpMessageID,
			recv.MessageID,
			recv.SenderID,
		), pass
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D8.voice_asset_upload_and_message", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		voiceBytes := []byte("gomob voice harness sample\n")
		asset, err := uploadMediaAsset(phone.token, "message_voice", "audio/mp4", voiceBytes)
		if err != nil {
			return err.Error(), false
		}
		clientMsgID := "voice-phone-emu-" + suffix
		res.ClientMsgID = clientMsgID
		status, body, err := httpJSON("POST", fmt.Sprintf("%s/v1/conversations/%d/messages", *gateway, convID), map[string]any{
			"client_msg_id": clientMsgID,
			"kind":          "voice",
			"payload": map[string]any{
				"media_state":  "ready",
				"asset_id":     asset.AssetID,
				"object_key":   asset.ObjectKey,
				"mime":         asset.MIME,
				"size_bytes":   asset.SizeBytes,
				"sha256":       asset.SHA256,
				"download_url": asset.DownloadURL,
				"duration_sec": 1,
				"source":       "harness_voice",
			},
		}, authHeader(phone.token))
		res.HTTPCode = status
		res.ExpectedHTTP = 200
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("voice send http=%d body=%v", status, body), false
		}
		data := dataMap(body)
		httpSeq := parseFlexInt(data["server_seq"])
		recv, err := emulator.waitRecv(*timeout)
		if err != nil {
			return err.Error(), false
		}
		res.ConversationID = convID
		res.ServerSeq = httpSeq
		pass := asset.AssetID != "" &&
			asset.ObjectKey != "" &&
			httpSeq > 0 &&
			recv.ConversationID == convID &&
			recv.ServerSeq == httpSeq &&
			recv.Kind == "voice" &&
			recv.ClientMsgID == clientMsgID
		return fmt.Sprintf("asset_id=%s object_key=%s voice_seq=%d recv_seq=%d", asset.AssetID, asset.ObjectKey, httpSeq, recv.ServerSeq), pass
	}) {
		exitCode = 1
		return
	}

	if !step(rec, baseResult("D9.call_invite_accept_end_log", "message", "phone-sim", "emulator-sim"), func(res *result) (string, bool) {
		clientMsgID := "call-phone-emu-" + suffix
		res.ClientMsgID = clientMsgID
		status, body, err := httpJSON("POST", fmt.Sprintf("%s/v1/conversations/%d/call-invites", *gateway, convID), map[string]any{
			"client_msg_id": clientMsgID,
			"title":         "harness 视频通话",
		}, authHeader(phone.token))
		res.HTTPCode = status
		res.ExpectedHTTP = 200
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("call invite http=%d body=%v", status, body), false
		}
		data := dataMap(body)
		room := asMap(data["room"])
		message := asMap(data["message"])
		roomID := parseFlexInt(room["id"])
		inviteSeq := parseFlexInt(message["server_seq"])
		if roomID <= 0 || inviteSeq <= 0 || room["status"] != "active" {
			return fmt.Sprintf("call invite 缺 active room/message room=%v message=%v", room, message), false
		}
		inviteRecv, err := emulator.waitRecv(*timeout)
		if err != nil {
			return "等待 call_invite 实时消息失败：" + err.Error(), false
		}
		if inviteRecv.Kind != "call_invite" || inviteRecv.ClientMsgID != clientMsgID {
			return fmt.Sprintf("call_invite 实时消息不匹配 kind=%s client=%s", inviteRecv.Kind, inviteRecv.ClientMsgID), false
		}

		status, callerToken, err := httpJSON("POST", fmt.Sprintf("%s/v1/media/rooms/%d/token", *gateway, roomID), map[string]any{"role": "publisher"}, authHeader(phone.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK || stringValue(asMap(callerToken["data"])["token"]) == "" {
			return fmt.Sprintf("caller token http=%d body=%v", status, callerToken), false
		}
		status, calleeToken, err := httpJSON("POST", fmt.Sprintf("%s/v1/media/rooms/%d/token", *gateway, roomID), map[string]any{"role": "viewer"}, authHeader(emulator.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK || stringValue(asMap(calleeToken["data"])["token"]) == "" {
			return fmt.Sprintf("callee token http=%d body=%v", status, calleeToken), false
		}
		status, roomBody, err := httpJSON("GET", fmt.Sprintf("%s/v1/media/rooms/%d", *gateway, roomID), nil, authHeader(phone.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK || asMap(roomBody["data"])["call_accepted"] != true {
			return fmt.Sprintf("room accepted http=%d body=%v", status, roomBody), false
		}
		status, endBody, err := httpJSON("POST", fmt.Sprintf("%s/v1/media/rooms/%d/end", *gateway, roomID), map[string]any{}, authHeader(emulator.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK || asMap(endBody["data"])["status"] != "ended" {
			return fmt.Sprintf("end room http=%d body=%v", status, endBody), false
		}
		callLogRecv, err := emulator.waitRecv(*timeout)
		if err != nil {
			return "等待 video_call 通话记录失败：" + err.Error(), false
		}
		status, history, err := httpJSON("GET", fmt.Sprintf("%s/v1/conversations/%d/messages?since_seq=%d&limit=20", *gateway, convID, inviteSeq-1), nil, authHeader(phone.token))
		if err != nil {
			return err.Error(), false
		}
		if status != http.StatusOK {
			return fmt.Sprintf("call history http=%d body=%v", status, history), false
		}
		hasVideoCall := false
		for _, item := range dataItems(history) {
			m := asMap(item)
			if m["kind"] == "video_call" && asMap(m["payload"])["status"] == "completed" {
				hasVideoCall = true
				res.ServerSeq = parseFlexInt(m["server_seq"])
				break
			}
		}
		res.ConversationID = convID
		pass := callLogRecv.Kind == "video_call" &&
			callLogRecv.ConversationID == convID &&
			callLogRecv.ServerSeq == res.ServerSeq &&
			hasVideoCall
		return fmt.Sprintf(
			"room_id=%d invite_seq=%d video_seq=%d recv_kind=%s call_accepted=true",
			roomID,
			inviteSeq,
			res.ServerSeq,
			callLogRecv.Kind,
		), pass
	}) {
		exitCode = 1
		return
	}

	probeMediaControlPlane(rec, phone.token, convID)
}

func baseResult(scenario, area, actor, peer string) result {
	return result{
		Scenario: scenario,
		Area:     area,
		Actor:    actor,
		Peer:     peer,
		Severity: "fail",
	}
}

func step(rec *recorder, base result, fn func(*result) (string, bool)) bool {
	start := time.Now()
	res := base
	note, ok := fn(&res)
	res.OK = ok
	if ok {
		res.Severity = "pass"
	}
	res.LatencyMS = time.Since(start).Milliseconds()
	res.Note = note
	rec.write(res)
	return ok
}

func probeMediaControlPlane(rec *recorder, token string, conversationID int64) {
	step(rec, result{Scenario: "L1.media_room_create_capability", Area: "live", Actor: "phone-sim", Peer: "emulator-sim", Severity: "warn", ExpectedHTTP: "2xx"}, func(res *result) (string, bool) {
		body := map[string]any{
			"kind":            "live",
			"conversation_id": conversationID,
			"title":           "设备交互 harness 直播探测",
		}
		status, out, err := httpJSON("POST", *gateway+"/v1/media/rooms", body, authHeader(token))
		res.HTTPCode = status
		if err != nil {
			res.Severity = "fail"
			return err.Error(), false
		}
		switch {
		case status >= 200 && status < 300:
			res.Severity = "pass"
			return fmt.Sprintf("media room 控制面已响应 body=%v", out), true
		case status == http.StatusNotFound || status == http.StatusBadGateway:
			res.Severity = "warn"
			return fmt.Sprintf("blocked_livekit_control_plane_missing http=%d body=%v", status, out), false
		case status == http.StatusUnauthorized || status == http.StatusForbidden:
			res.Severity = "fail"
			return fmt.Sprintf("media room 鉴权异常 http=%d body=%v", status, out), false
		default:
			res.Severity = "fail"
			return fmt.Sprintf("media room 非预期响应 http=%d body=%v", status, out), false
		}
	})

	step(rec, result{Scenario: "L2.live_session_list_capability", Area: "live", Actor: "emulator-sim", Peer: "phone-sim", Severity: "warn", ExpectedHTTP: "2xx"}, func(res *result) (string, bool) {
		status, out, err := httpJSON("GET", *gateway+"/v1/live-sessions?status=live", nil, authHeader(token))
		res.HTTPCode = status
		if err != nil {
			res.Severity = "fail"
			return err.Error(), false
		}
		switch {
		case status >= 200 && status < 300:
			res.Severity = "pass"
			return fmt.Sprintf("live session 列表已响应 body=%v", out), true
		case status == http.StatusNotFound || status == http.StatusBadGateway:
			res.Severity = "warn"
			return fmt.Sprintf("blocked_live_session_api_missing http=%d body=%v", status, out), false
		default:
			res.Severity = "fail"
			return fmt.Sprintf("live session 非预期响应 http=%d body=%v", status, out), false
		}
	})
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
	env, err := c.waitType("msg.fetch_result", *timeout)
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
	if out == nil && len(bs) > 0 {
		out = map[string]any{"raw": strings.TrimSpace(string(bs))}
	}
	return resp.StatusCode, out, nil
}

func uploadMediaAsset(token, kind, mime string, data []byte) (uploadedAsset, error) {
	sum := sha256.Sum256(data)
	sha := hex.EncodeToString(sum[:])
	status, body, err := httpJSON("POST", *gateway+"/v1/assets/upload/init", map[string]any{
		"kind":       kind,
		"size_bytes": len(data),
		"sha256":     sha,
		"mime":       mime,
	}, authHeader(token))
	if err != nil {
		return uploadedAsset{}, err
	}
	if status != http.StatusOK {
		return uploadedAsset{}, fmt.Errorf("asset init http=%d body=%v", status, body)
	}
	init := dataMap(body)
	uploadID, _ := init["upload_id"].(string)
	if uploadID == "" {
		return uploadedAsset{}, fmt.Errorf("asset init 缺 upload_id body=%v", body)
	}
	chunkURL := fmt.Sprintf("%s/v1/assets/upload/%s/chunk/1", *gateway, uploadID)
	req, err := http.NewRequest(http.MethodPut, chunkURL, bytes.NewReader(data))
	if err != nil {
		return uploadedAsset{}, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", mime)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return uploadedAsset{}, err
	}
	chunkBody, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return uploadedAsset{}, fmt.Errorf("asset chunk http=%d body=%s", resp.StatusCode, strings.TrimSpace(string(chunkBody)))
	}
	status, body, err = httpJSON("POST", fmt.Sprintf("%s/v1/assets/upload/%s/complete", *gateway, uploadID), map[string]any{
		"total_chunks": 1,
	}, authHeader(token))
	if err != nil {
		return uploadedAsset{}, err
	}
	if status != http.StatusOK {
		return uploadedAsset{}, fmt.Errorf("asset complete http=%d body=%v", status, body)
	}
	complete := dataMap(body)
	asset := uploadedAsset{
		AssetID:     fmt.Sprint(complete["asset_id"]),
		ObjectKey:   fmt.Sprint(complete["object_key"]),
		DownloadURL: fmt.Sprint(complete["download_url"]),
		SHA256:      sha,
		SizeBytes:   len(data),
		MIME:        mime,
	}
	if asset.AssetID == "" || asset.AssetID == "<nil>" || asset.ObjectKey == "" || asset.ObjectKey == "<nil>" {
		return uploadedAsset{}, fmt.Errorf("asset complete 缺资产字段 body=%v", body)
	}
	return asset, nil
}

func registerAndLogin(suffix, role string) (userID int64, token string, err error) {
	username := fmt.Sprintf("di_%s_%s", role, suffix)
	emp := fmt.Sprintf("DI%s%s", role, suffix)
	status, body, err := httpJSON("POST", *gateway+"/v1/auth/register", map[string]any{
		"username":    username,
		"password":    "device-pass-1",
		"real_name":   "Device Harness " + role,
		"employee_id": emp,
	}, nil)
	if err != nil {
		return 0, "", err
	}
	if status != http.StatusOK {
		return 0, "", fmt.Errorf("register http=%d body=%v", status, body)
	}
	if data, ok := body["data"].(map[string]any); ok {
		userID = parseFlexInt(data["user_id"])
	}

	status, body, err = httpJSON("POST", *gateway+"/v1/auth/login", map[string]any{
		"username": username,
		"password": "device-pass-1",
	}, nil)
	if err != nil {
		return 0, "", err
	}
	if status != http.StatusOK {
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

func authHeader(token string) map[string]string {
	return map[string]string{"Authorization": "Bearer " + token}
}

func conversationListHas(body map[string]any, convID int64) bool {
	items := dataItems(body)
	for _, item := range items {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if parseFlexInt(m["id"]) == convID {
			return true
		}
	}
	return false
}

func messageSeqs(body map[string]any) []int64 {
	items := dataItems(body)
	seqs := make([]int64, 0, len(items))
	for _, item := range items {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		seqs = append(seqs, parseFlexInt(m["server_seq"]))
	}
	return seqs
}

func dataItems(body map[string]any) []any {
	items, _ := dataMap(body)["items"].([]any)
	return items
}

func dataMap(body map[string]any) map[string]any {
	data, _ := body["data"].(map[string]any)
	if data == nil {
		return map[string]any{}
	}
	return data
}

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}

func stringValue(v any) string {
	s, _ := v.(string)
	return s
}

func parseFlexInt(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case string:
		var n int64
		_, _ = fmt.Sscan(x, &n)
		return n
	case int64:
		return x
	case int:
		return int64(x)
	}
	return 0
}

func jsonRaw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
