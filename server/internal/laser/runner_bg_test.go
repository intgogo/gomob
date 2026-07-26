package laser

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"

	"io.gomob/server/pkg/repo"
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
func (s *bgFakeStore) PutMeasuredCloud(
	_ context.Context,
	sessionKey, name string,
	xyz []float32,
	artifact MeasuredCloudArtifact,
) (string, error) {
	pcd, err := EncodeMeasuredPCDBinary(xyz, artifact)
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

type bgFakeRevisionStore struct {
	seq    int64
	active *repo.LaserBackgroundRevision
}

func (s *bgFakeRevisionStore) GetActive(_ context.Context, _, _ string) (*repo.LaserBackgroundRevision, error) {
	if s.active == nil {
		return nil, repo.ErrNotFound
	}
	copy := *s.active
	return &copy, nil
}

func (s *bgFakeRevisionStore) Activate(_ context.Context, rev repo.LaserBackgroundRevision) (*repo.LaserBackgroundRevision, error) {
	s.seq++
	rev.ID = s.seq
	rev.Active = true
	s.active = &rev
	copy := rev
	return &copy, nil
}

func (s *bgFakeRevisionStore) ActivateAndComplete(
	ctx context.Context,
	jobID int64,
	_ repo.LaserScanCompletion,
	rev repo.LaserBackgroundRevision,
) (*repo.LaserScanJob, *repo.LaserBackgroundRevision, error) {
	created, err := s.Activate(ctx, rev)
	if err != nil {
		return nil, nil, err
	}
	return &repo.LaserScanJob{ID: jobID, Status: repo.LaserScanStatusDone}, created, nil
}

func backgroundTestProfile(ip string) UnitAcquisitionProfile {
	return newUnitAcquisitionProfile(ip, DeviceInfo{
		Model: "LTS-T1", SN: "SN-" + ip, HWVer: "1", SWVer: "1",
		Control: ControlSettings{ScanStartAngle: 0, ScanStopAngle: 90},
	}, 0, 90, 1, false)
}

func legacyTestEvidence(points int64, checksum string) json.RawMessage {
	return mustJSON(map[string]any{
		"binding_version":         1,
		"pipeline_revision":       repo.LaserBackgroundLegacyPipelineRevision,
		"source_revision_id":      1,
		"source_scan_id":          176,
		"source_fused_points":     points,
		"source_fused_xyz_sha256": checksum,
		"reference_scans":         []map[string]any{{"scan_id": 197}},
		"audit_report_sha256":     "audit-checksum",
		"harness_report_sha256":   "harness-checksum",
	})
}

func backgroundTestRegion(limit float32) PointRegionFilter {
	m := identity16()
	return PointRegionFilter{
		Enabled: true,
		Points: [][3]float32{
			{-limit, -limit, 0},
			{limit, -limit, 0},
			{limit, limit, 0},
			{-limit, limit, 0},
		},
		BToA: append([]float32(nil), m[:]...),
	}
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

func scanWithAlign(base ScanFunc, align string) ScanFunc {
	return func(ipA, ipB, requestedAlign, siteJSON string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		result, err := base(ipA, ipB, requestedAlign, siteJSON, keep, cb)
		result.Align = align
		return result, err
	}
}

func TestRunnerBackgroundSubtractWiring(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	revisions := &bgFakeRevisionStore{}
	pub := &fakePublisher{}
	mkRunner := func() *Runner {
		return &Runner{
			Jobs:                &fakeJobStore{},
			Clouds:              store,
			Reader:              store,
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
	roomWithOutside := append(append([]float32(nil), room...), 20000, 20000, 1000, -20000, -20000, 1000)
	roomVeh := append(append([]float32(nil), room...), makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)...)
	vehCnt := len(makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)) / 3

	// ① 采集空工位背景：存到稳定背景 key，事件标 background_captured。
	r := mkRunner()
	r.Replay = emitSceneScan(roomWithOutside)
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 1, SessionKey: "cap", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		MarkAsBackground: true, UnitAProfile: profileA, UnitBProfile: profileB,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("采集背景扫描失败: %v", err)
	}
	if revisions.active == nil || revisions.active.CoordinateSchema != repo.LaserBackgroundSchemaRegionCroppedUnitV1 {
		t.Fatalf("未激活 A/B 区域背景 revision: %+v", revisions.active)
	}
	if revisions.active.UnitAObjectKey == nil || revisions.active.UnitBObjectKey == nil ||
		*revisions.active.UnitAObjectKey == *revisions.active.UnitBObjectKey {
		t.Fatalf("背景 revision 的 A/B 区域对象键无效: %+v", revisions.active)
	}
	if revisions.active.SiteRevision == nil || *revisions.active.SiteRevision != siteCalibration.MatrixSHA256 {
		t.Fatalf("背景 revision 未绑定工位外参版本: %+v", revisions.active.SiteRevision)
	}
	if revisions.active.RegionRevision == nil || *revisions.active.RegionRevision != regionCalibration.PointsSHA256 {
		t.Fatalf("背景 revision 未绑定区域版本: %+v", revisions.active.RegionRevision)
	}
	bgBytes, ok := store.objs[*revisions.active.UnitAObjectKey]
	if !ok {
		t.Fatalf("A 区域背景对象不存在: %s", *revisions.active.UnitAObjectKey)
	}
	bgDec, _ := DecodePCDBinary(bgBytes)
	if got := len(bgDec) / 3; got < len(room)/3 || got >= len(roomWithOutside)/3+8 {
		t.Errorf("存的 A 区域背景点数=%d，未正确剔除区域外点", got)
	}
	for i := 0; i+2 < len(bgDec); i += 3 {
		if bgDec[i] < -5000 || bgDec[i] > 5000 || bgDec[i+1] < -5000 || bgDec[i+1] > 5000 {
			t.Fatalf("区域外点进入背景对象: (%f,%f,%f)", bgDec[i], bgDec[i+1], bgDec[i+2])
		}
	}
	capEvt := pub.events[len(pub.events)-1]
	if !capEvt.BackgroundCaptured || capEvt.MeasMode != "background_captured" {
		t.Errorf("采集事件应标 background_captured，got captured=%v mode=%s", capEvt.BackgroundCaptured, capEvt.MeasMode)
	}

	// ② 普通扫描：读回背景→bg_subtract→前景≈车（去掉房间）。
	r2 := mkRunner()
	r2.Replay = emitSceneScan(roomVeh)
	if _, err := r2.Run(context.Background(), RunSpec{
		JobID: 2, SessionKey: "meas", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB, BackgroundRevision: revisions.active,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
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
	if evt.MeasuredObjectKey == "" {
		t.Fatal("有效背景相减必须落测量同源车辆 PCD")
	}
	measuredBytes, ok := store.objs[evt.MeasuredObjectKey]
	if !ok {
		t.Fatalf("测量同源车辆 PCD 不存在: %s", evt.MeasuredObjectKey)
	}
	measuredCloud, decodeErr := DecodePCDBinary(measuredBytes)
	if decodeErr != nil || len(measuredCloud)/3 != evt.MeasuredPoints {
		t.Fatalf("测量 PCD 点数与事件不一致: err=%v pcd=%d event=%d", decodeErr, len(measuredCloud)/3, evt.MeasuredPoints)
	}

	// ③ site 扫描若场景精修未应用，必须保留诊断云但禁止生成 canonical measured。
	r3 := mkRunner()
	r3.Replay = scanWithAlign(emitSceneScan(roomVeh), "site")
	if _, err := r3.Run(context.Background(), RunSpec{
		JobID: 3, SessionKey: "refine-rejected", UnitAIP: bay, UnitBIP: profileB.IP,
		Align: "site", SiteJSON: testSiteJSON, Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB, BackgroundRevision: revisions.active,
		SiteCalibration: siteCalibration, RegionCalibration: regionCalibration, RegionFilter: regionFilter,
	}, nil); err != nil {
		t.Fatalf("精修未通过时仍应保存诊断结果，得 %v", err)
	}
	rejected := pub.events[len(pub.events)-1]
	if rejected.MeasMode != "refine_unaccepted" || rejected.MeasureValid || rejected.MeasuredObjectKey != "" {
		t.Fatalf("精修未通过不得输出 canonical measured: %+v", rejected)
	}
	if rejected.MeasureReason != "refine_unaccepted" {
		t.Fatalf("精修拒绝原因未透传: %+v", rejected)
	}
}

func TestRunnerReusesLegacyFusedBackground(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	pub := &fakePublisher{}
	legacyKey := "laser-scans/background/legacy-fused.pcd"
	profileA := backgroundTestProfile(bay)
	profileB := backgroundTestProfile("192.168.9.102")
	room := makeRoom(2000, 2000, 2000, 30)
	vehicle := makeVehicleShell(200, -200, 4000, 1800, 1500, 30, 0)
	// emitSceneScan 会给 A 追加 8 个守卫点，给 B 追加 20+8 个占位点；历史融合背景
	// 是区域裁剪后的 A∪B 云，因此对象必须包含相同静态占位点才能严格重放旧路径。
	backgroundFused := append(append([]float32(nil), room...), make([]float32, (8+20+8)*3)...)
	pcd, err := EncodePCDBinary(backgroundFused)
	if err != nil {
		t.Fatal(err)
	}
	store.objs[legacyKey] = pcd
	legacyChecksum := cloudFloatSHA256(backgroundFused)
	legacySite := "legacy-site-revision"
	legacyRegion := "legacy-region-revision"
	legacyEvidence := legacyTestEvidence(int64(len(backgroundFused)/3), legacyChecksum)
	r := &Runner{
		Jobs: &fakeJobStore{}, Clouds: store, Reader: store, Publisher: pub, Log: slog.Default(),
		Replay: emitSceneScan(append(append([]float32(nil), room...), vehicle...)),
	}
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 3, SessionKey: "legacy", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB,
		SiteCalibration:   SiteCalibrationSnapshot{MatrixSHA256: "legacy-site-revision"},
		RegionCalibration: RegionCalibrationSnapshot{PointsSHA256: "legacy-region-revision"},
		BackgroundRevision: &repo.LaserBackgroundRevision{
			ID: 176, UnitAIP: bay, UnitBIP: profileB.IP,
			LegacyFusedObjectKey:  &legacyKey,
			LegacyFusedPoints:     int64(len(backgroundFused) / 3),
			LegacyFusedChecksum:   &legacyChecksum,
			CompatibilitySite:     &legacySite,
			CompatibilityRegion:   &legacyRegion,
			CompatibilityEvidence: legacyEvidence,
			UnitAIdentity:         profileA.identityJSON(),
			UnitBIdentity:         profileB.identityJSON(),
			UnitADeviceConfigHash: &profileA.DeviceConfigSHA256,
			UnitBDeviceConfigHash: &profileB.DeviceConfigSHA256,
			UnitAScanConfigHash:   &profileA.ScanConfigSHA256,
			UnitBScanConfigHash:   &profileB.ScanConfigSHA256,
			CoordinateSchema:      repo.LaserBackgroundSchemaLegacyVerifiedFused,
		},
	}, nil); err != nil {
		t.Fatalf("legacy 融合背景应重放修改前测量路径，得 %v", err)
	}
	evt := pub.events[len(pub.events)-1]
	if evt.MeasMode != "bg_subtract" || !evt.BackgroundCompatible || evt.BackgroundReason != "ready" {
		t.Fatalf("legacy 融合背景未进入兼容相减路径: %+v", evt)
	}
	if !evt.BackgroundSet || evt.BackgroundSchema != repo.LaserBackgroundSchemaLegacyVerifiedFused {
		t.Fatalf("legacy 背景身份未保留: %+v", evt)
	}
	vehiclePoints := len(vehicle) / 3
	if evt.FgPoints < int(float64(vehiclePoints)*0.85) || evt.FgPoints > int(float64(vehiclePoints)*1.20) {
		t.Fatalf("legacy 背景未正确移除静态房间：前景=%d 车辆真值=%d", evt.FgPoints, vehiclePoints)
	}
}

func TestRunnerDamagedLegacyBackgroundFailsClosed(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	pub := &fakePublisher{}
	legacyKey := "laser-scans/background/missing-legacy-fused.pcd"
	legacyChecksum := "checksum"
	legacySite := "legacy-site-revision"
	legacyRegion := "legacy-region-revision"
	legacyEvidence := legacyTestEvidence(1, legacyChecksum)
	profileA := backgroundTestProfile(bay)
	profileB := backgroundTestProfile("192.168.9.102")
	r := &Runner{
		Jobs: &fakeJobStore{}, Clouds: store, Reader: store, Publisher: pub, Log: slog.Default(),
		Replay: emitSceneScan(makeRoom(4000, 3000, 2500, 50)),
	}
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 4, SessionKey: "damaged-legacy", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB,
		SiteCalibration:   SiteCalibrationSnapshot{MatrixSHA256: legacySite},
		RegionCalibration: RegionCalibrationSnapshot{PointsSHA256: legacyRegion},
		BackgroundRevision: &repo.LaserBackgroundRevision{
			ID: 176, UnitAIP: bay, UnitBIP: profileB.IP,
			LegacyFusedObjectKey:  &legacyKey,
			LegacyFusedPoints:     1,
			LegacyFusedChecksum:   &legacyChecksum,
			CompatibilitySite:     &legacySite,
			CompatibilityRegion:   &legacyRegion,
			CompatibilityEvidence: legacyEvidence,
			UnitAIdentity:         profileA.identityJSON(),
			UnitBIdentity:         profileB.identityJSON(),
			UnitADeviceConfigHash: &profileA.DeviceConfigSHA256,
			UnitBDeviceConfigHash: &profileB.DeviceConfigSHA256,
			UnitAScanConfigHash:   &profileA.ScanConfigSHA256,
			UnitBScanConfigHash:   &profileB.ScanConfigSHA256,
			CoordinateSchema:      repo.LaserBackgroundSchemaLegacyVerifiedFused,
		},
	}, nil); err != nil {
		t.Fatalf("损坏 legacy 背景应完成诊断任务，得 %v", err)
	}
	evt := pub.events[len(pub.events)-1]
	if evt.MeasMode != "background_incompatible" || evt.MeasureValid || evt.BackgroundCompatible {
		t.Fatalf("损坏 legacy 背景必须 fail closed: %+v", evt)
	}
	if evt.BackgroundReason != "legacy_fused_object_unavailable" {
		t.Fatalf("损坏 legacy 背景原因错误: %+v", evt)
	}
}

func TestLegacyFusedBackgroundChecksumMismatchFailsClosed(t *testing.T) {
	store := newBgFakeStore()
	key := "laser-scans/background/tampered-legacy-fused.pcd"
	points := []float32{1, 2, 3, 4, 5, 6}
	pcd, err := EncodePCDBinary(points)
	if err != nil {
		t.Fatal(err)
	}
	store.objs[key] = pcd
	wrongChecksum := "not-the-content-checksum"
	revision := &repo.LaserBackgroundRevision{
		ID: 176, LegacyFusedObjectKey: &key,
		LegacyFusedPoints: 2, LegacyFusedChecksum: &wrongChecksum,
		CoordinateSchema: repo.LaserBackgroundSchemaLegacyVerifiedFused,
	}
	runner := &Runner{Reader: store, Log: slog.Default()}
	if _, reason := runner.loadLegacyFusedBackground(context.Background(), revision); reason != "legacy_fused_checksum_mismatch" {
		t.Fatalf("同点数内容篡改必须由 checksum 拒绝，reason=%s", reason)
	}
}

func TestRunnerDamagedRawBackgroundNeverFallsBackToCropBox(t *testing.T) {
	const bay = "192.168.9.101"
	store := newBgFakeStore()
	pub := &fakePublisher{}
	profileA := backgroundTestProfile(bay)
	profileB := backgroundTestProfile("192.168.9.102")
	missingA, missingB := "missing/background-a.pcd", "missing/background-b.pcd"
	checksumA, checksumB := "checksum-a", "checksum-b"
	deviceHashA, deviceHashB := profileA.DeviceConfigSHA256, profileB.DeviceConfigSHA256
	scanHashA, scanHashB := profileA.ScanConfigSHA256, profileB.ScanConfigSHA256
	siteRevision := "site-test-revision"
	revision := &repo.LaserBackgroundRevision{
		ID: 177, UnitAIP: bay, UnitBIP: profileB.IP,
		UnitAObjectKey: &missingA, UnitBObjectKey: &missingB,
		SiteRevision: &siteRevision, UnitAPoints: 100, UnitBPoints: 100,
		UnitAChecksum: &checksumA, UnitBChecksum: &checksumB,
		UnitAIdentity: profileA.identityJSON(), UnitBIdentity: profileB.identityJSON(),
		UnitADeviceConfigHash: &deviceHashA, UnitBDeviceConfigHash: &deviceHashB,
		UnitAScanConfigHash: &scanHashA, UnitBScanConfigHash: &scanHashB,
		CoordinateSchema: repo.LaserBackgroundSchemaRawUnitFramesV1,
	}
	boxes := newFakeCropBoxStore()
	_ = boxes.SaveCropBox(context.Background(), bay, "a", CropBox{
		Center: [3]float32{0, 0, 1000}, Up: [3]float32{0, 0, 1}, Half: [3]float32{5000, 5000, 5000},
	})
	r := &Runner{
		Jobs: &fakeJobStore{}, Clouds: store, Reader: store, Publisher: pub, CropBoxes: boxes, Log: slog.Default(),
		Replay: emitSceneScan(makeRoom(4000, 3000, 2500, 50)),
	}
	if _, err := r.Run(context.Background(), RunSpec{
		JobID: 4, SessionKey: "damaged-raw", UnitAIP: bay, UnitBIP: profileB.IP, Align: "icp", Replay: true,
		UnitAProfile: profileA, UnitBProfile: profileB, BackgroundRevision: revision,
		SiteCalibration: SiteCalibrationSnapshot{MatrixSHA256: siteRevision},
	}, nil); err != nil {
		t.Fatalf("背景损坏应完成诊断任务但拒绝测量，得 %v", err)
	}
	evt := pub.events[len(pub.events)-1]
	if evt.MeasMode != "background_incompatible" || evt.BackgroundReason != "raw_object_unavailable" {
		t.Fatalf("背景对象损坏不得回退 crop box: %+v", evt)
	}
	if evt.MeasureValid || evt.MeasuredObjectKey != "" || evt.LengthMM != 0 || evt.WidthMM != 0 || evt.HeightMM != 0 {
		t.Fatalf("背景对象损坏不得产 canonical measured/LWH: %+v", evt)
	}
}
