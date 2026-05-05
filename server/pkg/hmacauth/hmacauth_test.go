package hmacauth

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

const testSecret = "test-secret-abc-123"

func newSignedReq(t *testing.T, method, urlStr string, body []byte, secret string) *http.Request {
	t.Helper()
	var br io.Reader
	if body != nil {
		br = bytes.NewReader(body)
	}
	r := httptest.NewRequest(method, urlStr, br)
	if body != nil {
		r.ContentLength = int64(len(body))
	}
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	nonce := "nonce-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	sig := ComputeSig([]byte(secret), ts, r.Method, r.URL.RequestURI(), body)
	r.Header.Set(HeaderTs, ts)
	r.Header.Set(HeaderNonce, nonce)
	r.Header.Set(HeaderSig, sig)
	return r
}

func decodeBody(t *testing.T, w *httptest.ResponseRecorder) (int, string) {
	t.Helper()
	var env struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	}
	_ = json.NewDecoder(w.Body).Decode(&env)
	return env.Code, env.Message
}

func TestVerifier_Disabled_NoOp(t *testing.T) {
	v := NewVerifier("", false, nil)
	if !v.Disabled() {
		t.Fatal("空 secret 应 Disabled")
	}
	called := false
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
		w.WriteHeader(200)
	}))
	r := httptest.NewRequest("GET", "/anything", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if !called || w.Code != 200 {
		t.Fatalf("disabled 应直放，got called=%v code=%d", called, w.Code)
	}
}

func TestVerifier_Required_MissingHeaders_Rejected(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Fatal("required 模式缺头不该到 handler")
	}))
	r := httptest.NewRequest("GET", "/x", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("缺头应 401，got %d", w.Code)
	}
	code, _ := decodeBody(t, w)
	if code != 40110 {
		t.Fatalf("期望 40110，got %d", code)
	}
}

func TestVerifier_NotRequired_MissingHeaders_Passthrough(t *testing.T) {
	v := NewVerifier(testSecret, false, nil)
	called := false
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		called = true
	}))
	r := httptest.NewRequest("GET", "/x", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if !called {
		t.Fatal("未要求时缺头应放行")
	}
}

func TestVerifier_GoodSignature_Passes(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	called := false
	gotBody := ""
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		bb, _ := io.ReadAll(r.Body)
		gotBody = string(bb)
	}))
	body := []byte(`{"hello":"world"}`)
	r := newSignedReq(t, "POST", "http://example.com/api?x=1", body, testSecret)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if !called {
		t.Fatalf("正确签应放行，code=%d", w.Code)
	}
	if gotBody != string(body) {
		t.Fatalf("body 应可被 handler 再读，got %q", gotBody)
	}
}

func TestVerifier_BadSignature_Rejected(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Fatal("错签不该到 handler")
	}))
	r := newSignedReq(t, "POST", "/x", []byte("body"), testSecret)
	r.Header.Set(HeaderSig, "deadbeef")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("期望 401，got %d", w.Code)
	}
	code, _ := decodeBody(t, w)
	if code != 40113 {
		t.Fatalf("期望 40113，got %d", code)
	}
}

func TestVerifier_DifferentSecret_Rejected(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Fatal("错 secret 不该到 handler")
	}))
	r := newSignedReq(t, "POST", "/x", []byte("body"), "wrong-secret")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	code, _ := decodeBody(t, w)
	if code != 40113 {
		t.Fatalf("期望 40113，got %d", code)
	}
}

func TestVerifier_ExpiredTs_Rejected(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		t.Fatal("过期 ts 不该到 handler")
	}))
	body := []byte("body")
	// 用 10min 前的 ts
	old := strconv.FormatInt(time.Now().Add(-10*time.Minute).Unix(), 10)
	r := httptest.NewRequest("POST", "/x", bytes.NewReader(body))
	sig := ComputeSig([]byte(testSecret), old, r.Method, r.URL.RequestURI(), body)
	r.Header.Set(HeaderTs, old)
	r.Header.Set(HeaderNonce, "n1")
	r.Header.Set(HeaderSig, sig)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	code, _ := decodeBody(t, w)
	if code != 40111 {
		t.Fatalf("期望 40111，got %d", code)
	}
}

func TestVerifier_NonceReplay_Rejected(t *testing.T) {
	store := NewInMemoryNonceStore()
	defer store.Close()
	v := NewVerifier(testSecret, true, store)
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(200)
	}))
	body := []byte("body")
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	nonce := "fixed-nonce"
	sig := ComputeSig([]byte(testSecret), ts, "POST", "/x", body)

	for i, expect := range []int{200, http.StatusUnauthorized} {
		r := httptest.NewRequest("POST", "/x", bytes.NewReader(body))
		r.Header.Set(HeaderTs, ts)
		r.Header.Set(HeaderNonce, nonce)
		r.Header.Set(HeaderSig, sig)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != expect {
			t.Fatalf("第 %d 次期望 %d，got %d", i+1, expect, w.Code)
		}
		if i == 1 {
			code, _ := decodeBody(t, w)
			if code != 40112 {
				t.Fatalf("第二次期望 40112，got %d", code)
			}
		}
	}
}

func TestSigningTransport_RoundTripSignsCorrectly(t *testing.T) {
	v := NewVerifier(testSecret, true, nil)
	called := false
	srv := httptest.NewServer(v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if r.Header.Get(HeaderTs) == "" || r.Header.Get(HeaderNonce) == "" || r.Header.Get(HeaderSig) == "" {
			t.Fatal("server 应已收到三签头")
		}
		w.WriteHeader(204)
	})))
	defer srv.Close()

	hc := &http.Client{Transport: NewSigningTransport(http.DefaultTransport, testSecret)}
	resp, err := hc.Post(srv.URL+"/x", "application/json", strings.NewReader(`{"hi":1}`))
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("期望 204，got %d", resp.StatusCode)
	}
	if !called {
		t.Fatal("handler 没被调用 — 签名通过校验失败")
	}
}

func TestSigningTransport_NoSecret_NoOp(t *testing.T) {
	rt := NewSigningTransport(http.DefaultTransport, "")
	if rt != http.DefaultTransport {
		t.Fatal("空 secret 应直返 base")
	}
}

func TestComputeSig_DeterministicAcrossCalls(t *testing.T) {
	a := ComputeSig([]byte("k"), "1700000000", "POST", "/x", []byte("body"))
	b := ComputeSig([]byte("k"), "1700000000", "POST", "/x", []byte("body"))
	if a != b {
		t.Fatal("ComputeSig 必须确定")
	}
	c := ComputeSig([]byte("k"), "1700000001", "POST", "/x", []byte("body"))
	if a == c {
		t.Fatal("不同 ts 应不同签")
	}
}

func TestInMemoryNonceStore_TTL(t *testing.T) {
	s := NewInMemoryNonceStore()
	defer s.Close()
	ctx := context.Background()
	ok, _ := s.SeenAndRemember(ctx, "n", time.Hour)
	if ok {
		t.Fatal("第一次应未见过")
	}
	ok, _ = s.SeenAndRemember(ctx, "n", time.Hour)
	if !ok {
		t.Fatal("第二次应判为重放")
	}
}
