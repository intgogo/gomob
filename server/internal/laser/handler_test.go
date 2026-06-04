package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
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

func TestStartScanHappy(t *testing.T) {
	h, fr, pub, clouds := newTestHandler(t, true)
	rec := do(h, "POST", "/v1/scans/laser", `{"align":"icp"}`, "7")
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
	rec2 := do(h, "POST", "/v1/scans/laser", `{}`, "7")
	if rec2.Code != http.StatusCreated {
		t.Errorf("名额应已释放，第二次期望 201，得 %d", rec2.Code)
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
	if rec := do(h, "POST", "/v1/scans/laser", `{}`, "7"); rec.Code != http.StatusCreated {
		t.Fatalf("首次应 201，得 %d", rec.Code)
	}
	if rec := do(h, "POST", "/v1/scans/laser", `{}`, "7"); rec.Code != http.StatusConflict {
		t.Fatalf("占用中应 409，得 %d", rec.Code)
	}
}

func TestStartScanProbeFail(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.probe = fakeProber{res: ProbeResult{Reachable: false, Err: "connection refused"}}
	rec := do(h, "POST", "/v1/scans/laser", `{}`, "7")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("探活失败应 502，得 %d: %s", rec.Code, rec.Body.String())
	}
	// 失败应释放名额：再探活通过则可起。
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true}}
	if rec := do(h, "POST", "/v1/scans/laser", `{}`, "7"); rec.Code != http.StatusCreated {
		t.Errorf("名额应已释放，期望 201，得 %d", rec.Code)
	}
}

func TestStartScanBadAlign(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	if rec := do(h, "POST", "/v1/scans/laser", `{"align":"bogus"}`, "7"); rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 align 应 400，得 %d", rec.Code)
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

func itoa(n int64) string {
	return strings.TrimSpace(jsonNum(n))
}
func jsonNum(n int64) string {
	b, _ := json.Marshal(n)
	return string(b)
}
