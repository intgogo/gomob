package repo

import "testing"

// 状态机纯函数测试（DB CAS 由集成测试覆盖）。
func TestVehicleModelTransitions(t *testing.T) {
	cases := []struct {
		from string
		to   string
		want bool
	}{
		{"draft", "published", true},
		{"draft", "archived", true},
		{"published", "archived", true},
		// 非法
		{"published", "draft", false},   // 已发布不能回 draft
		{"archived", "draft", false},    // 终态
		{"archived", "published", false},
		{"unknown", "published", false},
	}
	for _, c := range cases {
		if got := IsVMTransitionAllowed(c.from, c.to); got != c.want {
			t.Errorf("IsVMTransitionAllowed(%q,%q)=%v want=%v", c.from, c.to, got, c.want)
		}
	}
}

func TestValidVMTransitionTo(t *testing.T) {
	if !validVMTransitionTo([]string{"draft"}, "published") {
		t.Error("draft→published 应允许")
	}
	if !validVMTransitionTo([]string{"draft", "published"}, "archived") {
		t.Error("draft/published→archived 应允许")
	}
	if validVMTransitionTo([]string{"archived"}, "draft") {
		t.Error("archived→draft 应禁止")
	}
}
