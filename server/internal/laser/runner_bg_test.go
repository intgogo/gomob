package laser

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
)

// runner_bg_test.go = 背景相减在 runner 里的端到端接线验证（用 fake 存储/扫描，不依赖真 MinIO/设备）：
// ① MarkAsBackground 扫描把融合云存到稳定背景 key；② 之后普通扫描读回背景、走 bg_subtract、前景=去房间。
// LWH 数值正确性由 background_test.go(调好参数)担保；这里只证「采集→保存→读回→相减」整条链通。

// bgFakeStore 同时实现 CloudStore + CloudReader，按【完整 object key】索引并做真 PCD 编解码（镜像 MinIO）。
type bgFakeStore struct {
	objs map[string][]byte // objectKey → PCD bytes
}

func newBgFakeStore() *bgFakeStore { return &bgFakeStore{objs: map[string][]byte{}} }

func (s *bgFakeStore) PutCloud(_ context.Context, sessionKey, name string, xyz []float32) (string, error) {
	pcd, err := EncodePCDBinary(xyz)
	if err != nil {
		return "", err
	}
	key := LaserObjectKey(sessionKey, name)
	s.objs[key] = pcd
	return key, nil
}
func (s *bgFakeStore) PutCloudXYZI(ctx context.Context, sk, n string, xyz, _ []float32) (string, error) {
	return s.PutCloud(ctx, sk, n, xyz)
}
func (s *bgFakeStore) PutCloudXYZRGB(ctx context.Context, sk, n string, xyz []float32, _ []uint32) (string, error) {
	return s.PutCloud(ctx, sk, n, xyz)
}
func (s *bgFakeStore) PutCloudXYZRGBI(ctx context.Context, sk, n string, xyz []float32, _ []uint32, _ []float32) (string, error) {
	return s.PutCloud(ctx, sk, n, xyz)
}

type readCloser struct{ io.Reader }

func (readCloser) Close() error { return nil }

func (s *bgFakeStore) GetObject(_ context.Context, key string) (io.ReadCloser, int64, error) {
	b, ok := s.objs[key]
	if !ok {
		return nil, 0, errors.New("不存在")
	}
	return readCloser{bytesReader(b)}, int64(len(b)), nil
}

func bytesReader(b []byte) io.Reader { return &sliceReader{b: b} }

type sliceReader struct {
	b []byte
	i int
}

func (r *sliceReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}

func identity16() [16]float32 {
	return [16]float32{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}
}

// emitSceneScan 返回一个扫描函数：把整幕场景塞进 unit0（cloudA），unit1 给少量原点占位。
// runner 实测把融合云重建为 cloudA ∪ BToA·cloudB（丢弃 native 融合帧），故场景必须经分镜云进。
// BToA=单位阵 → cloudFus = sceneA + unit1 占位点（占位点在两次扫描里相同、相减抵消）。
func emitSceneScan(sceneA []float32) ScanFunc {
	const guardA, occB = 8, 20 // 过空扫守卫的额外帧/占位点（两次扫描一致，相减抵消）
	return func(_, _, _ string, _ string, _ float32, cb ScanCallbacks) (ScanResult, error) {
		if cb.OnStatus != nil {
			cb.OnStatus("scanning", 0, 0)
		}
		cb.OnPoints(PointFrame{Unit: 0, XYZmm: append([]float32(nil), sceneA...), HAngleDeg: 0})
		cb.OnPoints(PointFrame{Unit: 0, XYZmm: make([]float32, guardA*3), HAngleDeg: 90})
		cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, occB*3), HAngleDeg: 0})
		cb.OnPoints(PointFrame{Unit: 1, XYZmm: make([]float32, guardA*3), HAngleDeg: 90})
		// native 融合帧 token：runner 会丢弃它、用 cloudA∪BToA·cloudB 重建融合云，但 rawCounts 守卫
		// 要求 rawFus==res.Fused，故仍需发等量 unit2 占位帧过校验。
		const fusedTok = 30
		cb.OnPoints(PointFrame{Unit: 2, XYZmm: make([]float32, fusedTok*3), HAngleDeg: 0})
		if cb.OnStatus != nil {
			cb.OnStatus("done", 0, 0)
		}
		nA := len(sceneA)/3 + guardA
		nB := occB + guardA
		return ScanResult{PtsA: nA, PtsB: nB, Fused: fusedTok, AfterCrop: fusedTok, Align: "icp", BToA: identity16()}, nil
	}
}

func TestRunnerBackgroundSubtractWiring(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	pub := &fakePublisher{}
	mkRunner := func() *Runner {
		return &Runner{
			Jobs:      &fakeJobStore{},
			Clouds:    store,
			Reader:    store,
			Publisher: pub,
			Log:       slog.Default(),
		}
	}
	room := makeRoom(2000, 2000, 2000, 30)
	roomVeh := append(append([]float32(nil), room...), makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)...)
	vehCnt := len(makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)) / 3

	// ① 采集空工位背景：存到稳定背景 key，事件标 background_captured。
	r := mkRunner()
	r.Replay = emitSceneScan(room)
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 1, SessionKey: "cap", UnitAIP: bay, Align: "icp", Replay: true, MarkAsBackground: true,
	}, nil); err != nil {
		t.Fatalf("采集背景扫描失败: %v", err)
	}
	bgBytes, ok := store.objs[backgroundObjectKey(bay)]
	if !ok {
		t.Fatalf("背景未存到稳定 key %s", backgroundObjectKey(bay))
	}
	bgDec, _ := DecodePCDBinary(bgBytes)
	if got := len(bgDec) / 3; got < len(room)/3 {
		t.Errorf("存的背景点数=%d 少于 room %d（融合云重建丢点？）", got, len(room)/3)
	}
	capEvt := pub.events[len(pub.events)-1]
	if !capEvt.BackgroundCaptured || capEvt.MeasMode != "background_captured" {
		t.Errorf("采集事件应标 background_captured，got captured=%v mode=%s", capEvt.BackgroundCaptured, capEvt.MeasMode)
	}

	// ② 普通扫描：读回背景→bg_subtract→前景≈车（去掉房间）。
	r2 := mkRunner()
	r2.Replay = emitSceneScan(roomVeh)
	if _, err := r2.Run(context.Background(), RunSpec{
		JobID: 2, SessionKey: "meas", UnitAIP: bay, Align: "icp", Replay: true,
	}, nil); err != nil {
		t.Fatalf("测量扫描失败: %v", err)
	}
	evt := pub.events[len(pub.events)-1]
	t.Logf("测量事件: mode=%s bg_set=%v fg=%d (融合=%d, 车真值=%d)", evt.MeasMode, evt.BackgroundSet, evt.FgPoints, len(roomVeh)/3, vehCnt)
	if evt.MeasMode != "bg_subtract" {
		t.Fatalf("应走 bg_subtract，got %s", evt.MeasMode)
	}
	if !evt.BackgroundSet {
		t.Error("background_set 应为 true")
	}
	// 前景应≈车真值（减掉了房间），远小于融合总点数。
	if evt.FgPoints < int(float64(vehCnt)*0.85) || evt.FgPoints > int(float64(vehCnt)*1.20) {
		t.Errorf("前景点数=%d 偏离车真值 %d 太多（背景相减异常）", evt.FgPoints, vehCnt)
	}
	if evt.FgPoints >= len(roomVeh)/3 {
		t.Errorf("前景=%d 未小于融合总点数 %d（没减掉房间）", evt.FgPoints, len(roomVeh)/3)
	}
}
