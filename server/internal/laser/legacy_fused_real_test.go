package laser

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"math"
	"os"
	"strconv"
	"testing"
)

func TestLegacyFusedGoldenReplay(t *testing.T) {
	livePath := os.Getenv("LEGACY_LIVE_PCD")
	backgroundPath := os.Getenv("LEGACY_BG_PCD")
	if livePath == "" || backgroundPath == "" {
		t.Skip("未提供 legacy fused 真数据")
	}
	load := func(path string) []float32 {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		points, err := DecodePCDBinary(raw)
		if err != nil {
			t.Fatal(err)
		}
		return points
	}
	live := load(livePath)
	background := load(backgroundPath)
	if expected := os.Getenv("LEGACY_EXPECTED_BG_XYZ_SHA256"); expected != "" {
		h := sha256.New()
		var buffer [4]byte
		for _, value := range background {
			binary.LittleEndian.PutUint32(buffer[:], math.Float32bits(value))
			_, _ = h.Write(buffer[:])
		}
		if actual := hex.EncodeToString(h.Sum(nil)); actual != expected {
			t.Fatalf("legacy 背景 checksum 不一致: got=%s want=%s", actual, expected)
		}
	}

	foreground := SubtractBackground(live, background, DefaultBackgroundParams())
	if raw := os.Getenv("LEGACY_EXPECTED_FOREGROUND_POINTS"); raw != "" {
		expected, err := strconv.Atoi(raw)
		if err != nil {
			t.Fatal(err)
		}
		if len(foreground)/3 != expected {
			t.Fatalf("legacy 前景点数不一致: got=%d want=%d", len(foreground)/3, expected)
		}
	}

	var ground GroundPlane
	if err := json.Unmarshal([]byte(os.Getenv("LEGACY_GROUND_JSON")), &ground); err != nil || !ground.Valid {
		t.Fatalf("LEGACY_GROUND_JSON 无效: %v", err)
	}
	var expected Dimensions
	if err := json.Unmarshal([]byte(os.Getenv("LEGACY_EXPECTED_DIMENSIONS_JSON")), &expected); err != nil {
		t.Fatalf("LEGACY_EXPECTED_DIMENSIONS_JSON 无效: %v", err)
	}
	params := GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
	params.SupportBG = background
	params.WidthSupportFrac = 0.15
	params.WidthBinMM = 1
	params.SpanTrimPct = 0.5
	dims, _, _ := MeasureFull(foreground, params, DefaultAxleParams())
	if !dims.Valid {
		t.Fatalf("legacy golden 测量无效: %+v", dims)
	}
	const toleranceMM = 0.2
	if math.Abs(float64(dims.LengthMM-expected.LengthMM)) > toleranceMM ||
		math.Abs(float64(dims.WidthMM-expected.WidthMM)) > toleranceMM ||
		math.Abs(float64(dims.HeightMM-expected.HeightMM)) > toleranceMM {
		t.Fatalf("legacy golden 外廓漂移: got=%.3f×%.3f×%.3f want=%.3f×%.3f×%.3f",
			dims.LengthMM, dims.WidthMM, dims.HeightMM,
			expected.LengthMM, expected.WidthMM, expected.HeightMM)
	}
	t.Logf("LEGACY_GOLDEN:PASS foreground=%d LWH=%.3f/%.3f/%.3f",
		len(foreground)/3, dims.LengthMM, dims.WidthMM, dims.HeightMM)
}
