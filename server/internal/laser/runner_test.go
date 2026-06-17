package laser

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
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
	xyz    map[string][]float32
	rgb    map[string][]uint32
}

func (f *fakeCloudStore) PutCloud(_ context.Context, sessionKey, name string, xyz []float32) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.putCloudLocked(name, xyz)
	return LaserObjectKey(sessionKey, name), nil
}

func (f *fakeCloudStore) putCloudLocked(name string, xyz []float32) {
	if f.counts == nil {
		f.counts = map[string]int{}
	}
	if f.xyz == nil {
		f.xyz = map[string][]float32{}
	}
	f.counts[name] = len(xyz) / 3
	f.xyz[name] = append([]float32(nil), xyz...)
}

func (f *fakeCloudStore) PutCloudXYZI(ctx context.Context, sessionKey, name string, xyz, attr []float32) (string, error) {
	if len(attr) != len(xyz)/3 {
		return "", errors.New("attr 长度与点数不符")
	}
	return f.PutCloud(ctx, sessionKey, name, xyz)
}

func (f *fakeCloudStore) PutCloudXYZRGB(ctx context.Context, sessionKey, name string, xyz []float32, rgb []uint32) (string, error) {
	if len(rgb) != len(xyz)/3 {
		return "", errors.New("rgb 长度与点数不符")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.putCloudLocked(name, xyz)
	if f.rgb == nil {
		f.rgb = map[string][]uint32{}
	}
	f.rgb[name] = append([]uint32(nil), rgb...)
	return LaserObjectKey(sessionKey, name), nil
}

func (f *fakeCloudStore) PutCloudXYZRGBI(ctx context.Context, sessionKey, name string, xyz []float32, rgb []uint32, attr []float32) (string, error) {
	if len(rgb) != len(xyz)/3 {
		return "", errors.New("rgb 长度与点数不符")
	}
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

func TestSweepSpanUnwrapsAngleBoundary(t *testing.T) {
	var s sweepSpan
	for _, h := range []float32{179.0, -179.0, -120.0, -20.0} {
		s.add(h)
	}
	if got := s.span(); got < 160.5 || got > 161.5 {
		t.Fatalf("跨 -180/+180 边界应按连续扫掠统计，span=%.1f", got)
	}
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
	mu           sync.Mutex
	frames       int
	pointsByUnit map[int]int
	statuses     []string
}

func (s *recordSink) Points(f PointFrame) {
	s.mu.Lock()
	if s.pointsByUnit == nil {
		s.pointsByUnit = map[int]int{}
	}
	s.frames++
	s.pointsByUnit[f.Unit] += f.Points()
	s.mu.Unlock()
}
func (s *recordSink) Status(state string, _, _ int) {
	s.mu.Lock()
	s.statuses = append(s.statuses, state)
	s.mu.Unlock()
}
func (s *recordSink) Image(ImageFrame) {}

// fakeScan 模拟 cgo 流式回调：unit0 两帧、unit1 两帧；site/icp 产 unit2，raw 只采 A/B。
// 每单元帧带递增 h_angle(0→90°)模拟真实扫掠，过空扫守卫。
func fakeScan(_, _, align, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
	usedAlign := align
	if usedAlign == "" {
		usedAlign = "icp"
	}
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
	if usedAlign == "raw" {
		status("done")
		return ScanResult{PtsA: 150, PtsB: 240, Fused: 0, AfterCrop: 0, Align: "raw"}, nil
	}
	status("fusing")
	emit(2, 390, 0) // 融合帧 h=0
	status("done")
	return ScanResult{PtsA: 150, PtsB: 240, Fused: 390, AfterCrop: 390, Align: usedAlign}, nil
}

// TestRunnerNoSweepGuardIsDiagnosticOnly：角度源在真设备上不可靠，不能阻断原始 PCD 写入。
func TestRunnerNoSweepGuardIsDiagnosticOnly(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	sink := &recordSink{}
	r := newTestRunner(jobs, clouds, nil)
	// 模拟 h_angle 恒为 0：仍应保留点云，让用户直接看真实采集结果。
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		for _, u := range []int{0, 1} {
			cb.OnPoints(PointFrame{Unit: u, XYZmm: make([]float32, 300), HAngleDeg: 0})
		}
		cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 600), HAngleDeg: 0})
		return ScanResult{PtsA: 100, PtsB: 100, Fused: 200, AfterCrop: 200, Align: "none"}, nil
	}
	_, err := r.Run(context.Background(), RunSpec{JobID: 9, SessionKey: "s", Replay: true}, sink)
	if err != nil {
		t.Fatalf("角度诊断不足不应阻断点云，得: %v", err)
	}
	if jobs.failCalls != 0 || jobs.completeCalls != 1 {
		t.Errorf("应 Fail 0/Complete 1，得 Fail=%d Complete=%d", jobs.failCalls, jobs.completeCalls)
	}
	if clouds.counts["fused"] != 200 {
		t.Errorf("应落 fused 点云，得 %+v", clouds.counts)
	}
}

func TestRunnerExpectedSweepCoverageGuard(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 100*3), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 100*3), HAngleDeg: 61.8})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 100*3), HAngleDeg: -170})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 100*3), HAngleDeg: -105.8})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 400*3), HAngleDeg: 0})
		}
		return ScanResult{PtsA: 200, PtsB: 200, Fused: 400, AfterCrop: 400, Align: "none"}, nil
	}

	_, err := r.Run(context.Background(), RunSpec{
		JobID:             10,
		SessionKey:        "s",
		Replay:            true,
		ExpectedSweepADeg: 170,
		ExpectedSweepBDeg: 160,
	}, &recordSink{})
	if err != nil {
		t.Fatalf("有效点扫掠角诊断不足不应失败，得: %v", err)
	}
	if jobs.failCalls != 0 || jobs.completeCalls != 1 {
		t.Errorf("应 Fail 0/Complete 1，得 Fail=%d Complete=%d", jobs.failCalls, jobs.completeCalls)
	}
	if clouds.counts["fused"] != 400 {
		t.Errorf("应落 PCD，得 %+v", clouds.counts)
	}
}

func newTestRunner(jobs JobStore, clouds CloudStore, pub Publisher) *Runner {
	r := NewRunner(jobs, clouds, pub, nil)
	r.Replay = fakeScan
	r.Live = fakeScan
	return r
}

func TestRunnerSiteJSONMaterializedForNative(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	siteJSON := `{"b_to_a":[1,0,0,0.1,0,1,0,0.2,0,0,1,0.3,0,0,0,1]}`
	var gotPath, gotBody string
	r.Replay = func(a, b, align, site string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		gotPath = site
		body, err := os.ReadFile(site)
		if err != nil {
			t.Fatalf("site 外参应在调用 native 前落成文件: %v", err)
		}
		gotBody = strings.TrimSpace(string(body))
		return fakeScan(a, b, align, site, keep, cb)
	}
	if _, err := r.Run(context.Background(), RunSpec{
		JobID:      45,
		SessionKey: "sess-site",
		Align:      "site",
		SiteJSON:   siteJSON,
		Replay:     true,
	}, &recordSink{}); err != nil {
		t.Fatalf("Run 失败: %v", err)
	}
	if gotPath == "" || gotPath == siteJSON || !filepath.IsAbs(gotPath) || gotBody != siteJSON {
		t.Fatalf("site 外参未正确落盘: path=%q body=%q", gotPath, gotBody)
	}
	if _, err := os.Stat(gotPath); !os.IsNotExist(err) {
		t.Fatalf("site 临时文件应在扫描后清理，stat err=%v", err)
	}
}

func TestRunnerSiteAlignFallbackFails(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	siteJSON := `{"b_to_a":[1,0,0,0.1,0,1,0,0.2,0,0,1,0.3,0,0,0,1]}`
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 300), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 300), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 600), HAngleDeg: 0})
		}
		return ScanResult{PtsA: 100, PtsB: 100, Fused: 200, AfterCrop: 200, Align: "none"}, nil
	}
	_, err := r.Run(context.Background(), RunSpec{
		JobID:      46,
		SessionKey: "sess-site-fallback",
		Align:      "site",
		SiteJSON:   siteJSON,
		Replay:     true,
	}, &recordSink{})
	if err == nil || !strings.Contains(err.Error(), "外参对齐未生效") {
		t.Fatalf("site 请求 native 未返回 site 应失败，得 %v", err)
	}
	if jobs.failCalls != 1 || jobs.completeCalls != 0 {
		t.Fatalf("应 Fail 1/Complete 0，得 Fail=%d Complete=%d", jobs.failCalls, jobs.completeCalls)
	}
	if len(clouds.counts) != 0 {
		t.Fatalf("外参未生效时不应写 PCD，得 %+v", clouds.counts)
	}
}

func TestRunnerRawOnlySkipsFusedCloud(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	_, err := r.Run(context.Background(), RunSpec{
		JobID:      47,
		SessionKey: "sess-raw",
		Align:      "raw",
		Replay:     true,
	}, &recordSink{})
	if err != nil {
		t.Fatalf("raw Run 失败: %v", err)
	}
	if _, ok := clouds.counts["fused"]; ok {
		t.Fatalf("raw 模式不应写 fused PCD，得 %+v", clouds.counts)
	}
	if clouds.counts["unit_a"] != 150 || clouds.counts["unit_b"] != 240 {
		t.Fatalf("raw 模式应写 A/B 点云，得 %+v", clouds.counts)
	}
	if jobs.completeCalls != 1 || jobs.failCalls != 0 {
		t.Fatalf("raw 模式应完成，Complete=%d Fail=%d", jobs.completeCalls, jobs.failCalls)
	}
	if jobs.lastCompletion.AlignMethod != "raw" || jobs.lastCompletion.Fused != 0 || jobs.lastCompletion.FusedObjectKey != "" {
		t.Fatalf("raw completion 错: %+v", jobs.lastCompletion)
	}
}

func TestFlipVerticalBToAConjugatesZ(t *testing.T) {
	got := flipVerticalBToA([16]float32{
		1, 0, 2, 100,
		0, 1, 3, 200,
		4, 5, 6, 300,
		0, 0, 0, 1,
	})
	want := [16]float32{
		1, 0, -2, 100,
		0, 1, -3, 200,
		-4, -5, 6, -300,
		0, 0, 0, 1,
	}
	if got != want {
		t.Fatalf("Z 翻转坐标下 BToA 共轭错误:\n got=%v\nwant=%v", got, want)
	}
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
	// Sink 只承载采集中 A/B 实时预览；完整 fused 以后走 PCD/done 事件。
	// 预览会按时间/点数批量发送，但不能丢掉 A/B 已采集点。
	if sink.pointsByUnit[0] != 150 || sink.pointsByUnit[1] != 240 {
		t.Errorf("sink 预览点数错: %+v", sink.pointsByUnit)
	}
	if sink.frames != 2 {
		t.Errorf("sink 预览批次数 = %d，期望 2", sink.frames)
	}
	if len(sink.statuses) != 3 || sink.statuses[0] != "scanning" || sink.statuses[2] != "done" {
		t.Errorf("状态序列错: %v", sink.statuses)
	}
}

func TestRunnerRegionFilterWritesFilteredClouds(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	sink := &recordSink{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: []float32{
				5, 5, 0,
				25, 5, 0,
			}, HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: []float32{
				-5, 5, 0,
				25, 5, 0,
			}, HAngleDeg: 90})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: []float32{
				5, 5, 0,
				25, 5, 0,
				10, 10, 0,
				-5, 5, 0,
			}, HAngleDeg: 0})
		}
		if cb.OnStatus != nil {
			cb.OnStatus("fusing", 0, 0)
			cb.OnStatus("done", 0, 0)
		}
		return ScanResult{PtsA: 2, PtsB: 2, Fused: 4, AfterCrop: 4, Align: "none"}, nil
	}

	_, err := r.Run(context.Background(), RunSpec{
		JobID:      43,
		SessionKey: "sess-filter",
		Replay:     true,
		RegionFilter: PointRegionFilter{
			Enabled: true,
			Points: [][3]float32{
				{0, 0, 0},
				{20, 0, 0},
				{20, 20, 0},
				{0, 20, 0},
			},
			BToA: []float32{
				1, 0, 0, 10,
				0, 1, 0, 0,
				0, 0, 1, 0,
				0, 0, 0, 1,
			},
		},
	}, sink)
	if err != nil {
		t.Fatalf("Run 失败: %v", err)
	}
	if clouds.counts["unit_a"] != 1 || clouds.counts["unit_b"] != 1 || clouds.counts["fused"] != 2 {
		t.Fatalf("应落过滤后点云，得 %+v", clouds.counts)
	}
	if sink.pointsByUnit[0] != 1 || sink.pointsByUnit[1] != 1 {
		t.Fatalf("实时预览应只推过滤后点，得 %+v", sink.pointsByUnit)
	}
	c := jobs.lastCompletion
	if c.PtsA != 1 || c.PtsB != 1 || c.Fused != 2 {
		t.Fatalf("completion 应记录过滤后点数，得 %+v", c)
	}
	var stats map[string]any
	if err := json.Unmarshal(c.Stats, &stats); err != nil {
		t.Fatal(err)
	}
	if stats["raw_pts_a"] != float64(2) || stats["raw_pts_b"] != float64(2) || stats["raw_fused"] != float64(4) {
		t.Fatalf("stats 应保留原始点数，得 %+v", stats)
	}
}

func TestRunnerRegionFilterKeepsColorXYZAligned(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: []float32{0, 0, 0}, HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: []float32{
				-5, 10, 0,
				10, 10, 0,
			}, HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: []float32{
				25, 10, 0,
				60, 10, 0,
			}, HAngleDeg: 10})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: []float32{
				0, 0, 0,
				15, 10, 0,
				30, 10, 0,
				45, 10, 0,
				80, 10, 0,
			}, HAngleDeg: 0})
		}
		if cb.OnColorPoints != nil {
			cb.OnColorPoints(ColorPointFrame{
				Unit: 1,
				XYZmm: []float32{
					-5, 10, 0,
					10, 10, 0,
					25, 10, 0,
					60, 10, 0,
				},
				RGB: []uint32{0x111111, 0x222222, 0x333333, 0x444444},
			})
		}
		return ScanResult{PtsA: 1, PtsB: 4, Fused: 5, AfterCrop: 5, Align: "none"}, nil
	}

	_, err := r.Run(context.Background(), RunSpec{
		JobID:      44,
		SessionKey: "sess-filter-rgb",
		Replay:     true,
		RegionFilter: PointRegionFilter{
			Enabled: true,
			Points: [][3]float32{
				{0, 0, 0},
				{30, 0, 0},
				{30, 30, 0},
				{0, 30, 0},
			},
			BToA: []float32{
				1, 0, 0, 20,
				0, 1, 0, 0,
				0, 0, 1, 0,
				0, 0, 0, 1,
			},
		},
	}, &recordSink{})
	if err != nil {
		t.Fatalf("Run 失败: %v", err)
	}
	xyz := clouds.xyz["unit_b"]
	rgb := clouds.rgb["unit_b"]
	if len(xyz) != 6 || len(rgb) != 2 {
		t.Fatalf("unit_b 应写入 2 个彩色点，xyz=%+v rgb=%#v", xyz, rgb)
	}
	if xyz[0] != -5 || xyz[3] != 10 || rgb[0] != 0x111111 || rgb[1] != 0x222222 {
		t.Fatalf("XYZ/RGB 未保持同一批点: xyz=%+v rgb=%#v", xyz, rgb)
	}
	if jobs.lastCompletion.PtsB != 2 {
		t.Fatalf("completion PtsB 应为过滤后点数，得 %+v", jobs.lastCompletion)
	}
}

func TestRunnerPointCallbackMismatchFails(t *testing.T) {
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	r := newTestRunner(jobs, clouds, nil)
	r.Replay = func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 90*3), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 10*3), HAngleDeg: 90})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 100*3), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 100*3), HAngleDeg: 90})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 250*3), HAngleDeg: 0})
		}
		return ScanResult{PtsA: 101, PtsB: 200, Fused: 300, AfterCrop: 300, Align: "none"}, nil
	}
	_, err := r.Run(context.Background(), RunSpec{JobID: 7, SessionKey: "s", Replay: true}, &recordSink{})
	if err == nil {
		t.Fatal("点数不一致应失败，得 nil")
	}
	if !strings.Contains(err.Error(), "点云回调累计与结果统计不一致") {
		t.Fatalf("错误应说明点数不一致，得 %v", err)
	}
	if jobs.failCalls != 1 || jobs.completeCalls != 0 {
		t.Errorf("应 Fail 1/Complete 0，得 Fail=%d Complete=%d", jobs.failCalls, jobs.completeCalls)
	}
	if len(clouds.counts) != 0 {
		t.Errorf("点数不一致不应落 PCD，得 %+v", clouds.counts)
	}
}

// TestRunnerDualCropBox：按单元框测量路由 + 双框 union/transformPoints 路径不 panic。
// 双框→crop_box_dual（跑 CropToBox(cloudA)∪BToA·CropToBox(cloudB)）；仅 A 框→crop_box。
func TestRunnerDualCropBox(t *testing.T) {
	bay := "192.168.9.101"
	bigBox := CropBox{Center: [3]float32{0, 0, 0}, Up: [3]float32{0, 0, 1}, YawDeg: 0, Half: [3]float32{5000, 5000, 5000}}
	// 自带 BToA=单位阵的扫描（默认 ScanResult 零矩阵会把 B 点全压到原点；测真实 transform 用单位阵）。
	dualScan := func(_, _, _, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		if cb.OnPoints != nil {
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 300), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, 300), HAngleDeg: 90})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 300), HAngleDeg: 0})
			cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, 300), HAngleDeg: 90})
			cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 600), HAngleDeg: 0})
		}
		if cb.OnStatus != nil {
			cb.OnStatus("fusing", 0, 0)
			cb.OnStatus("done", 0, 0)
		}
		return ScanResult{PtsA: 200, PtsB: 200, Fused: 200, AfterCrop: 200, Align: "icp",
			BToA: [16]float32{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}}, nil
	}
	runMode := func(t *testing.T, store *fakeCropBoxStore) string {
		jobs := &fakeJobStore{}
		r := newTestRunner(jobs, &fakeCloudStore{}, nil)
		r.Replay = dualScan
		r.CropBoxes = store
		if _, err := r.Run(context.Background(), RunSpec{JobID: 1, SessionKey: "s", UnitAIP: bay, Replay: true}, &recordSink{}); err != nil {
			t.Fatalf("Run 失败: %v", err)
		}
		var stats map[string]any
		_ = json.Unmarshal(jobs.lastCompletion.Stats, &stats)
		m, _ := stats["measure_mode"].(string)
		return m
	}

	dual := newFakeCropBoxStore()
	_ = dual.SaveCropBox(context.Background(), bay, "a", bigBox)
	_ = dual.SaveCropBox(context.Background(), bay, "b", bigBox)
	if m := runMode(t, dual); m != "crop_box_dual" {
		t.Errorf("双框应 crop_box_dual，得 %q", m)
	}

	aOnly := newFakeCropBoxStore()
	_ = aOnly.SaveCropBox(context.Background(), bay, "a", bigBox)
	if m := runMode(t, aOnly); m != "crop_box" {
		t.Errorf("仅 A 框应 crop_box，得 %q", m)
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
