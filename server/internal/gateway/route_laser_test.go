package gateway

import "testing"

// 激光路由必须优先于 asset 的 /v1/scans/{session_key}/result（长前缀优先），且独立目标。
func TestLaserRoutePrecedence(t *testing.T) {
	rt, err := NewRouteTable(DefaultRoutes())
	if err != nil {
		t.Fatalf("建路由表失败: %v", err)
	}
	cases := []struct {
		path       string
		wantTarget string
	}{
		{"/v1/scans/laser", targetLaserWorker},
		{"/v1/scans/laser/42/stop", targetLaserWorker},
		{"/v1/scans/laser/42", targetLaserWorker},
	}
	for _, c := range cases {
		r, _, ok := rt.Match(c.path)
		if !ok {
			t.Errorf("%s 未匹配任何路由", c.path)
			continue
		}
		if r.Target != c.wantTarget {
			t.Errorf("%s → %s，期望 %s", c.path, r.Target, c.wantTarget)
		}
	}

	// 非激光的 /v1/scans/... 当前 gateway 无路由（asset result 路径不经此前缀），
	// 不应被激光路由误抢（RGBD session_key 非 "laser-" 前缀）。
	if r, _, ok := rt.Match("/v1/scans/sess-abc/result"); ok {
		t.Errorf("/v1/scans/sess-abc/result 不应命中 %s（当前无 /v1/scans 通配路由）", r.Target)
	}
}

// 激光路由非公开（须 JWT）。
func TestLaserRouteRequiresAuth(t *testing.T) {
	rt, _ := NewRouteTable(DefaultRoutes())
	r, _, ok := rt.Match("/v1/scans/laser")
	if !ok || r.Public {
		t.Errorf("/v1/scans/laser 应需鉴权(Public=false)，得 ok=%v public=%v", ok, r.Public)
	}
}
