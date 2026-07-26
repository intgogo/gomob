package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"io.gomob/server/pkg/token"
)

func TestLoginPasswordRejectsWeakOrMissing(t *testing.T) {
	t.Setenv("GOMOB_LASER_STATION_PASSWORD", "")
	if _, err := loginPassword(); err == nil {
		t.Fatal("未设置口令应拒绝启动")
	}
	t.Setenv("GOMOB_LASER_STATION_PASSWORD", "short")
	if _, err := loginPassword(); err == nil {
		t.Fatal("过弱口令应拒绝启动")
	}
	t.Setenv("GOMOB_LASER_STATION_PASSWORD", "a-sufficiently-long-high-entropy-secret")
	pw, err := loginPassword()
	if err != nil {
		t.Fatalf("合规口令应通过: %v", err)
	}
	s := &webServer{password: pw}
	if !s.passwordOK("a-sufficiently-long-high-entropy-secret") {
		t.Fatal("正确口令应通过校验")
	}
	if s.passwordOK("wrong") {
		t.Fatal("错误口令不应通过校验")
	}
}

func TestSessionCookieUsesConfiguredTTL(t *testing.T) {
	base := time.Date(2026, 6, 8, 12, 0, 0, 0, time.Local)
	s := &webServer{secret: []byte("test-secret"), sessionTTL: 30 * 24 * time.Hour, now: func() time.Time { return base }}

	rec := httptest.NewRecorder()
	s.setSession(rec)
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("cookies=%d", len(cookies))
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(cookies[0])
	if !s.validSession(req) {
		t.Fatal("刚签发的 cookie 应有效")
	}

	s.now = func() time.Time { return base.Add(29 * 24 * time.Hour) }
	if !s.validSession(req) {
		t.Fatal("未过期 cookie 应继续有效")
	}

	s.now = func() time.Time { return base.Add(31 * 24 * time.Hour) }
	if s.validSession(req) {
		t.Fatal("过期 cookie 不应继续有效")
	}
}

func TestCookieSecretDefaultsToPasswordDerived(t *testing.T) {
	t.Setenv("GOMOB_LASER_STATION_COOKIE_SECRET", "")
	a, err := cookieSecret("a-sufficiently-long-high-entropy-secret")
	if err != nil {
		t.Fatal(err)
	}
	b, err := cookieSecret("a-sufficiently-long-high-entropy-secret")
	if err != nil {
		t.Fatal(err)
	}
	if string(a) != string(b) {
		t.Fatal("相同口令应派生出稳定 cookie secret")
	}
	c, err := cookieSecret("another-sufficiently-long-high-entropy-secret")
	if err != nil {
		t.Fatal(err)
	}
	if string(a) == string(c) {
		t.Fatal("不同口令不应派生出相同 cookie secret")
	}

	t.Setenv("GOMOB_LASER_STATION_COOKIE_SECRET", "explicit-secret")
	explicit, err := cookieSecret("a-sufficiently-long-high-entropy-secret")
	if err != nil {
		t.Fatal(err)
	}
	if string(explicit) != "explicit-secret" {
		t.Fatalf("显式 secret 未生效: %q", string(explicit))
	}
}

func TestSessionTTLFromEnv(t *testing.T) {
	t.Setenv("GOMOB_LASER_STATION_SESSION_DAYS", "")
	ttl, err := sessionTTL()
	if err != nil {
		t.Fatal(err)
	}
	if ttl != defaultSessionDays*24*time.Hour {
		t.Fatalf("默认 ttl=%s", ttl)
	}

	t.Setenv("GOMOB_LASER_STATION_SESSION_DAYS", "7")
	ttl, err = sessionTTL()
	if err != nil {
		t.Fatal(err)
	}
	if ttl != 7*24*time.Hour {
		t.Fatalf("自定义 ttl=%s", ttl)
	}

	t.Setenv("GOMOB_LASER_STATION_SESSION_DAYS", "0")
	if _, err := sessionTTL(); err == nil {
		t.Fatal("0 天应拒绝")
	}

	t.Setenv("GOMOB_LASER_STATION_SESSION_DAYS", "91")
	if _, err := sessionTTL(); err == nil {
		t.Fatal("超过上限应拒绝")
	}
}

func TestGatewayURLUsesSameOriginProxy(t *testing.T) {
	t.Setenv("GOMOB_LASER_STATION_GATEWAY", "")
	t.Setenv("GOMOB_LASER_STATION_PUBLIC_GATEWAY", "")
	req := httptest.NewRequest(http.MethodGet, "http://192.168.9.10:5177/station/session", nil)
	if got := gatewayURL(req); got != "http://192.168.9.10:5177/gateway" {
		t.Fatalf("gateway=%q", got)
	}
}

func TestGatewayProxyStripsPrefix(t *testing.T) {
	var gotPath, gotQuery string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()

	target, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/gateway/v1/scans/laser/active?unit_a_ip=1", nil)
	gatewayProxy(target).ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	if gotPath != "/v1/scans/laser/active" {
		t.Fatalf("path=%q", gotPath)
	}
	if gotQuery != "unit_a_ip=1" {
		t.Fatalf("query=%q", gotQuery)
	}
}

func TestStationSessionIssuesAccessToken(t *testing.T) {
	t.Setenv("GOMOB_LASER_STATION_USER_ID", "42")
	t.Setenv("GOMOB_LASER_STATION_ROLE", "admin")
	base := time.Date(2026, 6, 8, 12, 0, 0, 0, time.Local)
	s := &webServer{secret: []byte("test-secret"), sessionTTL: 30 * 24 * time.Hour, now: func() time.Time { return base }}

	cookieRec := httptest.NewRecorder()
	s.setSession(cookieRec)

	req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:5177/station/session", nil)
	req.AddCookie(cookieRec.Result().Cookies()[0])
	rec := httptest.NewRecorder()
	s.stationSession(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var body struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.AccessToken == "" {
		t.Fatalf("未返回 access_token: %s", rec.Body.String())
	}
	claims, err := token.Parse(body.AccessToken)
	if err != nil {
		t.Fatal(err)
	}
	if claims.UserID != 42 || claims.Role != "admin" || claims.Kind != "access" {
		t.Fatalf("claims=%+v", claims)
	}
}

func TestSubmitFeedbackRequiresSession(t *testing.T) {
	s := &webServer{secret: []byte("test-secret"), sessionTTL: 30 * 24 * time.Hour, now: time.Now, feedbackDir: t.TempDir()}
	req := httptest.NewRequest(http.MethodPost, "/station/feedback", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	s.submitFeedback(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestSubmitFeedbackWritesFiles(t *testing.T) {
	base := time.Date(2026, 6, 29, 12, 0, 0, 0, time.Local)
	dir := t.TempDir()
	s := &webServer{secret: []byte("test-secret"), sessionTTL: 30 * 24 * time.Hour, now: func() time.Time { return base }, feedbackDir: dir}
	cookieRec := httptest.NewRecorder()
	s.setSession(cookieRec)

	body := `{
		"title":"点云按钮位置异常",
		"severity":"high",
		"category":"ui",
		"pageUrl":"http://127.0.0.1:5177/",
		"userAgent":"test-agent",
		"imageDataUrl":"` + tinyPNGDataURL + `",
		"annotatedDataUrl":"` + tinyPNGDataURL + `",
		"boxes":[{"x":0.1,"y":0.2,"w":0.3,"h":0.4,"note":"按钮遮挡点云"}]
	}`
	req := httptest.NewRequest(http.MethodPost, "/station/feedback", strings.NewReader(body))
	req.AddCookie(cookieRec.Result().Cookies()[0])
	rec := httptest.NewRecorder()

	s.submitFeedback(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var resp struct {
		OK  bool   `json:"ok"`
		Dir string `json:"dir"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.OK || !strings.HasPrefix(resp.Dir, dir) {
		t.Fatalf("resp=%+v", resp)
	}
	for _, name := range []string{"screenshot.png", "annotated.png", "report.json", "report.md"} {
		if _, err := os.Stat(filepath.Join(resp.Dir, name)); err != nil {
			t.Fatalf("缺少 %s: %v", name, err)
		}
	}
	raw, err := os.ReadFile(filepath.Join(resp.Dir, "report.md"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), "按钮遮挡点云") {
		t.Fatalf("report.md 未包含标注说明: %s", string(raw))
	}
}

const tinyPNGDataURL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
