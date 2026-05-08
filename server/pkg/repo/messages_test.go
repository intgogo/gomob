package repo

import (
	"database/sql"
	"testing"
	"time"
)

func TestConversationP2PKeyIsStable(t *testing.T) {
	if got, want := p2pKey(7, 3), "3:7"; got != want {
		t.Fatalf("p2pKey(7,3)=%q want %q", got, want)
	}
	if got, want := p2pKey(3, 7), "3:7"; got != want {
		t.Fatalf("p2pKey(3,7)=%q want %q", got, want)
	}
}

func TestMessageNullHelpers(t *testing.T) {
	if got := nullStringPtr(sql.NullString{}); got != nil {
		t.Fatalf("nullStringPtr invalid = %v, want nil", *got)
	}
	s := nullStringPtr(sql.NullString{String: "c-1", Valid: true})
	if s == nil || *s != "c-1" {
		t.Fatalf("nullStringPtr valid = %v", s)
	}

	now := time.Now().UTC()
	if got := nullTimePtr(sql.NullTime{}); got != nil {
		t.Fatalf("nullTimePtr invalid = %v, want nil", *got)
	}
	ts := nullTimePtr(sql.NullTime{Time: now, Valid: true})
	if ts == nil || !ts.Equal(now) {
		t.Fatalf("nullTimePtr valid = %v want %v", ts, now)
	}
}
