package laser

import (
	"encoding/json"
	"os"
	"testing"
)

func loadRealRefinePCD(t *testing.T, path string) []float32 {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取 PCD %s: %v", path, err)
	}
	xyz, err := DecodePCDBinary(raw)
	if err != nil {
		t.Fatalf("解析 PCD %s: %v", path, err)
	}
	return xyz
}

func loadRealRefineMatrix(t *testing.T, path string) [16]float32 {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取外参 %s: %v", path, err)
	}
	var payload struct {
		BToA []float64 `json:"b_to_a"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil || len(payload.BToA) != 16 {
		t.Fatalf("外参必须是 {\"b_to_a\":[16]}: %v", err)
	}
	var out [16]float32
	for i, value := range payload.BToA {
		out[i] = float32(value)
	}
	return out
}

// TestRefineBToARealFixture 只在显式提供真实 PCD 时运行，供 laser_ab_refine harness 调用。
func TestRefineBToARealFixture(t *testing.T) {
	aPath := os.Getenv("LASER_REFINE_A_PCD")
	bPath := os.Getenv("LASER_REFINE_B_PCD")
	initPath := os.Getenv("LASER_REFINE_INIT_JSON")
	if aPath == "" || bPath == "" || initPath == "" {
		t.Skip("未提供 LASER_REFINE_A_PCD/B_PCD/INIT_JSON")
	}

	a := loadRealRefinePCD(t, aPath)
	b := loadRealRefinePCD(t, bPath)
	init := loadRealRefineMatrix(t, initPath)
	got, stats := RefineBToA(a, b, init, DefaultRefineBToAParams())
	t.Logf("A=%d B=%d applied=%v pairs=%d rms=%.3fmm delta=%.3fmm/%.4f° reason=%s",
		len(a)/3, len(b)/3, stats.Applied, stats.Pairs, stats.RMSMM,
		stats.DeltaTransMM, stats.DeltaRotDeg, stats.Reason)
	if !stats.Applied {
		t.Fatalf("真实点到面精修未采纳: %+v", stats)
	}

	if expectedPath := os.Getenv("LASER_REFINE_EXPECTED_JSON"); expectedPath != "" {
		expected := loadRealRefineMatrix(t, expectedPath)
		transMM, rotDeg := deltaSE3(mat16ToF64(expected), mat16ToF64(got))
		t.Logf("相对期望终态差 %.3fmm/%.4f°", transMM, rotDeg)
		if transMM > 5 || rotDeg > 0.2 {
			t.Fatalf("点到面终态偏离期望：%.3fmm/%.4f°", transMM, rotDeg)
		}
	}
}
