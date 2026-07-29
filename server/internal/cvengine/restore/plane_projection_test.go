package restore

import (
	"encoding/binary"
	"math"
	"testing"
)

func TestBackprojectColorOBBUsesFactoryProjection(t *testing.T) {
	const width, height = 10, 2
	calibration := mustFactoryCalibration(t)
	depth := make([]byte, width*height*2)
	for index := 0; index < width*height; index++ {
		binary.LittleEndian.PutUint16(depth[index*2:], 1300)
	}

	minColumn, minRow := math.Inf(1), math.Inf(1)
	maxColumn, maxRow := math.Inf(-1), math.Inf(-1)
	for row := 0; row < height; row++ {
		for column := 0; column < width; column++ {
			point, ok := calibration.depth.pointFromDisparity(1300, column, row, width, height)
			if !ok {
				t.Fatal("合成视差点无效")
			}
			colorColumn, colorRow, ok := calibration.color.projectWorldToColor(point)
			if !ok {
				t.Fatal("合成点投影失败")
			}
			minColumn = math.Min(minColumn, colorColumn)
			maxColumn = math.Max(maxColumn, colorColumn)
			minRow = math.Min(minRow, colorRow)
			maxRow = math.Max(maxRow, colorRow)
		}
	}
	obb := [4][2]float64{
		{minColumn - 1, minRow - 1},
		{maxColumn + 1, minRow - 1},
		{maxColumn + 1, maxRow + 1},
		{minColumn - 1, maxRow + 1},
	}
	points, err := backprojectColorOBB(depth, width, height, calibration, obb, 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(points) != width*height {
		t.Fatalf("原厂投影筛点数=%d，期望 %d", len(points), width*height)
	}
}

func TestBackprojectColorOBBRejectsUnrelatedRegion(t *testing.T) {
	const width, height = 10, 2
	calibration := mustFactoryCalibration(t)
	depth := make([]byte, width*height*2)
	for index := 0; index < width*height; index++ {
		binary.LittleEndian.PutUint16(depth[index*2:], 1300)
	}
	farAway := [4][2]float64{{10000, 10000}, {10100, 10000}, {10100, 10100}, {10000, 10100}}
	points, err := backprojectColorOBB(depth, width, height, calibration, farAway, 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(points) != 0 {
		t.Fatalf("无关 OBB 不应选中深度点，实际 %d", len(points))
	}
}
