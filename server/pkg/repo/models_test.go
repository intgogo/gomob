package repo

import "testing"

func TestModelTransitions(t *testing.T) {
	cases := []struct {
		from, to string
		want     bool
	}{
		{"draft", "canary", true},
		{"draft", "active", true},
		{"draft", "archived", true},
		{"canary", "active", true},
		{"canary", "archived", true},
		{"active", "archived", true},
		// 非法
		{"active", "draft", false},
		{"active", "canary", false},   // 不能从 active 回 canary（要先 archive 后再 canary）
		{"archived", "active", false},
		{"archived", "canary", false},
		{"unknown", "active", false},
	}
	for _, c := range cases {
		if got := IsModelTransitionAllowed(c.from, c.to); got != c.want {
			t.Errorf("IsModelTransitionAllowed(%q,%q)=%v want=%v", c.from, c.to, got, c.want)
		}
	}
}
