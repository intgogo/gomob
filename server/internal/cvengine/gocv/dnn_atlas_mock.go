// +build windows

package gocv

import (
	"image"
)

func CreateAtlasYolo(gpuId int, classes []string, weights []byte, strides, anchors []int) *Net {
	return nil
}
func CreateAtlasCom(framework string, gpuId int, cfgs, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	return nil
}
func CreateAtlasClassify(framework string, gpuId int, weights []byte, classes []string, std float64, mean Scalar) *Net {
	return nil
}
