package repo

import "testing"

func TestNullableInt64Scanner(t *testing.T) {
	var value *int64
	scanner := newNullInt64Target(&value)
	if err := scanner.Scan(nil); err != nil {
		t.Fatalf("扫描 NULL: %v", err)
	}
	if value != nil {
		t.Fatalf("NULL 应保持 nil，得 %d", *value)
	}
	if err := scanner.Scan(int64(42)); err != nil {
		t.Fatalf("扫描整数: %v", err)
	}
	if value == nil || *value != 42 {
		t.Fatalf("期望 42，得 %#v", value)
	}
}
