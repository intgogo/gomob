package laser

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"

	"io.gomob/server/pkg/repo"
)

// --- fakes（无 cgo / 无 MinIO / 无 NATS / 无 DB）---

type fakeJobStore struct {
	mu             sync.Mutex
	fusingCalls    int
	completeCalls  int
	failCalls      int
	lastCompletion repo.LaserScanCompletion
	lastFailMsg    string
}

func (f *fakeJobStore) MarkFusing(_ context.Context, id int64, a, b int) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fusingCalls++
	return &repo.LaserScanJob{ID: id, Status: repo.LaserScanStatusFusing}, nil
}
func (f *fakeJobStore) Complete(_ context.Context, id int64, c repo.LaserScanCompletion) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.completeCalls++
	f.lastCompletion = c
	return &repo.LaserScanJob{ID: id, Status: repo.LaserScanStatusDone}, nil
}
func (f *fakeJobStore) Fail(_ context.Context, id int64, msg string) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.failCalls++
	f.lastFailMsg = msg
	return &repo.LaserScanJob{ID: id, Status: repo.LaserScanStatusFailed}, nil
}

type fakeCloudStore struct {
	mu     sync.Mutex
	counts map[string]int // name → 点数
}

func (f *fakeCloudStore) PutCloud(_ context.Context, sessionKey, name string, xyz []float32) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.counts == nil {
		f.counts = map[string]int{}
	}
	f.counts[name] = len(xyz) / 3
	return LaserObjectKey(sessionKey, name), nil
}

func (f *fakeCloudStore) PutCloudXYZI(ctx context.Context, sessionKey, name string, xyz, attr []float32) (string, error) {
	if len(attr) != len(xyz)/3 {
		return "", errors.New("attr 长度与点数不符")
	}
	return f.PutCloud(ctx, sessionKey, name, xyz)
}

type fakePublisher struct {
	mu     sync.Mutex
	topic  string
	events []FusionDoneEvent
}

func (f *fakePublisher) Publish(_ context.Context, topic string, payload any) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.topic = topic
	if e, ok := payload.(FusionDoneEvent); ok {
		f.events = append(f.events, e)
	}
	return nil
}

type recordSink struct {
	mu       sync.Mutex
	frames   int
	statuses []string
}

func (s *recordSink) Points(PointFrame) {
	s.mu.Lock()
	s.frames++
	s.mu.Unlock()
}
func (s *recordSink) Status(state string, _, _ int) {
	s.mu.Lock()
	s.statuses = append(s.statuses, state)
	s.mu.Unlock()
}

// fakeScan 模拟 cgo 流式回调：unit0 两帧、unit1 两帧、status scanning→fusing→done、unit2 融合整云。
// 每单元帧带递增 h_angle(0→90°)模拟真实扫掠，过空扫守卫。
func fakeScan(_, _, align, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
	emit := func(unit, n int, h float32) {
		if cb.OnPoints == nil {
			return
		}
		cb.OnPoints(PointFrame{Unit: unit, XYZmm: make([]float32, n*3), HAngleDeg: h})
	}
	status := func(s string) {
		if cb.OnStatus != nil {
			cb.OnStatus(s, 0, 0)
		}
	}
	status("scanning")
	emit(0, 100, 0)
	emit(0, 50, 90) // unitA 共 150，扫掠 0→90°
	emit(1, 200, 0)
	emit(1, 40, 90) // unitB 共 240，扫掠 0→90°
	status("fusing")
	emit(2, 390, 0) // 融合帧 h=0
	status("done")
	return ScanResult{PtsA: 150, PtsB: 240, Fused: 390, AfterCrop: 390, Align: "icp"}, nil
}

// TestRunnerNoSweepGuard：控制板没真转台 → 帧 h_angle 恒定 → 空扫守卫判失败（不静默产出扁平云）。
func TestRunnerNoSweepGuard(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	sink := &recordSink{}
	r := newTestRunner(jobs, clouds, nil)
	// 模拟掉线控制板：state 报 SCAN/有点，但 h_angle 恒为 0（没扫掠）。
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		for _, u := range []int{0, 1} {
			cb.OnPoints(PointFrame{Unit: u, XYZmm: make([]float32, 300), HAngleDeg: 0})
		}
		return ScanResult{PtsA: 100, PtsB: 100, Fused: 200, AfterCrop: 200, Align: "none"}, nil
	}
	_, err := r.Run(context.Background(), RunSpec{JobID: 9, SessionKey: "s", Replay: true}, sink)
	if err == nil {
		t.Fatal("空扫应失败，得 nil")
	}
	if !strings.Contains(err.Error(), "未真正扫掠") {
		t.Errorf("错误应含「未真正扫掠」，得: %v", err)
	}
	if jobs.failCalls != 1 || jobs.completeCalls != 0 {
		t.Errorf("应 Fail 1/Complete 0，得 Fail=%d Complete=%d", jobs.failCalls, jobs.completeCalls)
	}
	if clouds.counts["fused"] != 0 {
		t.Errorf("空扫不应落云，得 %+v", clouds.counts)
	}
	// 端侧应收到 error 状态。
	got := false
	for _, s := range sink.statuses {
		if s == "error" {
			got = true
		}
	}
	if !got {
		t.Errorf("sink 应收 error 状态，得 %v", sink.statuses)
	}
}

func newTestRunner(jobs JobStore, clouds CloudStore, pub Publisher) *Runner {
	r := NewRunner(jobs, clouds, pub, nil)
	r.Replay = fakeScan
	r.Live = fakeScan
	return r
}

func TestRunnerHappyPath(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	pub := &fakePublisher{}
	sink := &recordSink{}
	r := newTestRunner(jobs, clouds, pub)

	owner := int64(7)
	job, err := r.Run(context.Background(), RunSpec{
		JobID:       42,
		SessionKey:  "sess-1",
		OwnerUserID: &owner,
		Align:       "icp",
		KeepRatio:   1.0,
		Replay:      true,
	}, sink)
	if err != nil {
		t.Fatalf("Run 失败: %v", err)
	}
	if job == nil || job.Status != repo.LaserScanStatusDone {
		t.Fatalf("job 应 done，得 %+v", job)
	}

	// 三朵云都上传且点数对。
	if clouds.counts["fused"] != 390 || clouds.counts["unit_a"] != 150 || clouds.counts["unit_b"] != 240 {
		t.Errorf("云点数错: %+v", clouds.counts)
	}
	// MarkFusing 恰一次、Complete 一次、无 Fail。
	if jobs.fusingCalls != 1 {
		t.Errorf("MarkFusing 应 1 次，得 %d", jobs.fusingCalls)
	}
	if jobs.completeCalls != 1 || jobs.failCalls != 0 {
		t.Errorf("Complete=%d Fail=%d，期望 1/0", jobs.completeCalls, jobs.failCalls)
	}
	// Completion 字段。
	c := jobs.lastCompletion
	if c.AlignMethod != "icp" || c.Fused != 390 || c.PtsA != 150 || c.PtsB != 240 {
		t.Errorf("completion 错: %+v", c)
	}
	if c.FusedObjectKey != "laser-scans/sess-1/fused.pcd" {
		t.Errorf("fused key 错: %q", c.FusedObjectKey)
	}
	// NATS 事件。
	if pub.topic != TopicFusionDone || len(pub.events) != 1 {
		t.Fatalf("应发 1 条 %s，得 topic=%q n=%d", TopicFusionDone, pub.topic, len(pub.events))
	}
	e := pub.events[0]
	if e.Kind != "laser" || e.Points != 390 || e.ResultObjectKey != "laser-scans/sess-1/fused.pcd" {
		t.Errorf("事件错: %+v", e)
	}
	if e.OwnerUserID == nil || *e.OwnerUserID != 7 {
		t.Errorf("事件 owner 错: %v", e.OwnerUserID)
	}
	if e.UnitAObjectKey == "" || e.UnitBObjectKey == "" {
		t.Error("事件应含 unitA/unitB key")
	}
	// Sink 收齐 5 帧点 + 状态序列。
	if sink.frames != 5 {
		t.Errorf("sink 帧数 = %d，期望 5", sink.frames)
	}
	if len(sink.statuses) != 3 || sink.statuses[0] != "scanning" || sink.statuses[2] != "done" {
		t.Errorf("状态序列错: %v", sink.statuses)
	}
}

func TestRunnerCancelled(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		return ScanResult{Error: "cancelled"}, &ScanError{Code: 2, Msg: "cancelled"}
	}
	_, err := r.Run(context.Background(), RunSpec{JobID: 1, SessionKey: "s", Replay: true}, nil)
	var se *ScanError
	if !errors.As(err, &se) || !se.Cancelled() {
		t.Fatalf("应回取消错，得 %v", err)
	}
	// 取消不写 Complete/Fail（取消态由 stop handler 负责）。
	if jobs.completeCalls != 0 || jobs.failCalls != 0 {
		t.Errorf("取消不应 Complete/Fail，得 C=%d F=%d", jobs.completeCalls, jobs.failCalls)
	}
}

func TestRunnerScanError(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, _ ScanCallbacks) (ScanResult, error) {
		return ScanResult{}, errors.New("connect failed")
	}
	_, err := r.Run(context.Background(), RunSpec{JobID: 9, SessionKey: "s", Replay: true}, nil)
	if err == nil {
		t.Fatal("应回错误")
	}
	if jobs.failCalls != 1 || jobs.completeCalls != 0 {
		t.Errorf("应 Fail 一次，得 F=%d C=%d", jobs.failCalls, jobs.completeCalls)
	}
	if jobs.lastFailMsg != "connect failed" {
		t.Errorf("失败原因错: %q", jobs.lastFailMsg)
	}
}
