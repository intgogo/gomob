package laser

import (
	"bytes"
	"math"
	"testing"
)

func TestPCDRoundTrip(t *testing.T) {
	in := []float32{1, 2, 3, -4.5, 1000.25, -20000, 0, 0, 0}
	enc, err := EncodePCDBinary(in)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if !bytes.Contains(enc, []byte("DATA binary\n")) {
		t.Error("头缺 DATA binary")
	}
	if !bytes.Contains(enc, []byte("POINTS 3\n")) {
		t.Error("头缺 POINTS 3")
	}
	out, err := DecodePCDBinary(enc)
	if err != nil {
		t.Fatalf("解码失败: %v", err)
	}
	if len(out) != len(in) {
		t.Fatalf("长度不符: got %d want %d", len(out), len(in))
	}
	for i := range in {
		if math.Abs(float64(out[i]-in[i])) > 1e-4 {
			t.Errorf("第 %d 值 %.4f != %.4f", i, out[i], in[i])
		}
	}
}

func TestPCDEmpty(t *testing.T) {
	enc, err := EncodePCDBinary(nil)
	if err != nil {
		t.Fatalf("空云编码应成功: %v", err)
	}
	if !bytes.Contains(enc, []byte("POINTS 0\n")) {
		t.Error("空云应 POINTS 0")
	}
	out, err := DecodePCDBinary(enc)
	if err != nil {
		t.Fatalf("空云解码失败: %v", err)
	}
	if len(out) != 0 {
		t.Errorf("空云应解出 0 点，得 %d", len(out))
	}
}

func TestPCDBadLength(t *testing.T) {
	if _, err := EncodePCDBinary([]float32{1, 2}); err == nil {
		t.Error("非 3 倍数长度应报错")
	}
}

func TestLaserObjectKey(t *testing.T) {
	if got := LaserObjectKey("sess-abc", "fused"); got != "laser-scans/sess-abc/fused.pcd" {
		t.Errorf("对象键 = %q", got)
	}
}
