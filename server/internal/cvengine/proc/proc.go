// Package proc —— ivv 业务流程层（从 gosmart/apps/api/ivv/proc.go 迁移）。
//
// M-S10 Phase 2 入口：仅提供 ProcVinCharacterCompare（单字符×字符比对），
// 不依赖模型加载、不依赖 vin-ref 厂家库；完整 ProcVinCompare（含 ProcVINDet 检测）留 Phase 2.x。
package proc

import (
	"errors"
	"fmt"

	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/judge"
)

// ProcVinCharacterCompare 比对两个字符的字形图。
//
//	imgBytes1, imgBytes2: 二值字符图 / 灰度字符图（任意尺寸；本函数会按 IMReadGrayScale 解码）
//	method:               judge.FONT_DIST_IOU(0) 或 judge.FONT_DIST_CHAMFER(1)
//
// 返回：
//
//	method=IOU：    0..1（越大越相似，1 = 完全一致）
//	method=CHAMFER：0+（越小越相似，0 = 完全一致）
//
// 错误：图片解码失败 / 空 / method 不合法。
func ProcVinCharacterCompare(imgBytes1, imgBytes2 []byte, logId string, method int) (float64, error) {
	if method != judge.FONT_DIST_IOU && method != judge.FONT_DIST_CHAMFER {
		return 0, errors.New("method 必须是 0(IOU) 或 1(CHAMFER)")
	}
	if len(imgBytes1) == 0 || len(imgBytes2) == 0 {
		return 0, errors.New("image bytes 为空")
	}

	img1, err := gocv.IMDecode(imgBytes1, gocv.IMReadGrayScale)
	if err != nil {
		return 0, fmt.Errorf("img1 解码失败: %w", err)
	}
	defer func() { _ = img1.Release() }()
	if img1.Empty() {
		return 0, errors.New("img1 解码后为空")
	}

	img2, err := gocv.IMDecode(imgBytes2, gocv.IMReadGrayScale)
	if err != nil {
		return 0, fmt.Errorf("img2 解码失败: %w", err)
	}
	defer func() { _ = img2.Release() }()
	if img2.Empty() {
		return 0, errors.New("img2 解码后为空")
	}

	val := judge.CalculateVinFontDifference(img1, img2, method, false, logId)
	return val, nil
}
