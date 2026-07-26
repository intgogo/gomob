package laser

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSiteFramingManualRouteRejectsUnderconstrainedInput(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)))
	mux := http.NewServeMux()
	h.Mount(mux)

	body, err := json.Marshal(manualSiteFramingRequest{
		UnitAIP: "192.168.9.101",
		UnitBIP: "192.168.9.102",
		Pairs: []manualSiteFramingPair{
			manualSiteFramingTestPair("P1", 100, 110, 140, 130),
			manualSiteFramingTestPair("P2", 300, 120, 340, 140),
			manualSiteFramingTestPair("P3", 180, 260, 220, 280),
			manualSiteFramingTestPair("P4", 420, 300, 460, 320),
		},
	})
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/scans/laser/site-framing/manual", bytes.NewReader(body))
	req.Header.Set("X-Gomob-User-Id", "42")
	req.Header.Set("X-Gomob-Roles", "admin")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code == http.StatusNotFound {
		t.Fatal("手动 RGB 点对端点不应再返回 404")
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("欠约束输入应返回 200+ok:false，得 %d: %s", rec.Code, rec.Body.String())
	}
	var got manualSiteFramingResp
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("响应不是 JSON: %v body=%s", err, rec.Body.String())
	}
	if got.OK {
		t.Fatalf("欠约束输入不能标成成功: %+v", got)
	}
	if got.NCommon != 4 {
		t.Fatalf("点对数量应回显 4，得 %d", got.NCommon)
	}
	if !strings.Contains(got.Message, "已解析 4 组") || !strings.Contains(got.Message, "缺少米制平移尺度") {
		t.Fatalf("错误原因应说明几何欠约束，得: %q", got.Message)
	}
}

func TestSiteFramingManualRequiresLogin(t *testing.T) {
	h := NewHandler(Config{}, nil, nil, nil, slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)))
	req := httptest.NewRequest(http.MethodPost, "/v1/scans/laser/site-framing/manual", strings.NewReader(`{"pairs":[]}`))
	rec := httptest.NewRecorder()

	h.SiteFramingManual(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("未登录应返回 401，得 %d", rec.Code)
	}
}

func manualSiteFramingTestPair(label string, ax, ay, bx, by float64) manualSiteFramingPair {
	return manualSiteFramingPair{
		Label: label,
		A: manualSiteFramingPointObs{
			Role:      "A",
			ShotIndex: 0,
			Heading:   12.5,
			X:         ax,
			Y:         ay,
			U:         ax / 640,
			V:         ay / 480,
			W:         640,
			H:         480,
		},
		B: manualSiteFramingPointObs{
			Role:      "B",
			ShotIndex: 1,
			Heading:   -35,
			X:         bx,
			Y:         by,
			U:         bx / 640,
			V:         by / 480,
			W:         640,
			H:         480,
		},
	}
}
