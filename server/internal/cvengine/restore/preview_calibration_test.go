package restore

import (
	"math"
	"os"
	"path/filepath"
	"testing"
)

func TestPreviewProjectionExportsFactoryGeometryWithoutAliasing(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, factoryBF301208File), readFactoryCalibrationFixture(t), 0o600); err != nil {
		t.Fatal(err)
	}
	calibration, err := NewFactoryVinCalibrationResolver(dir).ResolveVinCalibration(factoryCalibrationSpecs[0].Key)
	if err != nil {
		t.Fatal(err)
	}

	first, err := calibration.PreviewProjection()
	if err != nil {
		t.Fatal(err)
	}
	if first.ContractVersion != 1 || first.ProjectionModel != "vincreator_factory_v3" ||
		first.OcclusionMetric != "absolute_camera_z" {
		t.Fatalf("预览契约身份错误: %+v", first)
	}
	if first.Key != factoryCalibrationSpecs[0].Key {
		t.Fatalf("完整 rig/profile 未原样导出: %+v", first.Key)
	}
	if first.CalibrationSHA256 != factoryBF301208SHA256 || first.CalibrationVersion != 3 {
		t.Fatalf("标定审计身份错误: sha=%s version=%d", first.CalibrationSHA256, first.CalibrationVersion)
	}
	assertCalibrationNear(t, first.Depth.DisparityFocal, 1229.2099609375, 1e-9, "disparity focal")
	assertCalibrationNear(t, first.Depth.ProjectionFocalX, 614.60498046875, 1e-9, "profile fx")
	assertCalibrationNear(t, first.Depth.ProjectionFocalY, 614.60498046875, 1e-9, "profile fy")
	assertCalibrationNear(t, first.Depth.PrincipalColumn, 324, 1e-9, "profile cx")
	assertCalibrationNear(t, first.Depth.PrincipalRow, 65.43250274658203, 1e-9, "profile cy")

	first.Color.Distortion[0] = 999
	first.Color.Rotation[0] = 999
	first.Color.TranslationMM[0] = 999
	second, err := calibration.PreviewProjection()
	if err != nil {
		t.Fatal(err)
	}
	if second.Color.Distortion[0] == 999 || second.Color.Rotation[0] == 999 || second.Color.TranslationMM[0] == 999 {
		t.Fatal("导出快照与 resolver 内部数组发生别名")
	}
}

func TestPreviewProjectionMatchesCrossPlatformVectors(t *testing.T) {
	calibration := mustFactoryCalibration(t)
	calibration.key = factoryCalibrationSpecs[0].Key
	calibration.sourceSHA256 = factoryBF301208SHA256
	projection, err := calibration.PreviewProjection()
	if err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		column, row  int
		wantX, wantY float64
	}{
		{column: 324, row: 65, wantX: 279.38392092918923, wantY: 62.21943173082844},
		{column: 344, row: 55, wantX: 306.56828818086933, wantY: 48.60381214126974},
		{column: 304, row: 75, wantX: 252.1950535978917, wantY: 75.82674330390832},
	}
	for _, test := range tests {
		x, y, ok := projectPreviewVector(projection, 1300, test.column, test.row, 640, 128)
		if !ok {
			t.Fatalf("固定向量投影失败: column=%d row=%d", test.column, test.row)
		}
		assertCalibrationNear(t, x, test.wantX, 1e-9, "preview x")
		assertCalibrationNear(t, y, test.wantY, 1e-9, "preview y")
	}
}

func projectPreviewVector(
	calibration VinPreviewCalibration,
	raw uint16,
	column, row int,
	outputWidth, outputHeight int,
) (float64, float64, bool) {
	depth := calibration.Depth
	z := depth.DisparityFocal * depth.BaselineMM / (float64(raw) * depth.DisparityUnit)
	world := [3]float64{
		(depth.PrincipalRow - float64(row)) * z / depth.ProjectionFocalY,
		(float64(column) - depth.PrincipalColumn) * z / depth.ProjectionFocalX,
		z,
	}
	rotation := calibration.Color.Rotation
	translation := calibration.Color.TranslationMM
	camera := [3]float64{
		rotation[0]*world[0] + rotation[1]*world[1] + rotation[2]*world[2] + translation[0],
		rotation[3]*world[0] + rotation[4]*world[1] + rotation[5]*world[2] + translation[1],
		rotation[6]*world[0] + rotation[7]*world[1] + rotation[8]*world[2] + translation[2],
	}
	if math.Abs(camera[2]) <= 1e-12 {
		return 0, 0, false
	}
	color := calibration.Color
	rowDelta := color.FocalRow * camera[0] / camera[2]
	columnDelta := color.FocalColumn * camera[1] / camera[2]
	radius := math.Hypot(rowDelta, columnDelta)
	if radius > 1e-12 {
		rowDelta = color.FocalRow * math.Atan(radius/color.FocalRow) * rowDelta / radius
		columnDelta = color.FocalColumn * math.Atan(radius/color.FocalColumn) * columnDelta / radius
	}
	k, p1, p2, s1, s2 := color.Distortion[0], color.Distortion[1], color.Distortion[2], color.Distortion[3], color.Distortion[4]
	radius2 := rowDelta*rowDelta + columnDelta*columnDelta
	distortedRow := rowDelta + k*rowDelta*radius2 + p1*(3*rowDelta*rowDelta+columnDelta*columnDelta) +
		2*p2*rowDelta*columnDelta + s1*radius2
	distortedColumn := columnDelta + k*columnDelta*radius2 + p2*(rowDelta*rowDelta+3*columnDelta*columnDelta) +
		2*p1*rowDelta*columnDelta + s2*radius2
	colorColumn := color.PrincipalColumn + distortedColumn
	colorRow := color.PrincipalRow + distortedRow
	return colorColumn * float64(outputWidth) / float64(calibration.Key.ColorWidth),
		colorRow * float64(outputHeight) / float64(calibration.Key.ColorHeight), true
}
