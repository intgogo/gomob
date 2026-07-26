package restore

import (
	"errors"
	"math"
)

const (
	factoryDepthMinMM = 50.0
	factoryDepthMaxMM = 1000.0
)

// intrinsicsForProfile 把原厂 1280×256 mode25 内参等比缩放到实际深度档位。
func (model factoryDepthModel) intrinsicsForProfile(width, height int) (fx, fy, cx, cy float64, err error) {
	if width <= 0 || height <= 0 {
		return 0, 0, 0, 0, errors.New("深度分辨率非法")
	}
	sx := float64(width) / factoryDepthWidth
	sy := float64(height) / factoryDepthHeight
	if math.Abs(sx-sy) > 1e-9 {
		return 0, 0, 0, 0, errors.New("深度档位不是 mode25 等比缩放")
	}
	return model.Focal * sx,
		model.Focal * sy,
		model.PrincipalColumn * sx,
		model.PrincipalRow * sy,
		nil
}

// pointFromDisparity 把 RS-D550 mode25 的 1/8px 视差还原为原厂世界坐标。
// CCameraModel 的第一世界轴对应深度图竖直向上，第二轴对应水平向右；该轴顺序与 JPEG 行/列一致，
// 不能直接套普通 OpenCV 的 (X=水平,Y=竖直) 约定。
func (model factoryDepthModel) pointFromDisparity(
	raw uint16,
	column, row int,
	width, height int,
) (Vec3, bool) {
	if raw == 0 {
		return Vec3{}, false
	}
	fx, _, cx, cy, err := model.intrinsicsForProfile(width, height)
	if err != nil {
		return Vec3{}, false
	}
	// 原厂 restoreImageFlow 同时用 0x348 的 projection focal 计算 Z 与 X/Y；0x354/0x358 只是冗余焦距。
	z := model.Focal * model.BaselineMM / (float64(raw) * factoryDisparityUnit)
	if z <= factoryDepthMinMM || z >= factoryDepthMaxMM || !isFinite(z) {
		return Vec3{}, false
	}
	verticalUp := (cy - float64(row)) * z / fx
	horizontalRight := (float64(column) - cx) * z / fx
	return Vec3{verticalUp, horizontalRight, z}, true
}

// distortPixelDelta 复刻 CCameraModel::calcDistortion。
// 系数顺序为 k,p1,p2,s1,s2；输入输出均为相对主点的像素量，不是归一化坐标。
func (model factoryCameraModel) distortPixelDelta(x, y float64) (float64, float64) {
	k, p1, p2, s1, s2 :=
		model.Distortion[0], model.Distortion[1], model.Distortion[2], model.Distortion[3], model.Distortion[4]
	r2 := x*x + y*y
	return x + k*x*r2 + p1*(3*x*x+y*y) + 2*p2*x*y + s1*r2,
		y + k*y*r2 + p2*(x*x+3*y*y) + 2*p1*x*y + s2*r2
}

func (model factoryCameraModel) applyFOV(rowDelta, columnDelta float64) (float64, float64) {
	radius := math.Hypot(rowDelta, columnDelta)
	if radius <= 1e-12 {
		return rowDelta, columnDelta
	}
	return model.FocalRow * math.Atan(radius/model.FocalRow) * rowDelta / radius,
		model.FocalColumn * math.Atan(radius/model.FocalColumn) * columnDelta / radius
}

// projectWorldToColor 复刻 CCameraModel::FromWorld2Image_Simplified。
// 返回 OpenCV 采样顺序 (column,row)，原厂函数本身的两个输出则是 (row,column)。
func (model factoryCameraModel) projectWorldToColor(point Vec3) (column, row float64, ok bool) {
	camera := add3(matVec3(model.Rotation, point), model.Translation)
	if math.Abs(camera[2]) <= 1e-12 {
		return 0, 0, false
	}
	rowDelta := model.FocalRow * camera[0] / camera[2]
	columnDelta := model.FocalColumn * camera[1] / camera[2]
	rowDelta, columnDelta = model.applyFOV(rowDelta, columnDelta)
	rowDelta, columnDelta = model.distortPixelDelta(rowDelta, columnDelta)
	row = model.PrincipalRow + rowDelta
	column = model.PrincipalColumn + columnDelta
	return column, row, isFinite(column) && isFinite(row)
}

// rayFromColorPixel 把 JPEG (column,row) 反解为原厂世界坐标中的相机中心和射线方向。
// 私有畸变必须数值求逆，不能调用 OpenCV undistortPoints。
func (model factoryCameraModel) rayFromColorPixel(column, row float64) (origin, direction Vec3, err error) {
	fovRow, fovColumn, err := model.inverseDistortion(
		row-model.PrincipalRow,
		column-model.PrincipalColumn,
	)
	if err != nil {
		return Vec3{}, Vec3{}, err
	}
	pinholeRow, pinholeColumn, err := model.inverseFOV(fovRow, fovColumn)
	if err != nil {
		return Vec3{}, Vec3{}, err
	}
	cameraDirection := Vec3{
		pinholeRow / model.FocalRow,
		pinholeColumn / model.FocalColumn,
		1,
	}
	rotationT := transposeMat3(model.Rotation)
	origin = scale3(matVec3(rotationT, model.Translation), -1)
	direction = normalizeVec3(matVec3(rotationT, cameraDirection))
	if norm3(direction) < 1e-12 {
		return Vec3{}, Vec3{}, errors.New("原厂彩色像素射线退化")
	}
	return origin, direction, nil
}

func (model factoryCameraModel) inverseDistortion(targetX, targetY float64) (float64, float64, error) {
	x, y := targetX, targetY
	k, p1, p2, s1, s2 :=
		model.Distortion[0], model.Distortion[1], model.Distortion[2], model.Distortion[3], model.Distortion[4]
	for iteration := 0; iteration < 20; iteration++ {
		gotX, gotY := model.distortPixelDelta(x, y)
		errorX, errorY := gotX-targetX, gotY-targetY
		if math.Hypot(errorX, errorY) <= 1e-10 {
			return x, y, nil
		}
		j11 := 1 + k*(3*x*x+y*y) + 6*p1*x + 2*p2*y + 2*s1*x
		j12 := 2*k*x*y + 2*p1*y + 2*p2*x + 2*s1*y
		j21 := 2*k*x*y + 2*p2*x + 2*p1*y + 2*s2*x
		j22 := 1 + k*(x*x+3*y*y) + 6*p2*y + 2*p1*x + 2*s2*y
		determinant := j11*j22 - j12*j21
		if math.Abs(determinant) <= 1e-15 || !isFinite(determinant) {
			break
		}
		deltaX := (j22*errorX - j12*errorY) / determinant
		deltaY := (-j21*errorX + j11*errorY) / determinant
		x -= deltaX
		y -= deltaY
		if !isFinite(x) || !isFinite(y) {
			break
		}
	}
	return 0, 0, errors.New("原厂私有畸变逆解不收敛")
}

func (model factoryCameraModel) inverseFOV(targetRow, targetColumn float64) (float64, float64, error) {
	row, column := targetRow, targetColumn
	for iteration := 0; iteration < 20; iteration++ {
		gotRow, gotColumn := model.applyFOV(row, column)
		errorRow, errorColumn := gotRow-targetRow, gotColumn-targetColumn
		if math.Hypot(errorRow, errorColumn) <= 1e-10 {
			return row, column, nil
		}
		stepRow := 1e-4 * math.Max(1, math.Abs(row))
		stepColumn := 1e-4 * math.Max(1, math.Abs(column))
		rowPlus, columnAtRowPlus := model.applyFOV(row+stepRow, column)
		rowAtColumnPlus, columnPlus := model.applyFOV(row, column+stepColumn)
		j11 := (rowPlus - gotRow) / stepRow
		j21 := (columnAtRowPlus - gotColumn) / stepRow
		j12 := (rowAtColumnPlus - gotRow) / stepColumn
		j22 := (columnPlus - gotColumn) / stepColumn
		determinant := j11*j22 - j12*j21
		if math.Abs(determinant) <= 1e-15 || !isFinite(determinant) {
			break
		}
		deltaRow := (j22*errorRow - j12*errorColumn) / determinant
		deltaColumn := (-j21*errorRow + j11*errorColumn) / determinant
		row -= deltaRow
		column -= deltaColumn
		if !isFinite(row) || !isFinite(column) {
			break
		}
	}
	return 0, 0, errors.New("原厂 FOV 逆解不收敛")
}

func transposeMat3(matrix [3][3]float64) [3][3]float64 {
	return [3][3]float64{
		{matrix[0][0], matrix[1][0], matrix[2][0]},
		{matrix[0][1], matrix[1][1], matrix[2][1]},
		{matrix[0][2], matrix[1][2], matrix[2][2]},
	}
}
