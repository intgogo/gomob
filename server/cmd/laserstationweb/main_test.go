package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
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

func TestSessionCookieValidOnlyToday(t *testing.T) {
	base := time.Date(2026, 6, 8, 12, 0, 0, 0, time.Local)
	s := &webServer{secret: []byte("test-secret"), now: func() time.Time { return base }}

	rec := httptest.NewRecorder()
	s.setSession(rec)
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("cookies=%d", len(cookies))
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(cookies[0])
	if !s.validSession(req) {
		t.Fatal("当天 cookie 应有效")
	}

	s.now = func() time.Time { return base.Add(24 * time.Hour) }
	if s.validSession(req) {
		t.Fatal("隔天 cookie 不应继续有效")
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
	s := &webServer{secret: []byte("test-secret"), now: func() time.Time { return base }}

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
