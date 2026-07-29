package restore

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"testing"
)

func TestParseBF301208FactoryCalibration(t *testing.T) {
	blob := readFactoryCalibrationFixture(t)
	calibration, err := parseVinCreatorCalibration(blob)
	if err != nil {
		t.Fatal(err)
	}
	if calibration.fileSerial != "BF301208" || calibration.fileVersion != 3 {
		t.Fatalf("文件身份错误: serial=%s version=%d", calibration.fileSerial, calibration.fileVersion)
	}
	assertCalibrationNear(t, calibration.color.PrincipalRow, 1274.610937612, 1e-12, "principal row")
	assertCalibrationNear(t, calibration.color.PrincipalColumn, 2119.555128713, 1e-12, "principal column")
	assertCalibrationNear(t, calibration.color.FocalRow, 5737.022753971, 1e-12, "focal row")
	assertCalibrationNear(t, calibration.color.FocalColumn, 5642.090890116, 1e-12, "focal column")
	assertCalibrationNear(t, calibration.depth.PrincipalColumn, 648, 1e-9, "depth cx")
	assertCalibrationNear(t, calibration.depth.PrincipalRow, 130.86500549316406, 1e-9, "depth cy")
	assertCalibrationNear(t, calibration.depth.Focal, 1229.2099609375, 1e-9, "depth focal")
	assertCalibrationNear(t, calibration.depth.BaselineMM, 49.98929977416992, 1e-9, "baseline")
	if calibration.depth.DataType != 1 {
		t.Fatalf("depth data type=%d，期望 1", calibration.depth.DataType)
	}

	wantRotation := [3][3]float64{
		{0.988181353727503, -0.001554393417785, 0.153281427467200},
		{0.000002789863576, -0.999948403706616, -0.010158243785565},
		{0.153289308620975, 0.010038614729785, -0.988130362895914},
	}
	for row := range wantRotation {
		for column := range wantRotation[row] {
			assertCalibrationNear(t, calibration.color.Rotation[row][column], wantRotation[row][column], 1e-12,
				fmt.Sprintf("R[%d][%d]", row, column))
		}
	}
}

func TestFactoryDistortionMatchesNativeOracle(t *testing.T) {
	model := mustFactoryCalibration(t).color
	tests := []struct {
		x, y         float64
		wantX, wantY float64
	}{
		{x: 0, y: 0, wantX: 0, wantY: 0},
		{x: 100, y: 50, wantX: 100.370720367415, wantY: 50.175944842716},
		{x: -600, y: 180, wantX: -590.624038261995, wantY: 177.530722026526},
		{x: 1500, y: -300, wantX: 1622.479205979885, wantY: -322.923759953778},
	}
	for _, test := range tests {
		gotX, gotY := model.distortPixelDelta(test.x, test.y)
		assertCalibrationNear(t, gotX, test.wantX, 1e-9, "distortion x")
		assertCalibrationNear(t, gotY, test.wantY, 1e-9, "distortion y")
	}
}

func TestFactoryProjectionMatchesNativeOracle(t *testing.T) {
	model := mustFactoryCalibration(t).color
	tests := []struct {
		point            Vec3
		wantRow, wantCol float64
	}{
		{point: Vec3{0, 0, 300}, wantRow: 407.846496710986, wantCol: 1724.833453665334},
		{point: Vec3{0, 0, 500}, wantRow: 408.621037742871, wantCol: 1902.262574310315},
		{point: Vec3{100, 0, 500}, wantRow: -687.194595401080, wantCol: 1902.818528902083},
		{point: Vec3{-100, 40, 700}, wantRow: 1195.535052926923, wantCol: 2293.560555773054},
		{point: Vec3{20, -60, 450}, wantRow: 166.073725215519, wantCol: 1146.066648838434},
	}
	for _, test := range tests {
		column, row, ok := model.projectWorldToColor(test.point)
		if !ok {
			t.Fatalf("投影失败: %+v", test.point)
		}
		assertCalibrationNear(t, row, test.wantRow, 1e-9, "project row")
		assertCalibrationNear(t, column, test.wantCol, 1e-9, "project column")
	}
}

func TestFactoryColorRayRoundTripsProjection(t *testing.T) {
	model := mustFactoryCalibration(t).color
	points := []Vec3{{0, 0, 300}, {0, 0, 500}, {100, 0, 500}, {-100, 40, 700}, {20, -60, 450}}
	for _, point := range points {
		column, row, ok := model.projectWorldToColor(point)
		if !ok {
			t.Fatalf("投影失败: %+v", point)
		}
		origin, direction, err := model.rayFromColorPixel(column, row)
		if err != nil {
			t.Fatalf("逆投影失败: %v", err)
		}
		toPoint := sub3(point, origin)
		nearest := add3(origin, scale3(direction, dot3(toPoint, direction)))
		if distance := norm3(sub3(point, nearest)); distance > 1e-7 {
			t.Fatalf("点未落在逆投影射线上: point=%v distance=%.12g", point, distance)
		}
	}
}

func TestMode25DisparityUsesFactoryMetricScale(t *testing.T) {
	depth := mustFactoryCalibration(t).depth
	fx, fy, cx, cy, err := depth.intrinsicsForProfile(640, 128)
	if err != nil {
		t.Fatal(err)
	}
	assertCalibrationNear(t, fx, 614.60498046875, 1e-9, "scaled fx")
	assertCalibrationNear(t, fy, 614.60498046875, 1e-9, "scaled fy")
	assertCalibrationNear(t, cx, 324, 1e-9, "scaled cx")
	assertCalibrationNear(t, cy, 65.43250274658203, 1e-9, "scaled cy")

	point, ok := depth.pointFromDisparity(1300, 324, 65, 640, 128)
	if !ok {
		t.Fatal("有效视差被拒绝")
	}
	assertCalibrationNear(t, point[2], 378.13750906277164, 1e-9, "metric z")
	if math.Abs(point[0]) > 0.3 || math.Abs(point[1]) > 1e-12 {
		t.Fatalf("主点附近坐标异常: %v", point)
	}
}

func TestFactoryDepthBackprojectionMatchesNativeOracle(t *testing.T) {
	depth := mustFactoryCalibration(t).depth
	tests := []struct {
		column, row int
		want        Vec3
	}{
		{column: 324, row: 65, want: Vec3{0.266098577871274, 0, 378.137509062772}},
		{column: 344, row: 55, want: Vec3{6.41862778084603, 12.3050584059495, 378.137509062772}},
		{column: 304, row: 75, want: Vec3{-5.88643062510349, -12.3050584059495, 378.137509062772}},
		{column: 0, row: 0, want: Vec3{40.2575383972072, -199.341946176382, 378.137509062772}},
		{column: 639, row: 127, want: Vec3{-37.8795824805722, 193.804669893705, 378.137509062772}},
	}
	for _, test := range tests {
		point, ok := depth.pointFromDisparity(1300, test.column, test.row, 640, 128)
		if !ok {
			t.Fatalf("原厂固定向量被拒绝: column=%d row=%d", test.column, test.row)
		}
		for axis := range point {
			assertCalibrationNear(t, point[axis], test.want[axis], 1e-9,
				fmt.Sprintf("depth oracle col=%d row=%d axis=%d", test.column, test.row, axis))
		}
	}
}

func TestFactoryResolverBindsCompleteRigAndProfile(t *testing.T) {
	dir := t.TempDir()
	blob := readFactoryCalibrationFixture(t)
	if err := os.WriteFile(filepath.Join(dir, factoryBF301208File), blob, 0o600); err != nil {
		t.Fatal(err)
	}
	resolver := NewFactoryVinCalibrationResolver(dir)
	key := factoryCalibrationSpecs[0].Key
	calibration, err := resolver.ResolveVinCalibration(key)
	if err != nil {
		t.Fatal(err)
	}
	if calibration.sourceSHA256 != factoryBF301208SHA256 {
		t.Fatalf("SHA 未保留: %s", calibration.sourceSHA256)
	}

	wrongKeys := []VinCalibrationKey{key, key, key, key}
	wrongKeys[0].DepthDeviceSerial = "BF301215"
	wrongKeys[1].ColorDeviceSerial = "OTHER_HLSD8"
	wrongKeys[2].DepthWidth = 1280
	wrongKeys[3].ColorHeight = 256
	for _, wrong := range wrongKeys {
		if _, err := resolver.ResolveVinCalibration(wrong); !errors.Is(err, ErrVinCalibrationUnavailable) {
			t.Fatalf("错误 rig/profile 被接受: %+v err=%v", wrong, err)
		}
	}
}

func TestFactoryParserRejectsCorruption(t *testing.T) {
	tests := []struct {
		name   string
		mutate func([]byte) []byte
	}{
		{name: "截断", mutate: func(blob []byte) []byte { return blob[:len(blob)-1] }},
		{name: "版本错误", mutate: func(blob []byte) []byte { binaryPutUint32(blob, 0x200, 2); return blob }},
		{name: "畸变组数错误", mutate: func(blob []byte) []byte { binaryPutUint32(blob, 0x234, 0); return blob }},
		{name: "深度数据类型错误", mutate: func(blob []byte) []byte { binaryPutUint32(blob, 0x360, 0); return blob }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			blob := append([]byte(nil), readFactoryCalibrationFixture(t)...)
			if _, err := parseVinCreatorCalibration(test.mutate(blob)); err == nil {
				t.Fatal("损坏标定未被拒绝")
			}
		})
	}
}

func TestFactoryParserIgnoresMetadataBufferVersionLookalike(t *testing.T) {
	blob := append([]byte(nil), readFactoryCalibrationFixture(t)...)
	binaryPutUint32(blob, 0x20, 2)
	if _, err := parseVinCreatorCalibration(blob); err != nil {
		t.Fatalf("原厂忽略的 0x20 元数据不应被当作 payload version: %v", err)
	}
}

func TestValidateRequiredFactoryVinCalibrations(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(dir, factoryBF301208File),
		readFactoryCalibrationFixture(t),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRequiredFactoryVinCalibrations(NewFactoryVinCalibrationResolver(dir)); err != nil {
		t.Fatalf("原厂标定齐全却未就绪: %v", err)
	}
}

func TestValidateRequiredFactoryVinCalibrationsRejectsMissingMount(t *testing.T) {
	err := ValidateRequiredFactoryVinCalibrations(NewFactoryVinCalibrationResolver(t.TempDir()))
	if !errors.Is(err, ErrVinCalibrationAssetInvalid) {
		t.Fatalf("已发布标定缺失应返回 ErrVinCalibrationAssetInvalid，实际 %v", err)
	}
}

func readFactoryCalibrationFixture(t *testing.T) []byte {
	t.Helper()
	path := filepath.Join("..", "..", "..", "..", "tests", "vincreator-apk", factoryBF301208File)
	blob, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读取原厂标定 fixture %s: %v", path, err)
	}
	if got := fmt.Sprintf("%x", sha256.Sum256(blob)); got != factoryBF301208SHA256 {
		t.Fatalf("fixture SHA 变化: got=%s want=%s", got, factoryBF301208SHA256)
	}
	return blob
}

func mustFactoryCalibration(t *testing.T) *VinCalibration {
	t.Helper()
	calibration, err := parseVinCreatorCalibration(readFactoryCalibrationFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	return calibration
}

func assertCalibrationNear(t *testing.T, got, want, tolerance float64, field string) {
	t.Helper()
	if math.Abs(got-want) > tolerance {
		t.Fatalf("%s=%.15g，期望 %.15g（容差 %.3g）", field, got, want, tolerance)
	}
}

func binaryPutUint32(blob []byte, offset int, value uint32) {
	blob[offset] = byte(value)
	blob[offset+1] = byte(value >> 8)
	blob[offset+2] = byte(value >> 16)
	blob[offset+3] = byte(value >> 24)
}
