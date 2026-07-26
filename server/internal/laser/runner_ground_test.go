package laser

import (
	"context"
	"encoding/json"
	"log/slog"
	"testing"
)

// runner_ground_test.go = M13 持久地面接线测试：
// ① 采集空工位背景时自动拟合地面并入库；② 后续扫描从同一 raw revision 按当前外参重建背景地面，
// 且逐扫描 live 重拟合只作漂移诊断。

type fakeGroundStore struct {
	m map[string]GroundPlane
}

func newFakeGroundStore() *fakeGroundStore { return &fakeGroundStore{m: map[string]GroundPlane{}} }

func (s *fakeGroundStore) GetGround(_ context.Context, bayKey string) (GroundPlane, bool, error) {
	g, ok := s.m[bayKey]
	return g, ok, nil
}

func (s *fakeGroundStore) SaveGround(_ context.Context, bayKey string, g GroundPlane) error {
	s.m[bayKey] = g
	return nil
}

func translateCloudForGroundTest(xyz []float32, dz float32) []float32 {
	out := append([]float32(nil), xyz...)
	for i := 2; i < len(out); i += 3 {
		out[i] += dz
	}
	return out
}

// 两单元点都落在竖直平面 y=0，重力先验会拒绝，稳定复现 live 地面重拟合无效。
func emitNoGroundScan() ScanFunc {
	return func(_, _, _ string, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		makeLine := func(x float32) []float32 {
			points := make([]float32, 0, 120*3)
			for i := 0; i < 120; i++ {
				points = append(points, x, 0, float32(i*20))
			}
			return points
		}
		a, b := makeLine(0), makeLine(1000)
		cb.OnPoints(PointFrame{Unit: 0, XYZmm: a[:60*3], HAngleDeg: 0})
		cb.OnPoints(PointFrame{Unit: 0, XYZmm: a[60*3:], HAngleDeg: 90})
		cb.OnPoints(PointFrame{Unit: 1, XYZmm: b[:60*3], HAngleDeg: 0})
		cb.OnPoints(PointFrame{Unit: 1, XYZmm: b[60*3:], HAngleDeg: 90})
		cb.OnStatus("fusing", 120, 120)
		cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, 240*3)})
		return ScanResult{PtsA: 120, PtsB: 120, Fused: 240, AfterCrop: 240, Align: "icp", BToA: identity16()}, nil
	}
}

func TestRunnerPersistsAndReusesGround(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	revisions := &bgFakeRevisionStore{}
	grounds := newFakeGroundStore()
	pub := &fakePublisher{}
	mkRunner := func() *Runner {
		return &Runner{
			Jobs:                &fakeJobStore{},
			Clouds:              store,
			Reader:              store,
			Grounds:             grounds,
			BackgroundFinalizer: revisions,
			Publisher:           pub,
			Log:                 slog.Default(),
		}
	}
	profileA := backgroundTestProfile(bay)
	profileB := backgroundTestProfile("192.168.9.102")
	siteCalibration := SiteCalibrationSnapshot{MatrixSHA256: "site-test-revision"}
	regionCalibration := RegionCalibrationSnapshot{PointsSHA256: "region-test-revision"}
	regionFilter := backgroundTestRegion(5000)
	room := makeRoom(2000, 2000, 2000, 30)
	roomVeh := append(append([]float32(nil), room...), makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)...)

	// ① 采集背景 → 地面拟合入库。
	r := mkRunner()
	r.Replay = emitSceneScan(room)
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 1, SessionKey: "cap", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		MarkAsBackground: true, UnitAProfile: profileA, UnitBProfile: profileB,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("采集背景失败: %v", err)
	}
	saved, ok := grounds.m[bay]
	if !ok || !saved.Valid {
		t.Fatalf("背景采集应持久化有效地面, got ok=%v %+v", ok, saved)
	}
	if saved.NZ < 0.99 {
		t.Fatalf("合成房间地面法向应≈+Z, got (%f,%f,%f)", saved.NX, saved.NY, saved.NZ)
	}

	// ② 扫描测量 → 用 A/B 区域背景按当前外参重建地面（source=background_revision），重拟合仅诊断。
	jobs := &fakeJobStore{}
	r2 := mkRunner()
	r2.Jobs = jobs
	r2.Replay = emitSceneScan(roomVeh)
	if _, err := r2.Run(context.Background(), RunSpec{
		JobID: 2, SessionKey: "meas", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB, BackgroundRevision: revisions.active,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("测量扫描失败: %v", err)
	}
	var stats struct {
		GroundSource   string      `json:"ground_source"`
		GroundDriftDeg float32     `json:"ground_drift_deg"`
		Ground         GroundPlane `json:"ground"`
		MeasureMode    string      `json:"measure_mode"`
	}
	if err := json.Unmarshal(jobs.lastCompletion.Stats, &stats); err != nil {
		t.Fatalf("解 stats 失败: %v", err)
	}
	if stats.GroundSource != "background_revision" {
		t.Fatalf("应从 A/B 背景重建地面, got source=%q", stats.GroundSource)
	}
	if !stats.Ground.Valid || stats.Ground.NZ < 0.99 {
		t.Fatalf("A/B 背景重建地面无效: %+v", stats.Ground)
	}
	if stats.GroundDriftDeg < 0 {
		t.Fatalf("应产出地面漂移诊断, got %f", stats.GroundDriftDeg)
	}
	if stats.MeasureMode != "bg_subtract" {
		t.Fatalf("应走 bg_subtract, got %s", stats.MeasureMode)
	}

	// ③ 设备/工位整体抬高 100mm，但软件配置未变：背景兼容指纹仍相同，地面漂移门必须拦住测量。
	rDrift := mkRunner()
	rDrift.Replay = emitSceneScan(translateCloudForGroundTest(roomVeh, 100))
	if _, err := rDrift.Run(context.Background(), RunSpec{
		JobID: 3, SessionKey: "drift", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB, BackgroundRevision: revisions.active,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("漂移诊断扫描失败: %v", err)
	}
	driftEvent := pub.events[len(pub.events)-1]
	if driftEvent.MeasMode != "ground_drift" || driftEvent.MeasureValid || driftEvent.MeasuredObjectKey != "" {
		t.Fatalf("地面漂移超限不得输出 canonical measured: %+v", driftEvent)
	}
	if !driftEvent.BackgroundCompatible {
		t.Fatalf("本测试配置指纹未变，应由地面漂移门而非背景指纹门阻断: %+v", driftEvent)
	}

	// ④ 无持久地面（新工位）→ 回退逐扫描拟合，source=refit。
	jobs3 := &fakeJobStore{}
	r3 := mkRunner()
	r3.Jobs = jobs3
	r3.Grounds = newFakeGroundStore()
	r3.Replay = emitSceneScan(roomVeh)
	if _, err := r3.Run(context.Background(), RunSpec{
		JobID: 4, SessionKey: "meas2", UnitAIP: bay, Align: "icp", Replay: true,
	}, nil); err != nil {
		t.Fatalf("回退扫描失败: %v", err)
	}
	var stats3 struct {
		GroundSource string `json:"ground_source"`
	}
	_ = json.Unmarshal(jobs3.lastCompletion.Stats, &stats3)
	if stats3.GroundSource != "refit" {
		t.Fatalf("无持久地面应回退 refit, got %q", stats3.GroundSource)
	}
}

func TestRunnerRejectsBackgroundCaptureWithoutValidGround(t *testing.T) {
	jobs := &fakeJobStore{}
	revisions := &bgFakeRevisionStore{}
	publisher := &fakePublisher{}
	runner := &Runner{
		Jobs: jobs, Clouds: newBgFakeStore(), Publisher: publisher,
		BackgroundFinalizer: revisions, Replay: emitNoGroundScan(), Log: slog.Default(),
	}
	profileA := backgroundTestProfile("192.168.9.101")
	profileB := backgroundTestProfile("192.168.9.102")
	regionCalibration := RegionCalibrationSnapshot{PointsSHA256: "region-test-revision"}
	regionFilter := backgroundTestRegion(5000)

	_, err := runner.Run(context.Background(), RunSpec{
		JobID: 30, SessionKey: "invalid-ground-background", UnitAIP: profileA.IP, UnitBIP: profileB.IP,
		Align: "icp", Replay: true, MarkAsBackground: true,
		UnitAProfile: profileA, UnitBProfile: profileB,
		SiteCalibration:   SiteCalibrationSnapshot{MatrixSHA256: "site-test-revision"},
		RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil)
	if err == nil {
		t.Fatal("地面无效的空工位背景必须被拒绝")
	}
	if revisions.active != nil {
		t.Fatalf("地面无效不得激活背景 revision: %+v", revisions.active)
	}
	if jobs.failCalls != 1 || len(publisher.events) != 0 {
		t.Fatalf("应 Fail 1 且不发布 done，fail=%d events=%d", jobs.failCalls, len(publisher.events))
	}
}

func TestRunnerBlocksMeasurementWhenLiveGroundRefitIsInvalid(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	revisions := &bgFakeRevisionStore{}
	publisher := &fakePublisher{}
	profileA := backgroundTestProfile(bay)
	profileB := backgroundTestProfile("192.168.9.102")
	siteCalibration := SiteCalibrationSnapshot{MatrixSHA256: "site-test-revision"}
	regionCalibration := RegionCalibrationSnapshot{PointsSHA256: "region-test-revision"}
	regionFilter := backgroundTestRegion(5000)

	capture := &Runner{
		Jobs: &fakeJobStore{}, Clouds: store, Publisher: publisher, BackgroundFinalizer: revisions,
		Replay: emitSceneScan(makeRoom(2000, 2000, 2000, 30)), Log: slog.Default(),
	}
	if _, err := capture.Run(context.Background(), RunSpec{
		JobID: 31, SessionKey: "valid-background", UnitAIP: bay, UnitBIP: profileB.IP,
		Align: "icp", Replay: true, MarkAsBackground: true,
		UnitAProfile: profileA, UnitBProfile: profileB, SiteCalibration: siteCalibration,
		RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("准备有效背景失败: %v", err)
	}

	measure := &Runner{
		Jobs: &fakeJobStore{}, Clouds: store, Reader: store, Publisher: publisher,
		Replay: emitNoGroundScan(), Log: slog.Default(),
	}
	if _, err := measure.Run(context.Background(), RunSpec{
		JobID: 32, SessionKey: "invalid-live-ground", UnitAIP: bay, UnitBIP: profileB.IP,
		Align: "icp", Replay: true, UnitAProfile: profileA, UnitBProfile: profileB,
		BackgroundRevision: revisions.active, SiteCalibration: siteCalibration,
		RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("地面复核失败应保留诊断结果而非任务失败: %v", err)
	}
	evt := publisher.events[len(publisher.events)-1]
	if evt.MeasMode != "ground_refit_invalid" || evt.MeasureValid || evt.MeasuredObjectKey != "" {
		t.Fatalf("live 地面无法复核时不得输出 canonical measured: %+v", evt)
	}
}
