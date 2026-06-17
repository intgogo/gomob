package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"

	"io.gomob/server/pkg/repo"
)

// --- fake LaserRepo（内存）---

type fakeLaserRepo struct {
	mu   sync.Mutex
	seq  int64
	jobs map[int64]*repo.LaserScanJob
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
func (f *fakeLaserRepo) FindLatestDone(_ context.Context, a, b string) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var best *repo.LaserScanJob
	for _, j := range f.jobs {
		if j.UnitAIP == a && j.UnitBIP == b && j.Status == repo.LaserScanStatusDone && (best == nil || j.ID > best.ID) {
			best = j
		}
	}
	if best == nil {
		return nil, repo.ErrNotFound
	}
	return clone(best), nil
}
func (f *fakeLaserRepo) Cancel(_ context.Context, id int64) (*repo.LaserScanJob, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
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
	j.Fused = &c.Fused
	j.FusedObjectKey = &c.FusedObjectKey
	j.UnitAObjectKey = &c.UnitAObjectKey
	j.UnitBObjectKey = &c.UnitBObjectKey
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

type fakeGate struct{ started, stopped bool }

func (g *fakeGate) Start(context.Context) error { g.started = true; return nil }
func (g *fakeGate) Stop(context.Context) error  { g.stopped = true; return nil }

type fakeProber struct{ res ProbeResult }

func (p fakeProber) Probe(context.Context, string) ProbeResult { return p.res }

type fakeDeviceAPI struct {
	updated ControlSettings
	info    *DeviceInfo
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
func (f *fakeDeviceAPI) ControlScan(context.Context, ScanCmd) error { return nil }
func (f *fakeDeviceAPI) UpdateControl(_ context.Context, s ControlSettings) error {
	f.updated = s
	return nil
}
func (f *fakeDeviceAPI) UpdateCalib(context.Context, CalibParams) error { return nil }

// 组装一个全 fake、launch 同步的 handler。
func newTestHandler(t *testing.T, launchSync bool) (*Handler, *fakeLaserRepo, *fakePublisher, *fakeCloudStore) {
	t.Helper()
	fr := newFakeRepo()
	clouds := &fakeCloudStore{}
	pub := &fakePublisher{}
	runner := NewRunner(fr, clouds, pub, nil)
	runner.Live = fakeScan
	runner.Replay = fakeScan
	h := NewHandler(Config{}, fr, runner, pub, nil)
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, Model: "LTS-T1"}}
	h.newGate = func(a, b string) DeviceGate { return &fakeGate{} }
	h.newDev = func(string) DeviceAPI { return &fakeDeviceAPI{} }
	if launchSync {
		h.launch = func(f func()) { f() }
	}
	return h, fr, pub, clouds
}

func do(h *Handler, method, target, body string, uid string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	if uid != "" {
		req.Header.Set("X-Gomob-User-Id", uid)
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

func TestActiveScanRestoresCurrentStation(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.launch = func(f func()) {} // 保持活动会话，模拟扫描中刷新网页
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"unit_a_ip":"192.168.9.150","unit_b_ip":"192.168.9.151"`), "7")
	if rec.Code != http.StatusCreated {
		t.Fatalf("起扫应 201，得 %d: %s", rec.Code, rec.Body.String())
	}
	var start startResp
	_ = json.Unmarshal(rec.Body.Bytes(), &start)

	rec = do(h, "GET", "/v1/scans/laser/active?unit_a_ip=192.168.9.150&unit_b_ip=192.168.9.151", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("活动扫描应 200，得 %d: %s", rec.Code, rec.Body.String())
	}
	var active map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &active)
	if active["active"] != true || int64(active["scan_id"].(float64)) != start.ScanID || active["session_key"] != start.SessionKey {
		t.Fatalf("活动扫描视图错: %+v", active)
	}
	if active["unit_a_ip"] != "192.168.9.150" || active["unit_b_ip"] != "192.168.9.151" {
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
	if rec := do(h, "GET", "/v1/scans/laser/active?unit_a_ip=192.168.9.150&unit_b_ip=192.168.9.151", "", "99"); rec.Code != http.StatusForbidden {
		t.Fatalf("他人查看活动扫描应 403，得 %d", rec.Code)
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
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true}}
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

func TestStartScanWithoutSiteJSONRunsRaw(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotAlign, gotSite string
	h.runner.Live = func(a, b, align, site string, keep float32, cb ScanCallbacks) (ScanResult, error) {
		gotAlign = align
		gotSite = site
		return fakeScan(a, b, align, site, keep, cb)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"site"}`, "7"); rec.Code != http.StatusCreated {
		t.Fatalf("site 缺 site_json 应 raw 起扫成功，得 %d: %s", rec.Code, rec.Body.String())
	}
	if gotAlign != "raw" || gotSite != "" {
		t.Fatalf("缺 site_json 应传 raw 给 runner，align=%q site=%q", gotAlign, gotSite)
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

func TestStartScanBadRegionFilter(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", siteStartBody(`"region_filter":{"enabled":true,"points":[[0,0,0],[1,0,0]]}`), "7")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 region_filter 应 400，得 %d: %s", rec.Code, rec.Body.String())
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

	rec := do(h, "POST", "/v1/scans/laser/device-scan-settings?unit=b", `{
		"scan_speed": 6,
		"zero_speed": 20,
		"scan_start_angle": -170,
		"scan_stop_angle": 180,
		"scan_angle": 160,
		"watching_angle": 180,
		"lidar_filter_ghost": 0,
		"lidar_filter_zone": [-180, 180],
		"camera_fps": 0.33
	}`, "7")

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

func TestDeviceEndpointsResolveExplicitIP(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	var gotIP string
	dev := &fakeDeviceAPI{}
	h.newDev = func(ip string) DeviceAPI {
		gotIP = ip
		return dev
	}

	rec := do(h, "POST", "/v1/scans/laser/device-scan-settings?ip=192.168.9.150", `{
		"scan_speed": 6,
		"zero_speed": 20,
		"scan_start_angle": -80,
		"scan_stop_angle": 20,
		"scan_angle": 100,
		"watching_angle": 0,
		"lidar_filter_ghost": 0,
		"lidar_filter_zone": [-180, 180],
		"camera_fps": 0.33
	}`, "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("显式 IP 设备配置应成功，得 %d: %s", rec.Code, rec.Body.String())
	}
	if gotIP != "192.168.9.150" {
		t.Fatalf("应路由到显式 IP，得 %q", gotIP)
	}
	if dev.updated.ScanAngle == nil || *dev.updated.ScanAngle != 100 {
		t.Fatalf("应下发 scan_angle=100，得 %+v", dev.updated)
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
	h.sessions.set(j.ID, &activeSession{jobID: j.ID, owner: owner, cancel: func() { cancelled = true }})

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
