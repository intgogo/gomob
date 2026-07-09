package laser

import (
	"context"
	"encoding/json"
	"log/slog"
	"testing"
)

// runner_ground_test.go = M13 持久地面接线测试：
// ① 采集空工位背景时自动拟合地面并入库；② 后续扫描优先用持久地面（stats.ground_source=persisted），
// 且逐扫描重拟合只作漂移诊断，不改测量基准。

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

func TestRunnerPersistsAndReusesGround(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	grounds := newFakeGroundStore()
	pub := &fakePublisher{}
	mkRunner := func() *Runner {
		return &Runner{
			Jobs:      &fakeJobStore{},
			Clouds:    store,
			Reader:    store,
			Grounds:   grounds,
			Publisher: pub,
			Log:       slog.Default(),
		}
	}
	room := makeRoom(2000, 2000, 2000, 30)
	roomVeh := append(append([]float32(nil), room...), makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)...)

	// ① 采集背景 → 地面拟合入库。
	r := mkRunner()
	r.Replay = emitSceneScan(room)
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 1, SessionKey: "cap", UnitAIP: bay, Align: "icp", Replay: true, MarkAsBackground: true,
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

	// ② 扫描测量 → 用持久地面（source=persisted），重拟合仅诊断。
	jobs := &fakeJobStore{}
	r2 := mkRunner()
	r2.Jobs = jobs
	r2.Replay = emitSceneScan(roomVeh)
	if _, err := r2.Run(context.Background(), RunSpec{
		JobID: 2, SessionKey: "meas", UnitAIP: bay, Align: "icp", Replay: true,
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
	if stats.GroundSource != "persisted" {
		t.Fatalf("应用持久地面, got source=%q", stats.GroundSource)
	}
	if stats.Ground != saved {
		t.Fatalf("测量用的地面应与持久地面一致:\n got=%+v\nwant=%+v", stats.Ground, saved)
	}
	if stats.GroundDriftDeg < 0 {
		t.Fatalf("应产出地面漂移诊断, got %f", stats.GroundDriftDeg)
	}
	if stats.MeasureMode != "bg_subtract" {
		t.Fatalf("应走 bg_subtract, got %s", stats.MeasureMode)
	}

	// ③ 无持久地面（新工位）→ 回退逐扫描拟合，source=refit。
	jobs3 := &fakeJobStore{}
	r3 := mkRunner()
	r3.Jobs = jobs3
	r3.Grounds = newFakeGroundStore()
	r3.Replay = emitSceneScan(roomVeh)
	if _, err := r3.Run(context.Background(), RunSpec{
		JobID: 3, SessionKey: "meas2", UnitAIP: bay, Align: "icp", Replay: true,
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
