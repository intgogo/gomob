package laser

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type framingControlReadErrorDevice struct{}

func (framingControlReadErrorDevice) GetStatus(context.Context) (*DeviceStatus, error) {
	return nil, errors.New("status unavailable")
}
func (framingControlReadErrorDevice) GetInfo(context.Context) (*DeviceInfo, error) {
	return nil, errors.New("config unavailable")
}
func (framingControlReadErrorDevice) ControlScan(context.Context, ScanCmd) error { return nil }
func (framingControlReadErrorDevice) UpdateControl(context.Context, ControlSettings) error {
	return nil
}
func (framingControlReadErrorDevice) UpdateCalib(context.Context, CalibParams) error { return nil }

func TestValidFramingSessionKey(t *testing.T) {
	for _, value := range []string{"site-framing", "site-framing-lz4-12", "framing_20260714.1"} {
		if !validFramingSessionKey(value) {
			t.Fatalf("合法 session_key 被拒绝: %q", value)
		}
	}
	for _, value := range []string{"", "site framing", "取景标定", strings.Repeat("a", 97)} {
		if validFramingSessionKey(value) {
			t.Fatalf("非法 session_key 被接受: %q", value)
		}
	}
}

func TestSiteFramingRequiresUniqueSessionKey(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, nil)
	req := httptest.NewRequest(http.MethodPost, "/v1/scans/laser/site-framing", nil)
	req.Header.Set("X-Gomob-User-Id", "7")
	req.Header.Set("X-Gomob-Roles", "admin")
	rec := httptest.NewRecorder()
	h.SiteFraming(rec, req)
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "session_key 必填") {
		t.Fatalf("缺 session_key 应拒绝，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestParseFramingSweepRejectsUnsafeAngles(t *testing.T) {
	valid := map[string][]string{"a_start": {"-170"}, "a_stop": {"-10"}}
	start, stop, set, err := parseFramingSweep(valid, "a_start", "a_stop", "A")
	if err != nil || !set || start != -170 || stop != -10 {
		t.Fatalf("合法扫程解析失败: start=%v stop=%v set=%v err=%v", start, stop, set, err)
	}
	for name, query := range map[string]map[string][]string{
		"缺止角": {"a_start": {"0"}},
		"零扫程": {"a_start": {"0"}, "a_stop": {"0"}},
		"反向":  {"a_start": {"90"}, "a_stop": {"0"}},
		"过小":  {"a_start": {"0"}, "a_stop": {"5"}},
		"越界":  {"a_start": {"0"}, "a_stop": {"180"}},
	} {
		t.Run(name, func(t *testing.T) {
			if _, _, _, err := parseFramingSweep(query, "a_start", "a_stop", "A"); err == nil {
				t.Fatal("不安全扫程应拒绝")
			}
		})
	}
}

func TestApplyFramingControlFailsClosedWhenConfigUnknown(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, nil)
	h.newDev = func(string) DeviceAPI { return framingControlReadErrorDevice{} }
	if err := h.applyFramingControl(context.Background(), "192.168.9.101", 0, 90, "1", "A"); err == nil {
		t.Fatal("读取不到真实控制设置时不得继续起扫")
	}

	device := &fakeDeviceAPI{info: &DeviceInfo{Control: ControlSettings{
		ScanStartAngle: 0,
		ScanStopAngle:  90,
		ScanSpeed:      1,
	}}}
	h.newDev = func(string) DeviceAPI { return device }
	if err := h.applyFramingControl(context.Background(), "192.168.9.101", 0, 90, "1", "A"); err != nil {
		t.Fatalf("真实设置已匹配时应幂等通过: %v", err)
	}
	if device.controlWrites != 0 {
		t.Fatalf("幂等匹配不应重发 update_control，writes=%d", device.controlWrites)
	}
}

func TestStopSiteFramingCancelsRegisteredSession(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, nil)
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady}}
	h.newDev = func(string) DeviceAPI { return &fakeDeviceAPI{} }
	if !h.sessions.tryReserve(reservationFraming) {
		t.Fatal("预留取景会话失败")
	}
	ctx, cancel := context.WithCancel(context.Background())
	framing, ok := h.sessions.registerFraming("site-framing-cancel", 7, "192.168.9.101", "192.168.9.102", cancel)
	if !ok {
		t.Fatal("注册取景会话失败")
	}
	go func() {
		<-ctx.Done()
		h.sessions.markFramingPipelineDone(framing.key)
	}()

	req := httptest.NewRequest(http.MethodDelete, "/v1/scans/laser/site-framing?session_key=site-framing-cancel", nil)
	req.Header.Set("X-Gomob-User-Id", "7")
	req.Header.Set("X-Gomob-Roles", "admin")
	rec := httptest.NewRecorder()
	h.StopSiteFraming(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("停止取景返回 %d: %s", rec.Code, rec.Body.String())
	}
	select {
	case <-ctx.Done():
	default:
		t.Fatal("停止端点未取消取景 context")
	}
}

func TestFramingCleanupSurvivesStopRequestDisconnect(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, nil)
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady}}
	h.newDev = func(string) DeviceAPI { return &fakeDeviceAPI{} }
	if !h.sessions.tryReserve(reservationFraming) {
		t.Fatal("预留取景会话失败")
	}
	ctx, cancel := context.WithCancel(context.Background())
	framing, ok := h.sessions.registerFraming("disconnect-cleanup", 7, "a", "b", cancel)
	if !ok {
		t.Fatal("注册取景会话失败")
	}
	decision := h.sessions.requestFramingCancel(framing.key)
	if !decision.cancelWon {
		t.Fatal("停止应取得取消权")
	}
	cleanup, started := h.sessions.startFramingCleanup(framing.key)
	if !started {
		t.Fatal("后台清理未启动")
	}
	go h.recoverFramingSessionUntilReady(cleanup)
	// 模拟 DELETE 客户端已经断开；清理只依赖会话，不依赖该请求 context。
	h.sessions.markFramingPipelineDone(framing.key)
	select {
	case <-framing.done:
	case <-time.After(2 * time.Second):
		t.Fatal("客户端断开后后台清理未完成")
	}
	select {
	case <-ctx.Done():
	default:
		t.Fatal("取消 context 未传播")
	}
}

func TestReadyCheckedGateRechecksImmediatelyBeforeStart(t *testing.T) {
	base := &fakeGate{}
	gate := &readyCheckedGate{
		base: base,
		probe: fakeProber{res: ProbeResult{
			Reachable: true,
			Online:    true,
			State:     StateScan,
		}},
		ipA: "a", ipB: "b", operation: "测试标定",
	}
	if err := gate.Start(context.Background()); err == nil {
		t.Fatal("非 READY 不得下发底层 START")
	}
	if base.started {
		t.Fatal("READY 复核失败后底层 gate 不应启动")
	}
	gate.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady}}
	if err := gate.Start(context.Background()); err != nil || !base.started {
		t.Fatalf("双机 READY 应允许启动: started=%v err=%v", base.started, err)
	}
}

func TestFramingCommitFenceHasSingleWinner(t *testing.T) {
	t.Run("停止先赢", func(t *testing.T) {
		sessions := &sessionRegistry{active: map[int64]*activeSession{}}
		if !sessions.tryReserve(reservationFraming) {
			t.Fatal("预留失败")
		}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		framing, ok := sessions.registerFraming("cancel-wins", 7, "a", "b", cancel)
		if !ok {
			t.Fatal("注册失败")
		}
		decision := sessions.requestFramingCancel(framing.key)
		if !decision.cancelWon || !decision.matched {
			t.Fatalf("停止应赢得 fence: %+v", decision)
		}
		if sessions.beginFramingCommit(framing.key) {
			t.Fatal("停止先赢后不得进入提交")
		}
		select {
		case <-ctx.Done():
		default:
			t.Fatal("停止先赢后 context 应取消")
		}
		sessions.finishFraming(framing.key)
	})

	t.Run("提交先赢", func(t *testing.T) {
		sessions := &sessionRegistry{active: map[int64]*activeSession{}}
		if !sessions.tryReserve(reservationFraming) {
			t.Fatal("预留失败")
		}
		_, cancel := context.WithCancel(context.Background())
		defer cancel()
		framing, ok := sessions.registerFraming("commit-wins", 7, "a", "b", cancel)
		if !ok || !sessions.beginFramingCommit("commit-wins") {
			t.Fatal("提交应赢得 fence")
		}
		decision := sessions.requestFramingCancel("commit-wins")
		if decision.cancelWon || !decision.matched {
			t.Fatalf("提交开始后停止不得宣称取消成功: %+v", decision)
		}
		sessions.markFramingCommitted("commit-wins")
		sessions.finishFraming("commit-wins")
		if !framing.committed {
			t.Fatal("提交结果未记录")
		}
	})
}

func TestFramingSessionKeyCannotBeReused(t *testing.T) {
	sessions := &sessionRegistry{active: map[int64]*activeSession{}}
	if !sessions.tryReserve(reservationFraming) {
		t.Fatal("首次预留失败")
	}
	_, cancel := context.WithCancel(context.Background())
	framing, ok := sessions.registerFraming("never-reuse", 7, "a", "b", cancel)
	if !ok {
		t.Fatal("首次注册失败")
	}
	sessions.finishFraming(framing.key)
	cancel()
	if !sessions.tryReserve(reservationFraming) {
		t.Fatal("第二次预留失败")
	}
	_, cancelAgain := context.WithCancel(context.Background())
	defer cancelAgain()
	if _, ok := sessions.registerFraming("never-reuse", 7, "a", "b", cancelAgain); ok {
		t.Fatal("已使用的 session_key 不得再次注册")
	}
	sessions.release()
}

func TestStopSiteFramingReportsCommittedResult(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, nil)
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady}}
	h.newDev = func(string) DeviceAPI { return &fakeDeviceAPI{} }
	if !h.sessions.tryReserve(reservationFraming) {
		t.Fatal("预留取景会话失败")
	}
	_, cancel := context.WithCancel(context.Background())
	defer cancel()
	if _, ok := h.sessions.registerFraming("site-framing-committed", 7, "192.168.9.101", "192.168.9.102", cancel); !ok {
		t.Fatal("注册取景会话失败")
	}
	if !h.sessions.beginFramingCommit("site-framing-committed") {
		t.Fatal("进入提交阶段失败")
	}
	h.sessions.markFramingCommitted("site-framing-committed")
	h.sessions.finishFraming("site-framing-committed")

	req := httptest.NewRequest(http.MethodDelete, "/v1/scans/laser/site-framing?session_key=site-framing-committed", nil)
	req.Header.Set("X-Gomob-User-Id", "7")
	req.Header.Set("X-Gomob-Roles", "admin")
	rec := httptest.NewRecorder()
	h.StopSiteFraming(rec, req)
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), `"server_persisted":true`) {
		t.Fatalf("已提交会话应明确返回 409 + server_persisted=true，得 %d: %s", rec.Code, rec.Body.String())
	}
}

// putU32BE/record/frameRecord 复刻 framing_stream.cpp 的二进制帧协议，合成喂给解析器。
func putU32BE(buf *bytes.Buffer, v uint32) {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	buf.Write(b[:])
}

// record = [4B N][1B type][N payload]
func record(buf *bytes.Buffer, typ byte, payload []byte) {
	putU32BE(buf, uint32(len(payload)))
	buf.WriteByte(typ)
	buf.Write(payload)
}

// 'm' payload = [4B metaLen][meta json][jpeg]
func frameRecord(buf *bytes.Buffer, meta string, jpeg []byte) {
	var p bytes.Buffer
	putU32BE(&p, uint32(len(meta)))
	p.WriteString(meta)
	p.Write(jpeg)
	record(buf, 'm', p.Bytes())
}

func TestReadFramingRecordsParsesSequence(t *testing.T) {
	var wire bytes.Buffer
	record(&wire, 's', []byte(`{"ev":"ready"}`))
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02, 0x03} // 假 JPEG SOI + 数据
	meta := `{"unit":1,"seq":7,"heading":33.5,"w":1280,"h":720,"markers":[{"id":5,"px":[[10,20],[30,20],[30,40],[10,40]]}]}`
	frameRecord(&wire, meta, jpeg)
	record(&wire, 'r', []byte(`{"ok":true,"n_common":6,"rms_m":0.004}`))

	var types []byte
	var framePayload []byte
	err := readFramingRecords(&wire, func(typ byte, payload []byte) {
		types = append(types, typ)
		if typ == 'm' {
			framePayload = append([]byte(nil), payload...)
		}
	})
	if err != nil {
		t.Fatalf("readFramingRecords 出错: %v", err)
	}
	if string(types) != "smr" {
		t.Fatalf("记录类型序列应为 s,m,r，得 %q", string(types))
	}

	msg, ok := decodeFrameRecord(framePayload, 42, "site-framing")
	if !ok {
		t.Fatal("decodeFrameRecord 失败")
	}
	if msg.Unit != 1 || msg.Seq != 7 || msg.W != 1280 || msg.H != 720 {
		t.Fatalf("帧元数据解析错: %+v", msg)
	}
	if msg.HeadingDeg < 33.4 || msg.HeadingDeg > 33.6 {
		t.Fatalf("heading 解析错: %v", msg.HeadingDeg)
	}
	if msg.OwnerUserID == nil || *msg.OwnerUserID != 42 {
		t.Fatalf("owner 路由字段错: %+v", msg.OwnerUserID)
	}
	if len(msg.Markers) != 1 || msg.Markers[0].ID != 5 || len(msg.Markers[0].PX) != 4 {
		t.Fatalf("标记检测解析错: %+v", msg.Markers)
	}
	if msg.Markers[0].PX[1][0] != 30 || msg.Markers[0].PX[2][1] != 40 {
		t.Fatalf("标记角点像素解析错: %+v", msg.Markers[0].PX)
	}
	// jpeg 应原样 base64 往返。
	gotJPEG, err := base64.StdEncoding.DecodeString(msg.JPEGB64)
	if err != nil || !bytes.Equal(gotJPEG, jpeg) {
		t.Fatalf("jpeg base64 往返错: err=%v got=%v", err, gotJPEG)
	}

	// 载荷可 JSON 序列化（NATS 发布路径）。
	if _, err := json.Marshal(msg); err != nil {
		t.Fatalf("LaserFrameMsg 不可序列化: %v", err)
	}
}

func TestDecodeFrameRecordRejectsTruncated(t *testing.T) {
	if _, ok := decodeFrameRecord([]byte{0x00, 0x01}, 1, "s"); ok {
		t.Fatal("过短 payload 应拒绝")
	}
	// metaLen 越界。
	var p bytes.Buffer
	putU32BE(&p, 9999)
	p.WriteString("short")
	if _, ok := decodeFrameRecord(p.Bytes(), 1, "s"); ok {
		t.Fatal("metaLen 越界应拒绝")
	}
}
