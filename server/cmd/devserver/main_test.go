package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWithCORSExposesLaserCloudIdentityHeaders(t *testing.T) {
	handler := withCORS(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	for _, method := range []string{http.MethodGet, http.MethodOptions} {
		req := httptest.NewRequest(method, "/v1/scans/laser/1/cloud/measured", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		exposed := map[string]bool{}
		for _, name := range strings.Split(rec.Header().Get("Access-Control-Expose-Headers"), ",") {
			exposed[http.CanonicalHeaderKey(strings.TrimSpace(name))] = true
		}
		for _, name := range []string{
			"X-Gomob-Source-Points",
			"X-Gomob-Render-Points",
			"X-Gomob-Coordinate-Schema",
			"X-Gomob-XYZ-SHA256",
			"X-Gomob-Final-B-To-A-SHA256",
		} {
			if !exposed[http.CanonicalHeaderKey(name)] {
				t.Fatalf("%s 未向跨域 Web 客户端暴露 %s", method, name)
			}
		}
	}
}
