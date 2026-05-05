package repo

import "testing"

// 状态机逻辑是 IsTransitionAllowed 的纯函数，单测覆盖；DB 层的 CAS 行为需要集成测试。

func TestInspectionTransitions(t *testing.T) {
	cases := []struct {
		from string
		to   string
		want bool
	}{
		// 主线
		{"created", "scanning", true},
		{"scanning", "preliminary", true},
		{"preliminary", "pending_review", true},
		{"pending_review", "closed", true},
		// 任意状态可提前关闭
		{"created", "closed", true},
		{"scanning", "closed", true},
		{"preliminary", "closed", true},
		// 非法跳转
		{"created", "preliminary", false},      // 跳过 scanning
		{"scanning", "pending_review", false},  // 跳过 preliminary
		{"closed", "scanning", false},          // 关闭后不能复活
		{"preliminary", "scanning", false},     // 不能回退
		{"unknown", "closed", false},           // 未知起点
	}
	for _, c := range cases {
		if got := IsTransitionAllowed(c.from, c.to); got != c.want {
			t.Errorf("IsTransitionAllowed(%q,%q) = %v, want %v", c.from, c.to, got, c.want)
		}
	}
}

func TestValidTransitionTo(t *testing.T) {
	if !validTransitionTo([]string{"created", "scanning"}, "scanning") {
		t.Error("created→scanning 应允许")
	}
	if !validTransitionTo([]string{"scanning", "preliminary"}, "closed") {
		t.Error("scanning/preliminary→closed 应允许")
	}
	if validTransitionTo([]string{"created"}, "closed") == false {
		t.Error("created→closed 应允许")
	}
	if validTransitionTo([]string{"closed"}, "scanning") {
		t.Error("closed→scanning 应禁止")
	}
}
