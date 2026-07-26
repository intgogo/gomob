package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/repo"
)

// --- fake LaserRepo（内存）---

type fakeLaserRepo struct {
	mu          sync.Mutex
	seq         int64
	jobs        map[int64]*repo.LaserScanJob
	cancelHook  func()
	latestArgs  [2]string
	latestOwner *int64
	latestErr   error
	latestNil   bool
}

type latestCandidatesRepo struct {
	*fakeLaserRepo
	candidates []*repo.LaserScanJob
	err        error
}

func (r *latestCandidatesRepo) FindLatestMeasurements(
	_ context.Context,
	_, _ string,
	_ *int64,
	_ int,
) ([]*repo.LaserScanJob, error) {
	if r.err != nil {
		return nil, r.err
	}
	result := make([]*repo.LaserScanJob, 0, len(r.candidates))
	for _, job := range r.candidates {
		result = append(result, clone(job))
	}
	return result, nil
}

func newFakeRepo() *fakeLaserRepo { return &fakeLaserRepo{jobs: map[int64]*repo.LaserScanJob{}} }

func (f *fakeLaserRepo) Create(_ context.Context, sk, a, b, align string, keep float32, insp, owner *int64) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seq++
	j := &repo.LaserScanJob{ID: f.seq, SessionKey: sk, UnitAIP: a, UnitBIP: b, Align: align,
		KeepRatio: keep, Status: repo.LaserScanStatusCapturing, InspectionID: insp, OwnerUserID: owner}
	f.jobs[j.ID] = j
	return clone(j), nil
}
func (f *fakeLaserRepo) FindByID(_ context.Context, id int64) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if j, ok := f.jobs[id]; ok {
		return clone(j), nil
	}
	return nil, repo.ErrNotFound
}
func (f *fakeLaserRepo) FindLatestMeasurement(_ context.Context, a, b string, ownerUserID *int64) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.latestArgs = [2]string{a, b}
	f.latestOwner = nil
	if ownerUserID != nil {
		owner := *ownerUserID
		f.latestOwner = &owner
	}
	if f.latestErr != nil {
		return nil, f.latestErr
	}
	if f.latestNil {
		return nil, nil
	}
	var best *repo.LaserScanJob
	for _, j := range f.jobs {
		if j.UnitAIP != a || j.UnitBIP != b || j.Status != repo.LaserScanStatusDone {
			continue
		}
		if ownerUserID != nil && (j.OwnerUserID == nil || *j.OwnerUserID != *ownerUserID) {
			continue
		}
		if j.MeasuredObjectKey == nil || strings.TrimSpace(*j.MeasuredObjectKey) == "" || !jobHasValidMeasurement(j) {
			continue
		}
		if best == nil || j.ID > best.ID {
			best = j
		}
	}
	if best == nil {
		return nil, repo.ErrNotFound
	}
	return clone(best), nil
}

func jobHasValidMeasurement(j *repo.LaserScanJob) bool {
	var stats struct {
		Measure struct {
			Valid bool `json:"valid"`
		} `json:"measure"`
	}
	return j != nil && json.Unmarshal(j.Stats, &stats) == nil && stats.Measure.Valid
}

func markFakeValidMeasurement(t *testing.T, f *fakeLaserRepo, id int64) {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	j := f.jobs[id]
	if j == nil {
		t.Fatalf("扫描 %d 不存在", id)
	}
	key := LaserObjectKey(j.SessionKey, "measured")
	bToA := [16]float32{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}
	bToAJSON, err := json.Marshal(bToA)
	if err != nil {
		t.Fatal(err)
	}
	artifact := newMeasuredCloudArtifact(
		[]float32{1, 2, 3, 4, 5, 6},
		bToA,
		"site-revision",
		"region-revision",
		17,
	)
	j.MeasuredObjectKey = &key
	j.BToA = bToAJSON
	j.Stats = mustJSON(map[string]any{
		"measure":                Dimensions{Valid: true},
		"measured_points":        artifact.SourcePoints,
		"measured_artifact":      artifact,
		"background_revision_id": int64(17),
		"site_calibration":       SiteCalibrationSnapshot{MatrixSHA256: "site-revision"},
		"region_calibration":     RegionCalibrationSnapshot{PointsSHA256: "region-revision"},
	})
}
func (f *fakeLaserRepo) Cancel(_ context.Context, id int64) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.cancelHook != nil {
		f.cancelHook()
	}
	j, ok := f.jobs[id]
	if !ok {
		return nil, repo.ErrNotFound
	}
	if j.Status == repo.LaserScanStatusCapturing || j.Status == repo.LaserScanStatusFusing {
		j.Status = repo.LaserScanStatusCancelled
	}
	return clone(j), nil
}
func (f *fakeLaserRepo) MarkFusing(_ context.Context, id int64, a, b int) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	j := f.jobs[id]
	if j == nil {
		return nil, repo.ErrNotFound
	}
	j.Status = repo.LaserScanStatusFusing
	j.PtsA, j.PtsB = &a, &b
	return clone(j), nil
}
func (f *fakeLaserRepo) Complete(_ context.Context, id int64, c repo.LaserScanCompletion) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	j := f.jobs[id]
	if j == nil {
		return nil, repo.ErrNotFound
	}
	j.Status = repo.LaserScanStatusDone
	j.AlignMethod = &c.AlignMethod
	j.PtsA = &c.PtsA
	j.PtsB = &c.PtsB
	j.Fused = &c.Fused
	j.AfterCrop = &c.AfterCrop
	j.FusedObjectKey = &c.FusedObjectKey
	j.UnitAObjectKey = &c.UnitAObjectKey
	j.UnitBObjectKey = &c.UnitBObjectKey
	if c.MeasuredObjectKey != "" {
		j.MeasuredObjectKey = &c.MeasuredObjectKey
	}
	j.BToA = append(json.RawMessage(nil), c.BToA...)
	j.Stats = append(json.RawMessage(nil), c.Stats...)
	return clone(j), nil
}
func (f *fakeLaserRepo) Fail(_ context.Context, id int64, msg string) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	j := f.jobs[id]
	if j == nil {
		return nil, repo.ErrNotFound
	}
	j.Status = repo.LaserScanStatusFailed
	j.ErrorMessage = &msg
	return clone(j), nil
}

func clone(j *repo.LaserScanJob) *repo.LaserScanJob { c := *j; return &c }

type fakeSiteCalibrationStore struct {
	mu      sync.Mutex
	values  map[[2]string]repo.LaserSiteCalibration
	getErr  error
	putErr  error
	upserts int
}

type fakeRegionCalibrationStore struct {
	mu     sync.Mutex
	values map[[2]string]repo.LaserRegionCalibration
}

type fakeInspectionStore struct {
	values map[int64]repo.Inspection
	err    error
}

func newFakeInspectionStore() *fakeInspectionStore {
	return &fakeInspectionStore{values: map[int64]repo.Inspection{}}
}

func (f *fakeInspectionStore) FindByID(_ context.Context, id int64) (*repo.Inspection, error) {
	if f.err != nil {
		return nil, f.err
	}
	value, ok := f.values[id]
	if !ok {
		return nil, repo.ErrNotFound
	}
	copy := value
	return &copy, nil
}

func newFakeRegionCalibrationStore() *fakeRegionCalibrationStore {
	return &fakeRegionCalibrationStore{values: map[[2]string]repo.LaserRegionCalibration{}}
}

func (f *fakeRegionCalibrationStore) Get(_ context.Context, a, b string) (*repo.LaserRegionCalibration, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	value, ok := f.values[[2]string{a, b}]
	if !ok {
		return nil, repo.ErrNotFound
	}
	copy := value
	copy.Points = append(json.RawMessage(nil), value.Points...)
	return &copy, nil
}

func (f *fakeRegionCalibrationStore) Upsert(_ context.Context, value repo.LaserRegionCalibration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	value.Points = append(json.RawMessage(nil), value.Points...)
	value.UpdatedAt = time.Now().UTC()
	f.values[[2]string{value.UnitAIP, value.UnitBIP}] = value
	return nil
}

func (f *fakeRegionCalibrationStore) Delete(_ context.Context, a, b string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := [2]string{a, b}
	if _, ok := f.values[key]; !ok {
		return repo.ErrNotFound
	}
	delete(f.values, key)
	return nil
}

type fakeBackgroundRevisionStore struct {
	mu       sync.Mutex
	seq      int64
	active   map[[2]string]repo.LaserBackgroundRevision
	complete func(context.Context, int64, repo.LaserScanCompletion) (*repo.LaserScanJob, error)
}

func newFakeBackgroundRevisionStore() *fakeBackgroundRevisionStore {
	return &fakeBackgroundRevisionStore{active: map[[2]string]repo.LaserBackgroundRevision{}}
}

func (f *fakeBackgroundRevisionStore) GetActive(_ context.Context, a, b string) (*repo.LaserBackgroundRevision, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	value, ok := f.active[[2]string{a, b}]
	if !ok {
		return nil, repo.ErrNotFound
	}
	copy := value
	return &copy, nil
}

func (f *fakeBackgroundRevisionStore) Activate(_ context.Context, value repo.LaserBackgroundRevision) (*repo.LaserBackgroundRevision, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seq++
	value.ID = f.seq
	value.Active = true
	f.active[[2]string{value.UnitAIP, value.UnitBIP}] = value
	copy := value
	return &copy, nil
}

func (f *fakeBackgroundRevisionStore) ActivateAndComplete(
	ctx context.Context,
	jobID int64,
	completion repo.LaserScanCompletion,
	value repo.LaserBackgroundRevision,
) (*repo.LaserScanJob, *repo.LaserBackgroundRevision, error) {
	activated, err := f.Activate(ctx, value)
	if err != nil {
		return nil, nil, err
	}
	if f.complete == nil {
		return &repo.LaserScanJob{ID: jobID, Status: repo.LaserScanStatusDone}, activated, nil
	}
	job, err := f.complete(ctx, jobID, completion)
	if err != nil {
		return nil, nil, err
	}
	return job, activated, nil
}

func newFakeSiteCalibrationStore() *fakeSiteCalibrationStore {
	return &fakeSiteCalibrationStore{values: map[[2]string]repo.LaserSiteCalibration{}}
}

func (f *fakeSiteCalibrationStore) Get(_ context.Context, unitAIP, unitBIP string) (*repo.LaserSiteCalibration, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.getErr != nil {
		return nil, f.getErr
	}
	cal, ok := f.values[[2]string{unitAIP, unitBIP}]
	if !ok {
		return nil, repo.ErrNotFound
	}
	copy := cal
	copy.SiteJSON = append(json.RawMessage(nil), cal.SiteJSON...)
	return &copy, nil
}

func (f *fakeSiteCalibrationStore) Upsert(_ context.Context, cal repo.LaserSiteCalibration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.putErr != nil {
		return f.putErr
	}
	cal.SiteJSON = append(json.RawMessage(nil), cal.SiteJSON...)
	f.values[[2]string{cal.UnitAIP, cal.UnitBIP}] = cal
	f.upserts++
	return nil
}

type fakeGate struct{ started, stopped bool }

func (g *fakeGate) Start(context.Context) error { g.started = true; return nil }
func (g *fakeGate) Stop(context.Context) error  { g.stopped = true; return nil }

type fakeProber struct{ res ProbeResult }

func (p fakeProber) Probe(context.Context, string) ProbeResult { return p.res }

type fakeDeviceAPI struct {
	updated       ControlSettings
	info          *DeviceInfo
	commands      []ScanCmd
	controlWrites int
	calibWrites   int
}

func (f *fakeDeviceAPI) GetStatus(context.Context) (*DeviceStatus, error) {
	return &DeviceStatus{}, nil
}
func (f *fakeDeviceAPI) GetInfo(context.Context) (*DeviceInfo, error) {
	if f.info != nil {
		return f.info, nil
	}
	return &DeviceInfo{Control: ControlSettings{ScanStartAngle: 0, ScanStopAngle: 90}}, nil
}
func (f *fakeDeviceAPI) ControlScan(_ context.Context, cmd ScanCmd) error {
	f.commands = append(f.commands, cmd)
	return nil
}
func (f *fakeDeviceAPI) UpdateControl(_ context.Context, s ControlSettings) error {
	f.updated = s
	f.controlWrites++
	return nil
}
func (f *fakeDeviceAPI) UpdateCalib(context.Context, CalibParams) error {
	f.calibWrites++
	return nil
}

// 组装一个全 fake、launch 同步的 handler。
func newTestHandler(t *testing.T, launchSync bool) (*Handler, *fakeLaserRepo, *fakePublisher, *fakeCloudStore) {
	t.Helper()
	fr := newFakeRepo()
	clouds := &fakeCloudStore{}
	pub := &fakePublisher{}
	runner := NewRunner(fr, clouds, pub, nil)
	runner.Live = fakeScan
	runner.Replay = fakeScan
	h := NewHandler(Config{StationID: 9}, fr, runner, pub, nil)
	h.SetInspectionStore(newFakeInspectionStore())
	siteCalib := newFakeSiteCalibrationStore()
	siteRMS := 3.2
	siteMarkers := 6
	_ = siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", SiteJSON: json.RawMessage(testSiteJSON), Source: "test",
		RMSErrorMM: &siteRMS, CommonMarkers: &siteMarkers,
	})
	h.SetSiteCalibrationStore(siteCalib)
	regions := newFakeRegionCalibrationStore()
	defaultRegion, _ := json.Marshal([][3]float32{
		{-1_000_000, -1_000_000, 0}, {1_000_000, -1_000_000, 0},
		{1_000_000, 1_000_000, 0}, {-1_000_000, 1_000_000, 0},
	})
	_ = regions.Upsert(context.Background(), repo.LaserRegionCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", Enabled: true,
		Points: defaultRegion, Source: "test",
	})
	h.SetRegionCalibrationStore(regions)
	backgrounds := newFakeBackgroundRevisionStore()
	backgrounds.complete = fr.Complete
	h.SetBackgroundRevisionStore(backgrounds)
	runner.BackgroundFinalizer = backgrounds
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady, Model: "LTS-T1"}}
	h.newGate = func(a, b string) DeviceGate { return &fakeGate{} }
	deviceInfo := DeviceInfo{Control: ControlSettings{ScanStartAngle: 0, ScanStopAngle: 90}}
	h.newDev = func(string) DeviceAPI {
		copy := deviceInfo
		return &fakeDeviceAPI{info: &copy}
	}
	profileA := newUnitAcquisitionProfile("192.168.9.101", deviceInfo, 0, 90, 1, false)
	profileB := newUnitAcquisitionProfile("192.168.9.102", deviceInfo, 0, 90, 1, false)
	keyA, keyB := "background/test/unit_a.pcd", "background/test/unit_b.pcd"
	checksumA, checksumB := "checksum-a", "checksum-b"
	deviceHashA, deviceHashB := profileA.DeviceConfigSHA256, profileB.DeviceConfigSHA256
	scanHashA, scanHashB := profileA.ScanConfigSHA256, profileB.ScanConfigSHA256
	siteRevision, err := canonicalSiteSHA256(testSiteJSON)
	if err != nil {
		t.Fatal(err)
	}
	_, regionSnapshot, err := h.resolveRegionCalibration(
		context.Background(), "192.168.9.101", "192.168.9.102", "raw", "", nil,
	)
	if err != nil {
		t.Fatal(err)
	}
	backgrounds.active[[2]string{"192.168.9.101", "192.168.9.102"}] = repo.LaserBackgroundRevision{
		ID: 1, UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteRevision: &siteRevision, RegionRevision: &regionSnapshot.PointsSHA256,
		UnitAObjectKey: &keyA, UnitBObjectKey: &keyB, UnitAPoints: 100, UnitBPoints: 100,
		UnitAChecksum: &checksumA, UnitBChecksum: &checksumB,
		UnitAIdentity: profileA.identityJSON(), UnitBIdentity: profileB.identityJSON(),
		UnitADeviceConfigHash: &deviceHashA, UnitBDeviceConfigHash: &deviceHashB,
		UnitAScanConfigHash: &scanHashA, UnitBScanConfigHash: &scanHashB,
		CoordinateSchema: repo.LaserBackgroundSchemaRegionCroppedUnitV1, Active: true,
	}
	if launchSync {
		h.launch = func(f func()) { f() }
	}
	return h, fr, pub, clouds
}

func seedCompatibleBackground(t *testing.T, h *Handler, a, b string) {
	t.Helper()
	store, ok := h.backgrounds.(*fakeBackgroundRevisionStore)
	if !ok {
		t.Fatal("测试背景 store 类型错误")
	}
	profileA, _, err := h.acquisitionProfile(context.Background(), a, "A", h.cfg.DefaultKeep)
	if err != nil {
		t.Fatal(err)
	}
	profileB, _, err := h.acquisitionProfile(context.Background(), b, "B", h.cfg.DefaultKeep)
	if err != nil {
		t.Fatal(err)
	}
	keyA, keyB := "background/"+a+"/unit_a.pcd", "background/"+b+"/unit_b.pcd"
	checksumA, checksumB := "checksum-a", "checksum-b"
	deviceHashA, deviceHashB := profileA.DeviceConfigSHA256, profileB.DeviceConfigSHA256
	scanHashA, scanHashB := profileA.ScanConfigSHA256, profileB.ScanConfigSHA256
	siteRevision, err := h.currentSiteRevision(context.Background(), a, b)
	if err != nil {
		t.Fatal(err)
	}
	_, regionSnapshot, err := h.resolveRegionCalibration(context.Background(), a, b, "raw", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	store.active[[2]string{a, b}] = repo.LaserBackgroundRevision{
		ID: 1, UnitAIP: a, UnitBIP: b,
		SiteRevision: &siteRevision, RegionRevision: &regionSnapshot.PointsSHA256,
		UnitAObjectKey: &keyA, UnitBObjectKey: &keyB, UnitAPoints: 100, UnitBPoints: 100,
		UnitAChecksum: &checksumA, UnitBChecksum: &checksumB,
		UnitAIdentity: profileA.identityJSON(), UnitBIdentity: profileB.identityJSON(),
		UnitADeviceConfigHash: &deviceHashA, UnitBDeviceConfigHash: &deviceHashB,
		UnitAScanConfigHash: &scanHashA, UnitBScanConfigHash: &scanHashB,
		CoordinateSchema: repo.LaserBackgroundSchemaRegionCroppedUnitV1, Active: true,
	}
}

func do(h *Handler, method, target, body string, uid string) *httptest.ResponseRecorder {
	return doAs(h, method, target, body, uid, "")
}

func doAs(h *Handler, method, target, body, uid, roles string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	if uid != "" {
		req.Header.Set("X-Gomob-User-Id", uid)
	}
	if roles != "" {
		req.Header.Set("X-Gomob-Roles", roles)
	}
	mux := http.NewServeMux()
	h.Mount(mux)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

const testSiteJSON = `{"b_to_a":[1,0,0,0.1,0,1,0,0.2,0,0,1,0.3,0,0,0,1]}`

func siteStartBody(extra string) string {
	body := `{"align":"site","site_json":` + strconv.Quote(testSiteJSON)
	if strings.TrimSpace(extra) != "" {
		body += "," + strings.TrimSpace(extra)
	}
	return body + "}"
}

func TestStartScanHappy(t *testing.T) {
	h, fr, pub, clouds := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("期望 201，得 %d: %s", rec.Code, rec.Body.String())
	}
	var resp startResp
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.ScanID == 0 || resp.SessionKey == "" || resp.Status != "capturing" {
		t.Fatalf("响应错: %+v", resp)
	}
	// 同步 launch ⇒ 扫描已跑完。
	j, _ := fr.FindByID(context.Background(), resp.ScanID)
	if j.Status != repo.LaserScanStatusDone {
		t.Errorf("job 应 done，得 %s", j.Status)
	}
	if clouds.counts["fused"] != 390 {
		t.Errorf("融合云点数错: %+v", clouds.counts)
	}
	if len(pub.events) != 1 || pub.events[0].Kind != "laser" {
		t.Errorf("应发 1 条 laser 完成事件，得 %+v", pub.events)
	}
	// 跑完后单活名额应释放：再起一次应成功。
	rec2 := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec2.Code != http.StatusCreated {
		t.Errorf("名额应已释放，第二次期望 201，得 %d", rec2.Code)
	}
}

func TestStartScanSiteJSONPassesToRunner(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotAlign, gotSite, gotSiteBody string
	h.runner.Live = func(a, b, align, site string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		gotAlign = align
		gotSite = site
		body, err := os.ReadFile(site)
		if err != nil {
			t.Fatalf("site_json 应落成 native 可读取文件: %v", err)
		}
		gotSiteBody = strings.TrimSpace(string(body))
		return fakeScan(a, b, align, site, keep, cb)
	}
	site := `{"b_to_a":[1,0,0,0.1,0,1,0,0.2,0,0,1,0.3,0,0,0,1]}`
	body, _ := json.Marshal(map[string]any{
		"align":     "site",
		"site_json": site,
	})
	rec := do(h, "POST", "/v1/scans/laser", string(body), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("site 起扫应 201，得 %d: %s", rec.Code, rec.Body.String())
	}
	if gotAlign != "site" || gotSite == "" || gotSite == site || !filepath.IsAbs(gotSite) || gotSiteBody != site {
		t.Fatalf("site_json 未正确传给 native: align=%q path=%q body=%q", gotAlign, gotSite, gotSiteBody)
	}
}

func TestStartScanUnauthorized(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	if rec := do(h, "POST", "/v1/scans/laser", `{}`, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("无鉴权应 401，得 %d", rec.Code)
	}
}

func TestStartScanRejectsClientKeepRatioDivergence(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"keep_ratio":0.5`), "7")
	if rec.Code != http.StatusConflict {
		t.Fatalf("客户端保留率偏离服务端配置应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("配置冲突不得创建扫描任务，实际 seq=%d", fr.seq)
	}
}

func TestBackgroundUsesServerKeepRatio(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	rec := do(h, "GET", "/v1/scans/laser/background", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("背景状态应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["effective_keep_ratio"] != float64(1) {
		t.Fatalf("应返回服务端保留率 1，得 %+v", payload)
	}
	if payload["region_revision"] == "" || payload["background_region_revision"] != payload["region_revision"] {
		t.Fatalf("背景状态必须返回一致的区域 revision，得 %+v", payload)
	}

	rec = do(h, "GET", "/v1/scans/laser/background?keep_ratio=0.5", "", "7")
	if rec.Code != http.StatusConflict {
		t.Fatalf("旧客户端保留率不一致应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBackgroundCaptureRequiresEnabledRegionBeforeCreatingJob(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	regions := h.regionCalib.(*fakeRegionCalibrationStore)
	delete(regions.values, [2]string{"192.168.9.101", "192.168.9.102"})

	rec := doAs(
		h,
		"POST",
		"/v1/scans/laser",
		siteStartBody(`"mark_as_background":true`),
		"7",
		"admin",
	)
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "扫描区域") {
		t.Fatalf("缺少区域时背景采集应起扫前 409，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("缺少区域时不得创建背景任务，实际 seq=%d", fr.seq)
	}
}

func TestStartScanRejectsMissingCompatibleBackgroundBeforeCapture(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	backgrounds := h.backgrounds.(*fakeBackgroundRevisionStore)
	delete(backgrounds.active, [2]string{"192.168.9.101", "192.168.9.102"})

	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "background_incompatible") {
		t.Fatalf("缺少兼容背景应起扫前 409，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("先验不可测时不得创建任务，实际 seq=%d", fr.seq)
	}
}

func TestStartScanAcceptsLegacyFusedBackgroundForExistingStation(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, false)
	backgrounds := h.backgrounds.(*fakeBackgroundRevisionStore)
	legacyKey := "laser-scans/legacy/background/fused.pcd"
	profileA, _, err := h.acquisitionProfile(context.Background(), "192.168.9.101", "A", h.cfg.DefaultKeep)
	if err != nil {
		t.Fatal(err)
	}
	profileB, _, err := h.acquisitionProfile(context.Background(), "192.168.9.102", "B", h.cfg.DefaultKeep)
	if err != nil {
		t.Fatal(err)
	}
	siteRevision, err := h.currentSiteRevision(context.Background(), profileA.IP, profileB.IP)
	if err != nil {
		t.Fatal(err)
	}
	_, regionSnapshot, err := h.resolveRegionCalibration(context.Background(), profileA.IP, profileB.IP, "raw", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	legacyChecksum := "fused-checksum"
	backgrounds.active[[2]string{"192.168.9.101", "192.168.9.102"}] = repo.LaserBackgroundRevision{
		ID: 1, UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		LegacyFusedObjectKey:  &legacyKey,
		LegacyFusedPoints:     100,
		LegacyFusedChecksum:   &legacyChecksum,
		CompatibilitySite:     &siteRevision,
		CompatibilityRegion:   &regionSnapshot.PointsSHA256,
		CompatibilityEvidence: legacyTestEvidence(100, legacyChecksum),
		UnitAIdentity:         profileA.identityJSON(),
		UnitBIdentity:         profileB.identityJSON(),
		UnitADeviceConfigHash: &profileA.DeviceConfigSHA256,
		UnitBDeviceConfigHash: &profileB.DeviceConfigSHA256,
		UnitAScanConfigHash:   &profileA.ScanConfigSHA256,
		UnitBScanConfigHash:   &profileB.ScanConfigSHA256,
		CoordinateSchema:      repo.LaserBackgroundSchemaLegacyVerifiedFused,
		Active:                true,
	}
	// 只验证起扫前兼容门，不执行 fake 采集。
	h.launch = func(func()) {}

	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("已有同工位 legacy 融合背景不应被强制重采，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 1 {
		t.Fatalf("兼容 legacy 背景应创建扫描任务，实际 seq=%d", fr.seq)
	}
}

func TestStartScanRejectsSiteWithoutProductionQuality(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	_ = h.siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteJSON: json.RawMessage(testSiteJSON), Source: "legacy_browser",
	})

	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "质量") {
		t.Fatalf("无质量证据 site 应起扫前 409，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("site 质量不合格时不得创建任务，实际 seq=%d", fr.seq)
	}
}

func TestStartScanUnverifiedSiteOverrideRequiresExactCanonicalRevision(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	h.cfg.UnverifiedSiteRevision = strings.Repeat("0", 64)
	_ = h.siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteJSON: json.RawMessage(testSiteJSON), Source: "legacy_browser",
	})

	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "质量") {
		t.Fatalf("豁免 revision 不匹配应起扫前 409，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("revision 不匹配不得创建任务，实际 seq=%d", fr.seq)
	}
}

func TestGetSiteCalibrationReportsExactRevisionOverride(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	revision, err := canonicalSiteSHA256(testSiteJSON)
	if err != nil {
		t.Fatal(err)
	}
	h.cfg.UnverifiedSiteRevision = revision
	_ = h.siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteJSON: json.RawMessage(testSiteJSON), Source: "legacy_browser",
	})

	get := func() map[string]any {
		rec := do(h, "GET", "/v1/scans/laser/site-calibration?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7")
		if rec.Code != http.StatusOK {
			t.Fatalf("查询工位外参失败: %d %s", rec.Code, rec.Body.String())
		}
		var payload map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
			t.Fatal(err)
		}
		return payload
	}

	payload := get()
	if payload["revision"] != revision || payload["site_quality_state"] != "override" ||
		payload["site_quality_verified"] != false || payload["site_quality_override"] != true ||
		payload["site_quality_override_reason"] != "legacy_missing_evidence" ||
		payload["scan_eligible"] != true || payload["production_eligible"] != false {
		t.Fatalf("精确 revision 豁免状态错误: %+v", payload)
	}

	h.cfg.UnverifiedSiteRevision = strings.Repeat("0", 64)
	payload = get()
	if payload["site_quality_state"] != "missing_evidence" || payload["site_quality_override"] != false ||
		payload["scan_eligible"] != false || payload["production_eligible"] != false {
		t.Fatalf("revision 不匹配时不应报告可起扫: %+v", payload)
	}
}

func TestStartScanUnverifiedSiteOverrideCannotHideRealQualityFailure(t *testing.T) {
	revision, err := canonicalSiteSHA256(testSiteJSON)
	if err != nil {
		t.Fatal(err)
	}
	highRMS := 5.1
	fewMarkers := 3
	nanRMS := math.NaN()
	tests := []struct {
		name    string
		rms     *float64
		markers *int
	}{
		{name: "RMS 超限且 common 缺失", rms: &highRMS},
		{name: "common 不足且 RMS 缺失", markers: &fewMarkers},
		{name: "RMS 非有限数", rms: &nanRMS},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h, fr, _, _ := newTestHandler(t, true)
			h.cfg.UnverifiedSiteRevision = revision
			_ = h.siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
				UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
				SiteJSON: json.RawMessage(testSiteJSON), Source: "legacy_browser",
				RMSErrorMM: tc.rms, CommonMarkers: tc.markers,
			})

			rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
			if rec.Code != http.StatusConflict {
				t.Fatalf("真实质量不达标不得豁免，得 %d: %s", rec.Code, rec.Body.String())
			}
			if fr.seq != 0 {
				t.Fatalf("真实质量不达标不得创建任务，实际 seq=%d", fr.seq)
			}
		})
	}
}

func TestStartScanUnverifiedSiteOverrideMarksAuditRESTWSAndCompliance(t *testing.T) {
	h, fr, pub, _ := newTestHandler(t, true)
	revision, err := canonicalSiteSHA256(testSiteJSON)
	if err != nil {
		t.Fatal(err)
	}
	h.cfg.UnverifiedSiteRevision = revision
	recorder := &audit.InMemory{}
	h.SetAuditRecorder(recorder)
	_ = h.siteCalib.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteJSON: json.RawMessage(testSiteJSON), Source: "legacy_browser",
	})

	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("正确 canonical revision 应允许联调起扫，得 %d: %s", rec.Code, rec.Body.String())
	}
	var started startResp
	if err := json.Unmarshal(rec.Body.Bytes(), &started); err != nil {
		t.Fatal(err)
	}
	waitAuditCount(t, recorder, 1)
	entry, ok := recorder.EntryAt(0)
	if !ok || entry.Action != "laser.site_quality_override_scan" || entry.UserID != 7 || !strings.Contains(entry.AfterRaw, revision) {
		t.Fatalf("豁免起扫审计缺失: %+v", entry)
	}

	job, err := fr.FindByID(context.Background(), started.ScanID)
	if err != nil {
		t.Fatal(err)
	}
	var stats struct {
		SiteCalibration SiteCalibrationSnapshot `json:"site_calibration"`
		Compliance      Compliance              `json:"compliance"`
	}
	if err := json.Unmarshal(job.Stats, &stats); err != nil {
		t.Fatal(err)
	}
	if stats.SiteCalibration.QualityVerified == nil || *stats.SiteCalibration.QualityVerified ||
		stats.SiteCalibration.QualityOverride != "legacy_missing_evidence" {
		t.Fatalf("豁免快照错误: %+v", stats.SiteCalibration)
	}
	if stats.Compliance.Determined || stats.Compliance.Compliant || len(stats.Compliance.Violations) != 0 ||
		stats.Compliance.Reason != "site_quality_unverified" {
		t.Fatalf("豁免任务合规必须未判定: %+v", stats.Compliance)
	}

	if len(pub.events) != 1 {
		t.Fatalf("应发布一条完成事件，得 %d", len(pub.events))
	}
	evt := pub.events[0]
	if evt.SiteQualityVerified || !evt.SiteQualityOverride || evt.ProductionEligible ||
		evt.SiteQualityOverrideReason != "legacy_missing_evidence" || evt.ComplianceReason != "site_quality_unverified" {
		t.Fatalf("WS 豁免/合规标记错误: %+v", evt)
	}
	view := jobView(job)
	if view["site_quality_verified"] != false || view["site_quality_override"] != true ||
		view["site_quality_override_reason"] != "legacy_missing_evidence" || view["production_eligible"] != false ||
		view["compliance_determined"] != false || view["compliance_reason"] != "site_quality_unverified" {
		t.Fatalf("REST 豁免/合规标记错误: %+v", view)
	}
}

func TestStartScanBusy(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.launch = func(f func()) {} // 丢弃：占住单活名额不释放
	if rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7"); rec.Code != http.StatusCreated {
		t.Fatalf("首次应 201，得 %d", rec.Code)
	}
	if rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7"); rec.Code != http.StatusConflict {
		t.Fatalf("占用中应 409，得 %d", rec.Code)
	}
}

func TestStartScanRejectsUnmanagedStationPair(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"unit_a_ip":"192.168.9.150","unit_b_ip":"192.168.9.151"`), "7")
	if rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), "受管") {
		t.Fatalf("任意 IP 工位必须在触碰设备前拒绝，得 %d: %s", rec.Code, rec.Body.String())
	}
	if fr.seq != 0 {
		t.Fatalf("未受管工位不得创建任务，实际 seq=%d", fr.seq)
	}
}

func TestStartScanValidatesInspectionAuthorityStationAndState(t *testing.T) {
	t.Run("owner managed station", func(t *testing.T) {
		h, fr, _, _ := newTestHandler(t, true)
		store := h.inspections.(*fakeInspectionStore)
		store.values[101] = repo.Inspection{ID: 101, InspectorID: 7, StationID: 9, Status: "scanning"}
		rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"inspection_id":101`), "7")
		if rec.Code != http.StatusCreated {
			t.Fatalf("本人本工位进行中查验应可绑定，得 %d: %s", rec.Code, rec.Body.String())
		}
		job, err := fr.FindByID(context.Background(), 1)
		if err != nil || job.InspectionID == nil || *job.InspectionID != 101 {
			t.Fatalf("任务未绑定权威查验单: job=%+v err=%v", job, err)
		}
	})

	tests := []struct {
		name       string
		inspection repo.Inspection
		roles      string
		want       int
		message    string
	}{
		{"other owner", repo.Inspection{ID: 102, InspectorID: 8, StationID: 9, Status: "scanning"}, "", http.StatusForbidden, "他人"},
		{"wrong station", repo.Inspection{ID: 103, InspectorID: 7, StationID: 10, Status: "scanning"}, "", http.StatusConflict, "不属于"},
		{"closed", repo.Inspection{ID: 104, InspectorID: 7, StationID: 9, Status: "closed"}, "", http.StatusConflict, "状态"},
		{"admin still bound to station", repo.Inspection{ID: 105, InspectorID: 8, StationID: 10, Status: "scanning"}, "admin", http.StatusConflict, "不属于"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h, fr, _, _ := newTestHandler(t, true)
			h.inspections.(*fakeInspectionStore).values[tc.inspection.ID] = tc.inspection
			rec := doAs(h, "POST", "/v1/scans/laser", siteStartBody(`"inspection_id":`+strconv.FormatInt(tc.inspection.ID, 10)), "7", tc.roles)
			if rec.Code != tc.want || !strings.Contains(rec.Body.String(), tc.message) {
				t.Fatalf("期望 %d/%q，得 %d: %s", tc.want, tc.message, rec.Code, rec.Body.String())
			}
			if fr.seq != 0 {
				t.Fatalf("无权/错工位/错状态不得创建任务，实际 seq=%d", fr.seq)
			}
		})
	}

	t.Run("missing and database failure", func(t *testing.T) {
		h, fr, _, _ := newTestHandler(t, true)
		if rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"inspection_id":999`), "7"); rec.Code != http.StatusNotFound {
			t.Fatalf("不存在查验单应 404，得 %d: %s", rec.Code, rec.Body.String())
		}
		h.inspections.(*fakeInspectionStore).err = errors.New("database unavailable")
		if rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"inspection_id":999`), "7"); rec.Code != http.StatusInternalServerError {
			t.Fatalf("查验库故障应 500，得 %d: %s", rec.Code, rec.Body.String())
		}
		if fr.seq != 0 {
			t.Fatalf("查验校验失败不得创建任务，实际 seq=%d", fr.seq)
		}
	})

	t.Run("worker station binding required", func(t *testing.T) {
		h, fr, _, _ := newTestHandler(t, true)
		h.cfg.StationID = 0
		h.inspections.(*fakeInspectionStore).values[106] = repo.Inspection{ID: 106, InspectorID: 7, StationID: 9, Status: "created"}
		rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"inspection_id":106`), "7")
		if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "station_id") {
			t.Fatalf("worker 未绑定工位必须 503，得 %d: %s", rec.Code, rec.Body.String())
		}
		if fr.seq != 0 {
			t.Fatalf("worker 工位未配置不得创建任务，实际 seq=%d", fr.seq)
		}
	})
}

func TestActiveScanRestoresManagedStation(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.launch = func(f func()) {} // 保持活动会话，模拟扫描中刷新网页
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("起扫应 201，得 %d: %s", rec.Code, rec.Body.String())
	}
	var start startResp
	_ = json.Unmarshal(rec.Body.Bytes(), &start)

	rec = do(h, "GET", "/v1/scans/laser/active?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("活动扫描应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	var active map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &active)
	if active["active"] != true || int64(active["scan_id"].(float64)) != start.ScanID || active["session_key"] != start.SessionKey {
		t.Fatalf("活动扫描视图错: %+v", active)
	}
	if active["unit_a_ip"] != "192.168.9.101" || active["unit_b_ip"] != "192.168.9.102" {
		t.Fatalf("活动扫描 IP 错: %+v", active)
	}

	rec = do(h, "GET", "/v1/scans/laser/active?unit_a_ip=192.168.9.250&unit_b_ip=192.168.9.251", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("其它工位应 200 inactive，得 %d", rec.Code)
	}
	active = map[string]any{}
	_ = json.Unmarshal(rec.Body.Bytes(), &active)
	if active["active"] != false {
		t.Fatalf("其它工位不应命中活动扫描: %+v", active)
	}

	if rec := do(h, "GET", "/v1/scans/laser/active?unit_a_ip=bad", "", "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 IP 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/active?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "99"); rec.Code != http.StatusForbidden {
		t.Fatalf("他人查看活动扫描应 403，得 %d", rec.Code)
	}
}

func TestLatestScanUsesDefaultStation(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	startRec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if startRec.Code != http.StatusCreated {
		t.Fatalf("起扫应 201，得 %d: %s", startRec.Code, startRec.Body.String())
	}
	var start startResp
	_ = json.Unmarshal(startRec.Body.Bytes(), &start)
	markFakeValidMeasurement(t, fr, start.ScanID)

	rec := do(h, "GET", "/v1/scans/laser/latest", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("最近扫描应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	var latest map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &latest)
	if latest["found"] != true || int64(latest["scan_id"].(float64)) != start.ScanID || int(latest["points"].(float64)) != 390 {
		t.Fatalf("最近扫描视图错: %+v", latest)
	}
	if fr.latestArgs != [2]string{"192.168.9.101", "192.168.9.102"} {
		t.Fatalf("无查询参数应使用默认工位，得 %+v", fr.latestArgs)
	}
	if fr.latestOwner == nil || *fr.latestOwner != 7 {
		t.Fatalf("普通用户 latest 应在仓储层按 owner=7 过滤，得 %+v", fr.latestOwner)
	}

	rec = do(h, "GET", "/v1/scans/laser/latest?unit_a_ip=192.168.9.101", "", "7")
	if rec.Code != http.StatusOK || fr.latestArgs != [2]string{"192.168.9.101", "192.168.9.102"} {
		t.Fatalf("只传 A 时 B 应使用默认值，code=%d args=%+v", rec.Code, fr.latestArgs)
	}
}

func TestLatestScanReturnsLatestVisibleValidMeasurement(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner7, owner8 := int64(7), int64(8)
	invalidStats := mustJSON(map[string]any{"measure": Dimensions{Valid: false}})
	measured := "laser-scans/valid/measured.pcd"
	owner7Valid, _ := canonicalMeasuredJob(t, 2, "owner-7-valid", &owner7)
	owner8Valid, _ := canonicalMeasuredJob(t, 3, "owner-8-valid", &owner8)
	fr.mu.Lock()
	fr.jobs = map[int64]*repo.LaserScanJob{
		1: {
			ID: 1, SessionKey: "background", OwnerUserID: &owner7,
			UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", Status: repo.LaserScanStatusDone,
			Stats: mustJSON(map[string]any{"measure": Dimensions{Valid: false}, "measure_mode": "background_captured"}),
		},
		2: owner7Valid,
		3: owner8Valid,
		4: {
			ID: 4, SessionKey: "owner-7-invalid", OwnerUserID: &owner7,
			UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", Status: repo.LaserScanStatusDone,
			MeasuredObjectKey: &measured, Stats: invalidStats,
		},
		5: {
			ID: 5, SessionKey: "other-station", OwnerUserID: &owner7,
			UnitAIP: "192.168.9.150", UnitBIP: "192.168.9.151", Status: repo.LaserScanStatusDone,
			MeasuredObjectKey: &measured, Stats: invalidStats,
		},
	}
	fr.mu.Unlock()

	rec := do(h, "GET", "/v1/scans/laser/latest", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("普通用户 latest 应成功，得 %d: %s", rec.Code, rec.Body.String())
	}
	var got map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got["found"] != true || int64(got["scan_id"].(float64)) != 2 {
		t.Fatalf("应跳过背景、他人和无效尝试，返回 owner7 的 job2，得 %+v", got)
	}
	if fr.latestOwner == nil || *fr.latestOwner != owner7 {
		t.Fatalf("仓储 owner 过滤错误: %+v", fr.latestOwner)
	}

	rec = doAs(h, "GET", "/v1/scans/laser/latest", "", "99", "admin")
	if rec.Code != http.StatusOK {
		t.Fatalf("管理员 latest 应成功，得 %d: %s", rec.Code, rec.Body.String())
	}
	got = map[string]any{}
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if got["found"] != true || int64(got["scan_id"].(float64)) != 3 {
		t.Fatalf("管理员应看到工位最近有效车辆 job3，得 %+v", got)
	}
	if fr.latestOwner != nil {
		t.Fatalf("管理员查询不应加 owner 限制，得 %d", *fr.latestOwner)
	}
}

func TestLatestScanSkipsBrokenManifestAndMissingMeasuredObject(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner := int64(7)
	healthy, healthyPCD := canonicalMeasuredJob(t, 10, "healthy", &owner)

	missingObject := clone(healthy)
	missingObject.ID = 11
	missingObject.SessionKey = "missing-object"
	missingKey := LaserObjectKey(missingObject.SessionKey, "measured")
	missingObject.MeasuredObjectKey = &missingKey

	brokenManifest := clone(healthy)
	brokenManifest.ID = 12
	brokenManifest.SessionKey = "broken-manifest"
	brokenKey := LaserObjectKey(brokenManifest.SessionKey, "measured")
	brokenManifest.MeasuredObjectKey = &brokenKey
	var stats map[string]any
	if err := json.Unmarshal(brokenManifest.Stats, &stats); err != nil {
		t.Fatal(err)
	}
	artifact := stats["measured_artifact"].(map[string]any)
	artifact["source_points"] = float64(999)
	brokenManifest.Stats = mustJSON(stats)

	h.repo = &latestCandidatesRepo{
		fakeLaserRepo: fr,
		candidates:    []*repo.LaserScanJob{brokenManifest, missingObject, healthy},
	}
	healthyKey := *healthy.MeasuredObjectKey
	h.SetCloudReader(memReader{blobs: map[string][]byte{healthyKey: healthyPCD}})

	rec := do(h, "GET", "/v1/scans/laser/latest", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("latest 应跳过损坏候选，得 %d: %s", rec.Code, rec.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["found"] != true || int64(got["scan_id"].(float64)) != healthy.ID {
		t.Fatalf("应返回最近健康任务 %d，得 %+v", healthy.ID, got)
	}
}

func canonicalMeasuredJob(
	t *testing.T,
	id int64,
	sessionKey string,
	owner *int64,
) (*repo.LaserScanJob, []byte) {
	t.Helper()
	xyz := []float32{1, 2, 3, 4, 5, 6}
	bToA := [16]float32{1, 0, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30, 0, 0, 0, 1}
	artifact := newMeasuredCloudArtifact(xyz, bToA, "site-revision", "region-revision", 17)
	pcd, err := EncodeMeasuredPCDBinary(xyz, artifact)
	if err != nil {
		t.Fatal(err)
	}
	bToAJSON, err := json.Marshal(bToA)
	if err != nil {
		t.Fatal(err)
	}
	key := LaserObjectKey(sessionKey, "measured")
	align := "site"
	points := len(xyz) / 3
	return &repo.LaserScanJob{
		ID: id, SessionKey: sessionKey, OwnerUserID: owner,
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		Align: "site", AlignMethod: &align, Status: repo.LaserScanStatusDone,
		Fused: &points, PtsA: &points, PtsB: &points,
		MeasuredObjectKey: &key, BToA: bToAJSON,
		Stats: mustJSON(map[string]any{
			"measure":                Dimensions{LengthMM: 1000, WidthMM: 500, HeightMM: 600, Valid: true},
			"measured_points":        points,
			"measured_artifact":      artifact,
			"background_revision_id": int64(17),
			"site_calibration":       SiteCalibrationSnapshot{MatrixSHA256: "site-revision"},
			"region_calibration":     RegionCalibrationSnapshot{PointsSHA256: "region-revision"},
		}),
	}, pcd
}

func TestJobViewPreservesUnknownBackgroundCompatibility(t *testing.T) {
	cases := []struct {
		name             string
		stats            json.RawMessage
		wantCompatible   any
		wantIncompatible any
	}{
		{
			name:           "历史字段缺失保持未知",
			stats:          mustJSON(map[string]any{"bg_set": true}),
			wantCompatible: nil, wantIncompatible: nil,
		},
		{
			name:           "显式兼容",
			stats:          mustJSON(map[string]any{"bg_set": true, "background_compatible": true}),
			wantCompatible: true, wantIncompatible: false,
		},
		{
			name:           "显式不兼容",
			stats:          mustJSON(map[string]any{"bg_set": true, "background_compatible": false}),
			wantCompatible: false, wantIncompatible: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			view := jobView(&repo.LaserScanJob{ID: 1, SessionKey: "s", Status: repo.LaserScanStatusDone, Stats: tc.stats})
			compatible, hasCompatible := view["background_compatible"]
			incompatible, hasIncompatible := view["background_incompatible"]
			if tc.wantCompatible == nil {
				if hasCompatible || hasIncompatible {
					t.Fatalf("未知兼容性不应伪造布尔值: %+v", view)
				}
				return
			}
			if !hasCompatible || compatible != tc.wantCompatible || !hasIncompatible || incompatible != tc.wantIncompatible {
				t.Fatalf("兼容性拍平错误: %+v", view)
			}
		})
	}
}

func TestJobViewUnverifiedSiteRetainsMeasuredDimensionsButUndeterminesCompliance(t *testing.T) {
	owner := int64(7)
	job, _ := canonicalMeasuredJob(t, 88, "override-result", &owner)
	var stats map[string]any
	if err := json.Unmarshal(job.Stats, &stats); err != nil {
		t.Fatal(err)
	}
	qualityVerified := false
	stats["site_calibration"] = SiteCalibrationSnapshot{
		MatrixSHA256: "site-revision", QualityVerified: &qualityVerified,
		QualityOverride: "legacy_missing_evidence",
	}
	stats["measure"] = Dimensions{LengthMM: 1768, WidthMM: 531, HeightMM: 763, Valid: true}
	stats["compliance"] = Compliance{Reason: "site_quality_unverified"}
	job.Stats = mustJSON(stats)

	view := jobView(job)
	if view["measure_valid"] != true || view["length_mm"] != float32(1768) ||
		view["width_mm"] != float32(531) || view["height_mm"] != float32(763) {
		t.Fatalf("豁免任务应保留 measured 外廓尺寸: %+v", view)
	}
	if view["compliance_determined"] != false || view["compliant"] != false ||
		view["compliance_reason"] != "site_quality_unverified" {
		t.Fatalf("豁免任务不得输出合规结论: %+v", view)
	}
}

func TestLatestScanEmptyErrorAndValidation(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)

	rec := do(h, "GET", "/v1/scans/laser/latest", "", "7")
	if rec.Code != http.StatusOK || strings.TrimSpace(rec.Body.String()) != `{"found":false}` {
		t.Fatalf("无扫描应返回 found=false，得 %d: %s", rec.Code, rec.Body.String())
	}
	if rec := do(h, "GET", "/v1/scans/laser/latest?unit_a_ip=bad", "", "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 IP 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/latest", "", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("无鉴权应 401，得 %d", rec.Code)
	}

	fr.latestErr = errors.New("database unavailable")
	if rec := do(h, "GET", "/v1/scans/laser/latest", "", "7"); rec.Code != http.StatusInternalServerError {
		t.Fatalf("仓储异常应 500，得 %d: %s", rec.Code, rec.Body.String())
	}
	fr.latestErr = nil
	fr.latestNil = true
	if rec := do(h, "GET", "/v1/scans/laser/latest", "", "7"); rec.Code != http.StatusInternalServerError {
		t.Fatalf("仓储违约返回 nil,nil 应 500，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanProbeFail(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.probe = fakeProber{res: ProbeResult{Reachable: false, Err: "connection refused"}}
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("探活失败应 502，得 %d: %s", rec.Code, rec.Body.String())
	}
	// 失败应释放名额：再探活通过则可起。
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady}}
	if rec := do(h, "POST", "/v1/scans/laser", siteStartBody(""), "7"); rec.Code != http.StatusCreated {
		t.Errorf("名额应已释放，期望 201，得 %d", rec.Code)
	}
}

func TestStartScanBadAlign(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"bogus"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 align 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"none"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("none align 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"icp"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("icp align 应 400，得 %d", rec.Code)
	}
}

func TestStartScanWithoutSiteJSONUsesStoredCalibration(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotAlign, gotSiteBody string
	h.runner.Live = func(a, b, align, site string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		gotAlign = align
		body, err := os.ReadFile(site)
		if err != nil {
			t.Fatalf("读取 runner 外参失败: %v", err)
		}
		gotSiteBody = string(body)
		return fakeScan(a, b, align, site, keep, cb)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"site"}`, "7"); rec.Code != http.StatusCreated {
		t.Fatalf("site 缺 site_json 应读取服务端外参起扫，得 %d: %s", rec.Code, rec.Body.String())
	}
	if gotAlign != "site" || strings.TrimSpace(gotSiteBody) != testSiteJSON {
		t.Fatalf("应把服务端外参传给 runner，align=%q site=%q", gotAlign, gotSiteBody)
	}
}

func TestStartScanSiteWithoutStoredCalibrationRejected(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.SetSiteCalibrationStore(newFakeSiteCalibrationStore())
	rec := do(h, "POST", "/v1/scans/laser", `{"align":"site"}`, "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "尚未保存外参") {
		t.Fatalf("无服务端外参应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanRawRequiresAdminAndStaysDiagnostic(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotAlign string
	h.runner.Live = func(a, b, align, site string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		gotAlign = align
		return fakeScan(a, b, align, site, keep, cb)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"raw"}`, "7"); rec.Code != http.StatusForbidden {
		t.Fatalf("普通用户 raw 诊断采集应 403，得 %d %s", rec.Code, rec.Body.String())
	}
	if rec := doAs(h, "POST", "/v1/scans/laser", `{"align":"raw"}`, "7", "admin"); rec.Code != http.StatusCreated {
		t.Fatalf("admin raw 诊断起扫失败: %d %s", rec.Code, rec.Body.String())
	}
	if gotAlign != "raw" {
		t.Fatalf("显式 raw 不应被改写，得 %q", gotAlign)
	}
}

func TestSiteCalibrationGetPut(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	store := newFakeSiteCalibrationStore()
	h.SetSiteCalibrationStore(store)

	get := do(h, "GET", "/v1/scans/laser/site-calibration?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7")
	if get.Code != http.StatusOK || !strings.Contains(get.Body.String(), `"set":false`) {
		t.Fatalf("未配置查询错误: %d %s", get.Code, get.Body.String())
	}

	body := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":` + testSiteJSON + `,"source":"aruco","mean_error_mm":2.5,"rms_error_mm":3.2,"common_markers":6}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", body, "7", "inspector"); rec.Code != http.StatusForbidden {
		t.Fatalf("非 admin 保存应 403，得 %d", rec.Code)
	}
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", body, "7", "admin"); rec.Code != http.StatusConflict {
		t.Fatalf("浏览器不得创建正式外参，得 %d: %s", rec.Code, rec.Body.String())
	}
	updatedBy := int64(7)
	meanMM, rmsMM, markers := 2.5, 3.2, 6
	if err := store.Upsert(context.Background(), repo.LaserSiteCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		SiteJSON: json.RawMessage(testSiteJSON), Source: "aruco",
		MeanErrorMM: &meanMM, RMSErrorMM: &rmsMM, CommonMarkers: &markers, UpdatedBy: &updatedBy,
	}); err != nil {
		t.Fatal(err)
	}
	store.upserts = 0
	put := doAs(h, "PUT", "/v1/scans/laser/site-calibration", body, "7", "admin")
	if put.Code != http.StatusOK {
		t.Fatalf("admin 幂等确认失败: %d %s", put.Code, put.Body.String())
	}
	if store.upserts != 0 {
		t.Fatalf("幂等确认不得再写工位外参，upserts=%d", store.upserts)
	}
	roundTrip := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":{"b_to_a":[1,0,0,0.10000000000000002,0,1,0,0.2,0,0,1,0.3,0,0,0,1]},"source":"aruco","mean_error_mm":2.5,"rms_error_mm":3.2,"common_markers":6}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", roundTrip, "7", "admin"); rec.Code != http.StatusOK {
		t.Fatalf("浏览器 m↔mm 往返的浮点尾差应幂等通过，得 %d: %s", rec.Code, rec.Body.String())
	}
	get = do(h, "GET", "/v1/scans/laser/site-calibration?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7")
	if get.Code != http.StatusOK || !strings.Contains(get.Body.String(), `"set":true`) ||
		!strings.Contains(get.Body.String(), `"source":"aruco"`) ||
		!strings.Contains(get.Body.String(), `"rms_error_mm":3.2`) ||
		!strings.Contains(get.Body.String(), `"common_markers":6`) ||
		!strings.Contains(get.Body.String(), `"updated_by":7`) {
		t.Fatalf("保存后查询错误: %d %s", get.Code, get.Body.String())
	}
	bad := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":` + testSiteJSON + `,"source":"aruco","rms_error_mm":-1}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", bad, "7", "admin"); rec.Code != http.StatusBadRequest {
		t.Fatalf("负 RMS 应拒绝，得 %d: %s", rec.Code, rec.Body.String())
	}
	missingQuality := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":` + testSiteJSON + `,"source":"legacy_browser"}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", missingQuality, "7", "admin"); rec.Code != http.StatusBadRequest {
		t.Fatalf("缺少标定质量证据应 400，得 %d: %s", rec.Code, rec.Body.String())
	}
	highRMS := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":` + testSiteJSON + `,"source":"aruco","rms_error_mm":5.1,"common_markers":6}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", highRMS, "7", "admin"); rec.Code != http.StatusBadRequest {
		t.Fatalf("RMS 超生产上限应 400，得 %d: %s", rec.Code, rec.Body.String())
	}
	fewMarkers := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":` + testSiteJSON + `,"source":"aruco","rms_error_mm":3.2,"common_markers":3}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", fewMarkers, "7", "admin"); rec.Code != http.StatusBadRequest {
		t.Fatalf("公共标记不足应 400，得 %d: %s", rec.Code, rec.Body.String())
	}
	mismatch := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","site_json":{"b_to_a":[1,0,0,0.2,0,1,0,0.2,0,0,1,0.3,0,0,0,1]},"source":"aruco","mean_error_mm":2.5,"rms_error_mm":3.2,"common_markers":6}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/site-calibration", mismatch, "7", "admin"); rec.Code != http.StatusConflict {
		t.Fatalf("浏览器确认版本不一致应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanBadSiteJSON(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"site","site_json":"{bad"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("site_json 非法应 400，得 %d: %s", rec.Code, rec.Body.String())
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"raw","site_json":"{bad"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("raw 带非法 site_json 也应 400，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanRejectsClientSiteOverride(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	other := `{"b_to_a":[1,0,0,0.2,0,1,0,0.2,0,0,1,0.3,0,0,0,1]}`
	body, _ := json.Marshal(map[string]any{"align": "site", "site_json": other})
	rec := do(h, "POST", "/v1/scans/laser", string(body), "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "服务端权威版本不一致") {
		t.Fatalf("客户端外参不得覆盖服务端，应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanLoadsServerRegionAndRejectsClientOverride(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	points := [][3]float32{{-1000, -1000, 0}, {1000, -1000, 0}, {1000, 1000, 0}, {-1000, 1000, 0}}
	pointsJSON, _ := json.Marshal(points)
	regionStore := h.regionCalib.(*fakeRegionCalibrationStore)
	if err := regionStore.Upsert(context.Background(), repo.LaserRegionCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", Enabled: true,
		Points: pointsJSON, Source: "test_region",
	}); err != nil {
		t.Fatal(err)
	}
	seedCompatibleBackground(t, h, "192.168.9.101", "192.168.9.102")

	// App 不携带浏览器几何，也必须自动得到服务端区域墙和当前 site 的 B→A。
	appStart := do(h, "POST", "/v1/scans/laser", `{"align":"site"}`, "7")
	if appStart.Code != http.StatusCreated {
		t.Fatalf("App 起扫应自动加载区域墙，得 %d: %s", appStart.Code, appStart.Body.String())
	}
	var appResp startResp
	_ = json.Unmarshal(appStart.Body.Bytes(), &appResp)
	appJob, _ := fr.FindByID(context.Background(), appResp.ScanID)
	var stats struct {
		RegionFilter      PointRegionFilter         `json:"region_filter"`
		RegionCalibration RegionCalibrationSnapshot `json:"region_calibration"`
	}
	if err := json.Unmarshal(appJob.Stats, &stats); err != nil {
		t.Fatalf("解析扫描区域快照失败: %v", err)
	}
	if !stats.RegionFilter.Enabled || len(stats.RegionFilter.Points) != len(points) || len(stats.RegionFilter.BToA) != 16 {
		t.Fatalf("App 未使用服务端区域墙/当前外参: %+v", stats)
	}
	wantRevision, _ := regionDefinitionSHA256(PointRegionFilter{Enabled: true, Points: points})
	if !stats.RegionCalibration.Set || stats.RegionCalibration.PointsSHA256 != wantRevision {
		t.Fatalf("区域 revision 快照错误: %+v want=%s", stats.RegionCalibration, wantRevision)
	}

	// 旧网页携带相同定义只做一致性校验，可继续起扫；携带不同定义必须 409。
	webBody, _ := json.Marshal(map[string]any{
		"align": "site", "site_json": testSiteJSON,
		"region_filter": map[string]any{"enabled": true, "points": points, "b_to_a": make([]float32, 16)},
	})
	if rec := do(h, "POST", "/v1/scans/laser", string(webBody), "7"); rec.Code != http.StatusCreated {
		t.Fatalf("同版本旧网页请求应兼容，得 %d: %s", rec.Code, rec.Body.String())
	}
	mismatch := append([][3]float32(nil), points...)
	mismatch[0][0] = -900
	mismatchBody, _ := json.Marshal(map[string]any{
		"align": "site", "region_filter": map[string]any{"enabled": true, "points": mismatch},
	})
	if rec := do(h, "POST", "/v1/scans/laser", string(mismatchBody), "7"); rec.Code != http.StatusConflict {
		t.Fatalf("客户端区域墙不得覆盖服务端，应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestRegionCalibrationGetPutDelete(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.SetRegionCalibrationStore(newFakeRegionCalibrationStore())
	path := "/v1/scans/laser/region-calibration?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102"
	if rec := do(h, "GET", path, "", "7"); rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"set":false`) {
		t.Fatalf("未配置区域查询错误: %d %s", rec.Code, rec.Body.String())
	}
	body := `{"unit_a_ip":"192.168.9.101","unit_b_ip":"192.168.9.102","enabled":true,"points":[[0,0,0],[1000,0,0],[1000,1000,0],[0,1000,0]],"source":"web_region_editor"}`
	if rec := doAs(h, "PUT", "/v1/scans/laser/region-calibration", body, "7", "inspector"); rec.Code != http.StatusForbidden {
		t.Fatalf("非 admin 保存区域应 403，得 %d", rec.Code)
	}
	put := doAs(h, "PUT", "/v1/scans/laser/region-calibration", body, "7", "admin")
	if put.Code != http.StatusOK || !strings.Contains(put.Body.String(), `"revision":"`) {
		t.Fatalf("admin 保存区域失败: %d %s", put.Code, put.Body.String())
	}
	get := do(h, "GET", path, "", "7")
	if get.Code != http.StatusOK || !strings.Contains(get.Body.String(), `"set":true`) ||
		!strings.Contains(get.Body.String(), `"source":"web_region_editor"`) {
		t.Fatalf("保存后区域查询错误: %d %s", get.Code, get.Body.String())
	}
	if rec := doAs(h, "DELETE", path, "", "7", "admin"); rec.Code != http.StatusOK {
		t.Fatalf("删除区域失败: %d %s", rec.Code, rec.Body.String())
	}
	if rec := do(h, "GET", path, "", "7"); rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"set":false`) {
		t.Fatalf("删除后区域仍存在: %d %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanWithoutServerRegionRejected(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.SetRegionCalibrationStore(newFakeRegionCalibrationStore())
	rec := do(h, "POST", "/v1/scans/laser", `{"align":"site"}`, "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "扫描区域") {
		t.Fatalf("无服务端区域墙不得扫描整房间，应 409，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestStartScanBadRegionFilter(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"region_filter":{"enabled":true,"points":[[0,0,0],[1,0,0]]}`), "7")
	if rec.Code != http.StatusConflict {
		t.Fatalf("客户端非法/过期 region_filter 应 409 要求刷新，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func TestConfigDefaultDoesNotOverrideDeviceScanAngles(t *testing.T) {
	cfg := Config{}.withDefaults()
	if cfg.SetScanAngles {
		t.Fatal("默认不应在起扫前覆盖设备持久化扫描角")
	}
}

func TestValidateScanAnglesUsesLinearMechanicalAxis(t *testing.T) {
	if err := validateScanAngles(-180, 180); err == nil {
		t.Fatal("单段 360° 超过当前设备单扫稳定范围，应拒绝")
	}
	if err := validateScanAngles(-179, 179); err == nil {
		t.Fatal("单段 358° 超过当前设备单扫稳定范围，应拒绝")
	}
	if err := validateScanAngles(-180, 20); err == nil {
		t.Fatal("-180→20 是线性 200° 跨界设置，应拒绝")
	}
	if err := validateScanAngles(-180, -20); err == nil {
		t.Fatal("-180 贴边起扫不稳定，应拒绝")
	}
	if err := validateScanAngles(-170, -10); err != nil {
		t.Fatalf("-170→-10 应有效，得 %v", err)
	}
	if err := validateScanAngles(0, 170); err != nil {
		t.Fatalf("0→170 应有效，得 %v", err)
	}
}

func TestScanStopFromSignedAngle(t *testing.T) {
	stop, err := scanStopFromAngle(-180, -160)
	if err == nil {
		t.Fatal("-180 起扫、-160° 会落到 -340°，应拒绝")
	}
	_ = stop
	stop, err = scanStopFromAngle(-170, 160)
	if err != nil {
		t.Fatalf("-170 起扫、160° 扫描角应有效，得 %v", err)
	}
	if stop != -10 {
		t.Fatalf("期望停止角 -10，得 %v", stop)
	}
	if _, err := scanStopFromAngle(-180, 180); err == nil {
		t.Fatal("单段 180° 存在半圈歧义，应拒绝")
	}
	if _, err := scanStopFromAngle(-180, 2); err == nil {
		t.Fatal("过小扫描角应拒绝")
	}
	if _, err := scanStopFromAngle(20, -100); err == nil {
		t.Fatal("负扫描角会被固件跨 +180° 扫成超大角度，应拒绝")
	}
}

func TestDeviceScanSettingsAcceptsSignedScanAngle(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	dev := &fakeDeviceAPI{}
	h.newDev = func(string) DeviceAPI { return dev }

	rec := doAs(h, "POST", "/v1/scans/laser/device-scan-settings?unit=b", `{
		"scan_speed": 6,
		"zero_speed": 20,
		"scan_start_angle": -170,
		"scan_stop_angle": 180,
		"scan_angle": 160,
		"watching_angle": 180,
		"lidar_filter_ghost": 0,
		"lidar_filter_zone": [-180, 180],
		"camera_fps": 0.33
	}`, "7", "admin")

	if rec.Code != http.StatusOK {
		t.Fatalf("scan_angle 请求应成功，得 %d: %s", rec.Code, rec.Body.String())
	}
	if dev.updated.ScanStartAngle != -170 {
		t.Fatalf("起始角应原样下发，得 %v", dev.updated.ScanStartAngle)
	}
	if dev.updated.ScanStopAngle != -10 {
		t.Fatalf("scan_angle=160 应换算为 stop=-10，得 %v", dev.updated.ScanStopAngle)
	}
	if dev.updated.ScanAngle == nil || *dev.updated.ScanAngle != 160 {
		t.Fatalf("应把 scan_angle=160 一起下发，得 %v", dev.updated.ScanAngle)
	}
}

func TestDeviceMutationRejectsUnmanagedExplicitIP(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotIP string
	dev := &fakeDeviceAPI{}
	h.newDev = func(ip string) DeviceAPI {
		gotIP = ip
		return dev
	}

	rec := doAs(h, "POST", "/v1/scans/laser/device-scan-settings?ip=192.168.9.150", `{
		"scan_speed": 6,
		"zero_speed": 20,
		"scan_start_angle": -80,
		"scan_stop_angle": 20,
		"scan_angle": 100,
		"watching_angle": 0,
		"lidar_filter_ghost": 0,
		"lidar_filter_zone": [-180, 180],
		"camera_fps": 0.33
	}`, "7", "admin")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("非受管 IP 设备配置应拒绝，得 %d: %s", rec.Code, rec.Body.String())
	}
	if gotIP != "" {
		t.Fatalf("非受管 IP 不得创建设备客户端，得 %q", gotIP)
	}
	if dev.updated.ScanAngle != nil {
		t.Fatalf("非受管 IP 不得发生写操作，得 %+v", dev.updated)
	}
}

func TestDeviceEndpointsRejectBadExplicitIP(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	rec := do(h, "GET", "/v1/scans/laser/device-status?ip=not-an-ip", "", "7")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 ip 应 400，得 %d", rec.Code)
	}
}

func TestStopScanOwnerAndForbidden(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner := int64(7)
	j, _ := fr.Create(context.Background(), "sk", "a", "b", "icp", 1.0, nil, &owner)
	// 模拟进行中 + 注册活动会话。
	fr.jobs[j.ID].Status = repo.LaserScanStatusCapturing
	cancelled := false
	order := make([]string, 0, 2)
	fr.cancelHook = func() { order = append(order, "repo") }
	h.sessions.set(j.ID, &activeSession{jobID: j.ID, owner: owner, cancel: func() {
		cancelled = true
		order = append(order, "session")
	}})

	// 他人 → 403。
	if rec := do(h, "POST", "/v1/scans/laser/"+itoa(j.ID)+"/stop", "", "99"); rec.Code != http.StatusForbidden {
		t.Fatalf("他人停止应 403，得 %d", rec.Code)
	}
	// owner → 200 cancelled + 触发 cancel。
	rec := do(h, "POST", "/v1/scans/laser/"+itoa(j.ID)+"/stop", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("owner 停止应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	if !cancelled {
		t.Error("应触发活动会话 cancel")
	}
	if got := strings.Join(order, ","); got != "repo,session" {
		t.Fatalf("停止必须先写 cancelled 再停采集，实际顺序 %s", got)
	}
	jj, _ := fr.FindByID(context.Background(), j.ID)
	if jj.Status != repo.LaserScanStatusCancelled {
		t.Errorf("应 cancelled，得 %s", jj.Status)
	}
}

func TestGetScan(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner := int64(7)
	j, _ := fr.Create(context.Background(), "sk", "a", "b", "icp", 1.0, nil, &owner)
	rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID), "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("期望 200，得 %d", rec.Code)
	}
	var v map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &v)
	if v["session_key"] != "sk" || v["status"] != "capturing" {
		t.Errorf("视图错: %+v", v)
	}
	// 他人 → 403。
	if rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID), "", "99"); rec.Code != http.StatusForbidden {
		t.Errorf("他人查看应 403，得 %d", rec.Code)
	}
	// 不存在 → 404。
	if rec := do(h, "GET", "/v1/scans/laser/999999", "", "7"); rec.Code != http.StatusNotFound {
		t.Errorf("不存在应 404，得 %d", rec.Code)
	}
}

type memReader struct{ blobs map[string][]byte }

func (m memReader) GetObject(_ context.Context, key string) (io.ReadCloser, int64, error) {
	b, ok := m.blobs[key]
	if !ok {
		return nil, 0, errors.New("not found")
	}
	return io.NopCloser(bytes.NewReader(b)), int64(len(b)), nil
}

func TestDownloadCloud(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner := int64(7)
	j, _ := fr.Create(context.Background(), "sk", "a", "b", "icp", 1.0, nil, &owner)
	// 标记完成 + 写 object key。
	fusedKey := LaserObjectKey("sk", "fused")
	pcd, _ := EncodePCDBinary([]float32{1, 2, 3})
	_, _ = fr.Complete(context.Background(), j.ID, repo.LaserScanCompletion{
		AlignMethod: "icp", Fused: 1, FusedObjectKey: fusedKey,
		UnitAObjectKey: LaserObjectKey("sk", "unit_a"), UnitBObjectKey: LaserObjectKey("sk", "unit_b"),
	})
	h.SetCloudReader(memReader{blobs: map[string][]byte{fusedKey: pcd}})

	// owner 下载 fused → 200 + PCD 字节。
	rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID)+"/cloud/fused", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("期望 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "DATA binary") {
		t.Error("响应体应为 PCD")
	}
	// 渲染采样不改权威对象，只派生有界 PCD 并带回源点数。
	xyz := make([]float32, 30)
	for i := range xyz {
		xyz[i] = float32(i)
	}
	largePCD, _ := EncodePCDBinary(xyz)
	h.SetCloudReader(memReader{blobs: map[string][]byte{fusedKey: largePCD}})
	rec = do(h, "GET", "/v1/scans/laser/"+itoa(j.ID)+"/cloud/fused?max_points=3", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("采样下载期望 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("X-Gomob-Source-Points") != "10" || rec.Header().Get("X-Gomob-Render-Points") != "3" {
		t.Fatalf("采样点数响应头错误: source=%s render=%s",
			rec.Header().Get("X-Gomob-Source-Points"), rec.Header().Get("X-Gomob-Render-Points"))
	}
	if !strings.Contains(rec.Body.String(), "# GOMOB_SOURCE_POINTS 10") || !strings.Contains(rec.Body.String(), "POINTS 3") {
		t.Fatal("采样 PCD 头未携带源点数/渲染点数")
	}
	if rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID)+"/cloud/fused?max_points=0", "", "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 max_points 应 400，得 %d", rec.Code)
	}
	// 非法 name → 400。
	if rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID)+"/cloud/bogus", "", "7"); rec.Code != http.StatusBadRequest {
		t.Errorf("非法 name 应 400，得 %d", rec.Code)
	}
	// 他人 → 403。
	if rec := do(h, "GET", "/v1/scans/laser/"+itoa(j.ID)+"/cloud/fused", "", "99"); rec.Code != http.StatusForbidden {
		t.Errorf("他人下载应 403，得 %d", rec.Code)
	}
	// 未就绪的 unit（object key 为空，因 Complete 写了 unit_a/b 但 reader 无该 blob → 502；
	// 这里测真正未就绪：新建未完成 job 的 fused）。
	j2, _ := fr.Create(context.Background(), "sk2", "a", "b", "icp", 1.0, nil, &owner)
	if rec := do(h, "GET", "/v1/scans/laser/"+itoa(j2.ID)+"/cloud/fused", "", "7"); rec.Code != http.StatusNotFound {
		t.Errorf("未就绪应 404，得 %d", rec.Code)
	}
}

func TestDownloadActiveCloudSnapshot(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	owner := int64(7)
	j, _ := fr.Create(context.Background(), "sk", "192.168.9.101", "192.168.9.102", "icp", 1.0, nil, &owner)
	cache := newLivePointCache()
	cache.append(PointFrame{Unit: 0, XYZmm: []float32{1, 2, 3, 4, 5, 6}})
	cache.append(PointFrame{Unit: 1, XYZmm: []float32{7, 8, 9}})
	h.sessions.set(j.ID, &activeSession{
		jobID:      j.ID,
		sessionKey: "sk",
		owner:      owner,
		unitAIP:    "192.168.9.101",
		unitBIP:    "192.168.9.102",
		state:      repo.LaserScanStatusCapturing,
		cache:      cache,
	})

	rec := do(h, "GET", "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("活动点云快照应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	xyz, err := DecodePCDBinary(rec.Body.Bytes())
	if err != nil {
		t.Fatalf("实时 PCD 应可解码: %v", err)
	}
	if len(xyz) != 6 || xyz[0] != 1 || xyz[5] != 6 {
		t.Fatalf("unit_a 快照点错: %+v", xyz)
	}
	if rec.Header().Get("X-Gomob-Source-Points") != "2" || rec.Header().Get("X-Gomob-Render-Points") != "2" {
		t.Fatalf("实时快照点数响应头错误: source=%s render=%s",
			rec.Header().Get("X-Gomob-Source-Points"), rec.Header().Get("X-Gomob-Render-Points"))
	}
	if !strings.Contains(rec.Body.String(), "# GOMOB_SOURCE_POINTS 2") {
		t.Fatal("实时快照 PCD 头缺少源点数")
	}

	sampled := do(h, "GET", "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102&max_points=1", "", "7")
	if sampled.Code != http.StatusOK {
		t.Fatalf("实时快照采样应 200，得 %d: %s", sampled.Code, sampled.Body.String())
	}
	sampledXYZ, err := DecodePCDBinary(sampled.Body.Bytes())
	if err != nil || len(sampledXYZ) != 3 {
		t.Fatalf("实时采样 PCD 错误: points=%d err=%v", len(sampledXYZ)/3, err)
	}
	if sampled.Header().Get("X-Gomob-Source-Points") != "2" || sampled.Header().Get("X-Gomob-Render-Points") != "1" {
		t.Fatalf("实时采样响应头错误: source=%s render=%s",
			sampled.Header().Get("X-Gomob-Source-Points"), sampled.Header().Get("X-Gomob-Render-Points"))
	}
	if rec := do(h, "GET", "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102&max_points=0", "", "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("实时快照非法 max_points 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/active/cloud/fused?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("实时快照非法 name 应 400，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102", "", "99"); rec.Code != http.StatusForbidden {
		t.Fatalf("他人下载活动点云应 403，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/active/cloud/unit_a?unit_a_ip=192.168.9.200&unit_b_ip=192.168.9.201", "", "7"); rec.Code != http.StatusNotFound {
		t.Fatalf("非活动工位快照应 404，得 %d", rec.Code)
	}
}

func itoa(n int64) string {
	return strings.TrimSpace(jsonNum(n))
}
func jsonNum(n int64) string {
	b, _ := json.Marshal(n)
	return string(b)
}
