package asset

import "testing"

func TestFusionEnqueueParams(t *testing.T) {
	cases := []struct {
		name, kind, scanSession, uploadID string
		wantKey                           string
		wantOK                            bool
	}{
		{"bundle 带 session", KindScan3DBundle, "S-123", "u_abc", "S-123", true},
		{"bundle 缺 session 回退 uploadID", KindScan3DBundle, "", "u_abc", "u_abc", true},
		{"bundle session 含空白", KindScan3DBundle, "  S-123  ", "u_abc", "S-123", true},
		{"普通 scan3d 不触发", "scan3d", "S-123", "u_abc", "", false},
		{"vin_plate 不触发", "vin_plate", "", "u_abc", "", false},
		{"空 kind 不触发", "", "", "u_abc", "", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			key, ok := fusionEnqueueParams(c.kind, c.scanSession, c.uploadID)
			if ok != c.wantOK || key != c.wantKey {
				t.Fatalf("fusionEnqueueParams(%q,%q,%q)=(%q,%v) 期望(%q,%v)",
					c.kind, c.scanSession, c.uploadID, key, ok, c.wantKey, c.wantOK)
			}
		})
	}
}
