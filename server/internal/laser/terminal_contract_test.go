package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"io.gomob/server/pkg/repo"
)

type terminalContractRepo struct {
	jobs    map[int64]*repo.LaserScanJob
	findErr error
}

func (f *terminalContractRepo) Create(
	context.Context,
	string,
	string,
	string,
	string,
	float32,
	*int64,
	*int64,
) (*repo.LaserScanJob, error) {
	return nil, errors.New("测试未实现 Create")
}

func (f *terminalContractRepo) FindByID(_ context.Context, id int64) (*repo.LaserScanJob, error) {
	if f.findErr != nil {
		return nil, f.findErr
	}
	job := f.jobs[id]
	if job == nil {
		return nil, repo.ErrNotFound
	}
	copy := *job
	return &copy, nil
}

func terminalContractServe(h *Handler, method, path, userID string, body io.Reader) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, body)
	req.Header.Set("X-Gomob-User-Id", userID)
	mux := http.NewServeMux()
	h.Mount(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func (f *terminalContractRepo) FindLatestMeasurement(
	context.Context,
	string,
	string,
	*int64,
) (*repo.LaserScanJob, error) {
	return nil, repo.ErrNotFound
}

func (f *terminalContractRepo) Cancel(context.Context, int64) (*repo.LaserScanJob, error) {
	return nil, errors.New("测试未实现 Cancel")
}

func (f *terminalContractRepo) MarkFusing(context.Context, int64, int, int) (*repo.LaserScanJob, error) {
	return nil, errors.New("测试未实现 MarkFusing")
}

func (f *terminalContractRepo) Complete(context.Context, int64, repo.LaserScanCompletion) (*repo.LaserScanJob, error) {
	return nil, errors.New("测试未实现 Complete")
}

func (f *terminalContractRepo) Fail(context.Context, int64, string) (*repo.LaserScanJob, error) {
	return nil, errors.New("测试未实现 Fail")
}

type terminalContractCloudReader struct {
	blobs         map[string][]byte
	requestedKeys []string
}

func (r *terminalContractCloudReader) GetObject(_ context.Context, key string) (io.ReadCloser, int64, error) {
	r.requestedKeys = append(r.requestedKeys, key)
	blob, ok := r.blobs[key]
	if !ok {
		return nil, 0, errors.New("对象不存在")
	}
	return io.NopCloser(bytes.NewReader(blob)), int64(len(blob)), nil
}

func terminalContractRequest(h *Handler, scanID int64, name, userID string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(
		http.MethodGet,
		"/v1/scans/laser/"+strconv.FormatInt(scanID, 10)+"/cloud/"+name,
		nil,
	)
	req.Header.Set("X-Gomob-User-Id", userID)
	mux := http.NewServeMux()
	h.Mount(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func TestDownloadMeasuredCloudStrictlyUsesMeasuredObjectKey(t *testing.T) {
	owner := int64(7)
	fusedKey := "laser-scans/strict-contract/fused-authoritative.pcd"
	measuredKey := "laser-scans/strict-contract/measured-authoritative.pcd"
	fusedPCD, err := EncodePCDBinary([]float32{101, 102, 103})
	if err != nil {
		t.Fatal(err)
	}
	measuredPoints := []float32{1, 2, 3, 4, 5, 6}
	artifact := newMeasuredCloudArtifact(measuredPoints, identity16(), "site-revision", "region-revision", 301)
	measuredPCD, err := EncodeMeasuredPCDBinary(measuredPoints, artifact)
	if err != nil {
		t.Fatal(err)
	}
	bToA, _ := json.Marshal(identity16())
	stats := mustJSON(map[string]any{
		"measured_artifact":      artifact,
		"measured_points":        artifact.SourcePoints,
		"background_revision_id": artifact.BackgroundRevision,
		"site_calibration":       SiteCalibrationSnapshot{MatrixSHA256: artifact.SiteRevision},
		"region_calibration":     RegionCalibrationSnapshot{PointsSHA256: artifact.RegionRevision},
	})

	jobs := &terminalContractRepo{jobs: map[int64]*repo.LaserScanJob{
		11: {
			ID:                11,
			OwnerUserID:       &owner,
			Status:            repo.LaserScanStatusDone,
			FusedObjectKey:    &fusedKey,
			MeasuredObjectKey: &measuredKey,
			Stats:             stats,
			BToA:              bToA,
		},
		12: {
			ID:             12,
			OwnerUserID:    &owner,
			Status:         repo.LaserScanStatusDone,
			FusedObjectKey: &fusedKey,
		},
		13: {
			ID:                13,
			OwnerUserID:       &owner,
			Status:            repo.LaserScanStatusDone,
			FusedObjectKey:    &fusedKey,
			MeasuredObjectKey: &measuredKey,
		},
	}}
	reader := &terminalContractCloudReader{blobs: map[string][]byte{
		fusedKey:    fusedPCD,
		measuredKey: measuredPCD,
	}}
	h := NewHandler(Config{}, jobs, nil, nil, nil)
	h.SetCloudReader(reader)

	rec := terminalContractRequest(h, 11, "measured", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("owner 下载 measured 应为 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	if len(reader.requestedKeys) != 1 || reader.requestedKeys[0] != measuredKey {
		t.Fatalf("measured 端点必须只读取 MeasuredObjectKey，实际请求 %+v", reader.requestedKeys)
	}
	gotPoints, err := DecodePCDBinary(rec.Body.Bytes())
	if err != nil {
		t.Fatalf("measured 响应应为合法 PCD: %v", err)
	}
	if len(gotPoints) != len(measuredPoints) {
		t.Fatalf("measured 点数错误，得 %d，期望 %d", len(gotPoints)/3, len(measuredPoints)/3)
	}
	if rec.Header().Get("X-Gomob-XYZ-SHA256") != artifact.XYZSHA256 ||
		rec.Header().Get("X-Gomob-Coordinate-Schema") != artifact.CoordinateSchema ||
		rec.Header().Get("X-Gomob-Final-B-To-A-SHA256") != artifact.FinalBToASHA256 {
		t.Fatalf("measured 内容身份响应头错误: %+v", rec.Header())
	}
	for i := range measuredPoints {
		if gotPoints[i] != measuredPoints[i] {
			t.Fatalf("measured 点云被 fused 替代，索引 %d 得 %v，期望 %v", i, gotPoints[i], measuredPoints[i])
		}
	}

	if rec := terminalContractRequest(h, 11, "measured", "99"); rec.Code != http.StatusForbidden {
		t.Fatalf("他人下载 measured 应为 403，得 %d", rec.Code)
	}
	if len(reader.requestedKeys) != 1 {
		t.Fatalf("越权请求不得读取对象存储，实际请求 %+v", reader.requestedKeys)
	}

	if rec := terminalContractRequest(h, 12, "measured", "7"); rec.Code != http.StatusNotFound {
		t.Fatalf("MeasuredObjectKey 缺失时必须 404，不能回退 fused，得 %d", rec.Code)
	}
	if len(reader.requestedKeys) != 1 {
		t.Fatalf("MeasuredObjectKey 缺失时不得读取 fused，实际请求 %+v", reader.requestedKeys)
	}
	if rec := terminalContractRequest(h, 13, "measured", "7"); rec.Code != http.StatusConflict {
		t.Fatalf("measured 清单缺失时必须 fail closed，得 %d: %s", rec.Code, rec.Body.String())
	}
	if len(reader.requestedKeys) != 1 {
		t.Fatalf("清单缺失时不得读取对象，实际请求 %+v", reader.requestedKeys)
	}

	corrupt := append([]byte(nil), measuredPCD...)
	corrupt[len(corrupt)-1] ^= 0x01
	reader.blobs[measuredKey] = corrupt
	if rec := terminalContractRequest(h, 11, "measured", "7"); rec.Code != http.StatusBadGateway {
		t.Fatalf("measured 坐标损坏时必须拒绝，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestDatabaseLookupFailureIsNeverReportedAsMissingOrInactive(t *testing.T) {
	dbErr := errors.New("database unavailable")
	jobs := &terminalContractRepo{findErr: dbErr}
	h := NewHandler(Config{}, jobs, nil, nil, nil)
	h.SetCloudReader(&terminalContractCloudReader{})
	h.sessions.set(11, &activeSession{
		jobID: 11, sessionKey: "session-11", owner: 7,
		unitAIP: "192.168.9.101", unitBIP: "192.168.9.102",
		cache: newLivePointCache(),
	})

	tests := []struct {
		name   string
		method string
		path   string
	}{
		{"读取任务", http.MethodGet, "/v1/scans/laser/11"},
		{"停止任务", http.MethodPost, "/v1/scans/laser/11/stop"},
		{"下载终态点云", http.MethodGet, "/v1/scans/laser/11/cloud/measured"},
		{"下载活动点云", http.MethodGet, "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102"},
		{"活动任务恢复", http.MethodGet, "/v1/scans/laser/active?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := terminalContractServe(h, tt.method, tt.path, "7", nil)
			if rec.Code != http.StatusInternalServerError {
				t.Fatalf("DB 故障必须返回 500 并保留客户端任务身份，得 %d: %s", rec.Code, rec.Body.String())
			}
		})
	}
}

type cancelledAtCompleteStore struct {
	markFusingCalls int
	completeCalls   int
	failCalls       int
}

type cancelledAtMarkFusingStore struct {
	markFusingCalls int
	completeCalls   int
	failCalls       int
}

func (s *cancelledAtMarkFusingStore) MarkFusing(context.Context, int64, int, int) (*repo.LaserScanJob, error) {
	s.markFusingCalls++
	return nil, repo.ErrNotFound
}

func (s *cancelledAtMarkFusingStore) Complete(
	context.Context,
	int64,
	repo.LaserScanCompletion,
) (*repo.LaserScanJob, error) {
	s.completeCalls++
	return nil, nil
}

func (s *cancelledAtMarkFusingStore) Fail(context.Context, int64, string) (*repo.LaserScanJob, error) {
	s.failCalls++
	return nil, nil
}

func (s *cancelledAtCompleteStore) MarkFusing(_ context.Context, id int64, _, _ int) (*repo.LaserScanJob, error) {
	s.markFusingCalls++
	return &repo.LaserScanJob{ID: id, Status: repo.LaserScanStatusFusing}, nil
}

func (s *cancelledAtCompleteStore) Complete(
	context.Context,
	int64,
	repo.LaserScanCompletion,
) (*repo.LaserScanJob, error) {
	s.completeCalls++
	return nil, repo.ErrNotFound
}

func (s *cancelledAtCompleteStore) Fail(context.Context, int64, string) (*repo.LaserScanJob, error) {
	s.failCalls++
	return nil, repo.ErrNotFound
}

type terminalContractCloudStore struct{}

func (terminalContractCloudStore) PutCloud(_ context.Context, sessionKey, name string, _ []float32) (string, error) {
	return LaserObjectKey(sessionKey, name), nil
}

func (terminalContractCloudStore) PutMeasuredCloud(
	ctx context.Context,
	sessionKey, name string,
	xyz []float32,
	_ MeasuredCloudArtifact,
) (string, error) {
	return terminalContractCloudStore{}.PutCloud(ctx, sessionKey, name, xyz)
}

func (terminalContractCloudStore) PutCloudXYZI(
	ctx context.Context,
	sessionKey,
	name string,
	xyz,
	_ []float32,
) (string, error) {
	return terminalContractCloudStore{}.PutCloud(ctx, sessionKey, name, xyz)
}

func (terminalContractCloudStore) PutCloudXYZRGB(
	ctx context.Context,
	sessionKey,
	name string,
	xyz []float32,
	_ []uint32,
) (string, error) {
	return terminalContractCloudStore{}.PutCloud(ctx, sessionKey, name, xyz)
}

func (terminalContractCloudStore) PutCloudXYZRGBI(
	ctx context.Context,
	sessionKey,
	name string,
	xyz []float32,
	_ []uint32,
	_ []float32,
) (string, error) {
	return terminalContractCloudStore{}.PutCloud(ctx, sessionKey, name, xyz)
}

type terminalContractPublisher struct {
	topics []string
}

func (p *terminalContractPublisher) Publish(_ context.Context, topic string, _ any) error {
	p.topics = append(p.topics, topic)
	return nil
}

type cancelledBackgroundFinalizer struct {
	calls     int
	activated bool
}

func (f *cancelledBackgroundFinalizer) ActivateAndComplete(
	context.Context,
	int64,
	repo.LaserScanCompletion,
	repo.LaserBackgroundRevision,
) (*repo.LaserScanJob, *repo.LaserBackgroundRevision, error) {
	f.calls++
	return nil, nil, repo.ErrNotFound
}

func terminalContractSuccessfulScan(
	_, _, _, _ string,
	_ float32,
	cb ScanCallbacks,
) (ScanResult, error) {
	cb.OnStatus("scanning", 0, 0)
	cb.OnPoints(PointFrame{Unit: 0, XYZmm: []float32{1, 2, 3}, HAngleDeg: 0})
	cb.OnPoints(PointFrame{Unit: 1, XYZmm: []float32{4, 5, 6}, HAngleDeg: 90})
	cb.OnStatus("fusing", 1, 1)
	cb.OnPoints(PointFrame{Unit: 2, XYZmm: []float32{1, 2, 3, 4, 5, 6}})
	cb.OnStatus("done", 1, 1)
	return ScanResult{
		PtsA:  1,
		PtsB:  1,
		Fused: 2,
		BToA:  [16]float32{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
		Align: "none",
	}, nil
}

func TestRunnerDoesNotPublishDoneWhenCompleteCASRejectsCancelledJob(t *testing.T) {
	jobs := &cancelledAtCompleteStore{}
	publisher := &terminalContractPublisher{}
	runner := NewRunner(jobs, terminalContractCloudStore{}, publisher, nil)
	runner.Replay = terminalContractSuccessfulScan

	job, err := runner.Run(context.Background(), RunSpec{
		JobID:      21,
		SessionKey: "cancelled-before-complete",
		Align:      "none",
		Replay:     true,
	}, nil)
	if job != nil {
		t.Fatalf("Complete CAS 失败时不得返回 done job，得 %+v", job)
	}
	if !errors.Is(err, repo.ErrNotFound) {
		t.Fatalf("已取消任务的 Complete CAS 应返回 ErrNotFound，得 %v", err)
	}
	if jobs.markFusingCalls != 1 || jobs.completeCalls != 1 {
		t.Fatalf("应执行一次 fusing 和一次 Complete CAS，得 fusing=%d complete=%d",
			jobs.markFusingCalls, jobs.completeCalls)
	}
	if jobs.failCalls != 0 {
		t.Fatalf("Complete CAS 失败不得用 failed 覆盖 cancelled，Fail 调用=%d", jobs.failCalls)
	}
	if len(publisher.topics) != 0 {
		t.Fatalf("Complete CAS 失败后绝不能发布 done，实际发布 %+v", publisher.topics)
	}
}

func TestRunnerStopsBeforeUploadingWhenMarkFusingCASRejectsCancelledJob(t *testing.T) {
	jobs := &cancelledAtMarkFusingStore{}
	clouds := &fakeCloudStore{}
	publisher := &terminalContractPublisher{}
	runner := NewRunner(jobs, clouds, publisher, nil)
	runner.Replay = terminalContractSuccessfulScan

	job, err := runner.Run(context.Background(), RunSpec{
		JobID: 23, SessionKey: "cancelled-before-fusing", Align: "none", Replay: true,
	}, nil)
	if job != nil || !errors.Is(err, repo.ErrNotFound) {
		t.Fatalf("MarkFusing CAS 失败应按取消中止，job=%+v err=%v", job, err)
	}
	if jobs.markFusingCalls != 1 || jobs.completeCalls != 0 || jobs.failCalls != 0 {
		t.Fatalf("取消后不得 Complete/Fail，mark=%d complete=%d fail=%d",
			jobs.markFusingCalls, jobs.completeCalls, jobs.failCalls)
	}
	if len(clouds.counts) != 0 {
		t.Fatalf("MarkFusing 失败后不得上传 PCD，实际 %+v", clouds.counts)
	}
	if len(publisher.topics) != 0 {
		t.Fatalf("MarkFusing 失败后不得发布 done，实际 %+v", publisher.topics)
	}
}

func TestCancelledBackgroundCaptureDoesNotActivateRevisionOrPersistGround(t *testing.T) {
	jobs := &cancelledAtCompleteStore{}
	finalizer := &cancelledBackgroundFinalizer{}
	publisher := &terminalContractPublisher{}
	grounds := newFakeGroundStore()
	clouds := newBgFakeStore()
	runner := NewRunner(jobs, clouds, publisher, nil)
	runner.BackgroundFinalizer = finalizer
	runner.Grounds = grounds
	runner.Replay = emitSceneScan(makeRoom(2000, 2000, 2000, 30))

	profileA := backgroundTestProfile("192.168.9.101")
	profileB := backgroundTestProfile("192.168.9.102")
	regionCalibration := RegionCalibrationSnapshot{PointsSHA256: "region-test-revision"}
	regionFilter := backgroundTestRegion(5000)
	job, err := runner.Run(context.Background(), RunSpec{
		JobID:             22,
		SessionKey:        "cancelled-background-before-complete",
		UnitAIP:           profileA.IP,
		UnitBIP:           profileB.IP,
		Align:             "icp",
		Replay:            true,
		MarkAsBackground:  true,
		UnitAProfile:      profileA,
		UnitBProfile:      profileB,
		SiteCalibration:   SiteCalibrationSnapshot{MatrixSHA256: "site-revision"},
		RegionCalibration: regionCalibration,
		RegionFilter:      regionFilter,
	}, nil)
	if job != nil {
		t.Fatalf("背景完成 CAS 失败时不得返回 done job，得 %+v", job)
	}
	if !errors.Is(err, repo.ErrNotFound) {
		t.Fatalf("已取消背景任务应返回 ErrNotFound，得 %v", err)
	}
	if finalizer.calls != 1 || finalizer.activated {
		t.Fatalf("取消竞态不得激活背景，calls=%d activated=%v", finalizer.calls, finalizer.activated)
	}
	if len(grounds.m) != 0 {
		t.Fatalf("取消竞态不得持久化地面，实际 %+v", grounds.m)
	}
	if jobs.completeCalls != 0 || jobs.failCalls != 0 {
		t.Fatalf("原子背景路径不得再调用普通 Complete/Fail，complete=%d fail=%d",
			jobs.completeCalls, jobs.failCalls)
	}
	if len(publisher.topics) != 0 {
		t.Fatalf("取消背景任务不得发布 done，实际 %+v", publisher.topics)
	}
}
