package repo

import "testing"

func TestLLMTransitions(t *testing.T) {
	cases := []struct {
		from, to string
		want     bool
	}{
		{"draft", "active", true},
		{"draft", "archived", true},
		{"active", "archived", true},
		{"active", "draft", false},   // active 不回 draft
		{"archived", "active", false},
		{"archived", "draft", false},
		{"unknown", "active", false},
	}
	for _, c := range cases {
		if got := IsLLMTransitionAllowed(c.from, c.to); got != c.want {
			t.Errorf("IsLLMTransitionAllowed(%q,%q)=%v want=%v", c.from, c.to, got, c.want)
		}
	}
}
