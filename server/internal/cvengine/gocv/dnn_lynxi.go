//go:build linux
// +build linux

package gocv

/*
#include <stdlib.h>
#include "ocv_dnn.h"
#include "ocv_dnn_lynxi.h"
#include "ocv_cuda.h"
#include "ocv_imgproc.h"
*/
import "C"
import (
	// "fmt"
	"image"
	"runtime"
	"unsafe"
)

func CreateLynxiYolo(gpuId int, classes []string, weights []byte, strides, anchors []int, iSize image.Point, iChan int) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan == 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, 1.0/255.0, NewScalar(0, 0, 0, 0))
		net.inChan = make(chan interface{})
		net.classes = classes

		cStrides := toCIntVector(strides)
		cAnchors := toCIntVector(anchors)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(YoloIn)

			cBoxes := C.Rects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunYolo((C.LynxiSession)(net.p), data.InputBlob, data.InputScale, data.InputScale, cStrides, cAnchors,
				C.float(data.ConfThreshold), C.float(data.NMSThreshold), &cBoxes, &cIds, &cScores)

			out := YoloOut{
				Boxes:  toGoRects(cBoxes),
				Ids:    toGoInts(cIds),
				Scores: toGoFloats(cScores),
			}

			C.Rects_Release(cBoxes)
			C.IntVector_Release(cIds)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiMask(gpuId int, classes []string, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MaskIn)

			cContours := C.Contours{}
			cRR := C.RotatedRects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunMask((C.LynxiSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY,
				C.float(data.ConfThreshold), C.float(data.MaskThreshold), C.float(data.NMSThreshold),
				&cContours, &cRR, &cIds, &cScores)

			out := MaskOut{
				Contours: toGoContours(cContours),
				RRects:   toGoRotatedRects(cRR),
				Ids:      toGoInts(cIds),
				Scores:   toGoFloats(cScores),
			}

			C.Contours_Release(cContours)
			C.RotatedRects_Release(cRR)
			C.IntVector_Release(cIds)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiYolact(gpuId int, classes []string, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MaskIn)

			cContours := C.Contours{}
			cRR := C.RotatedRects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunYolact((C.LynxiSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY,
				C.float(data.ConfThreshold), C.float(data.MaskThreshold), C.float(data.NMSThreshold),
				&cContours, &cRR, &cIds, &cScores)

			out := MaskOut{
				Contours: toGoContours(cContours),
				RRects:   toGoRotatedRects(cRR),
				Ids:      toGoInts(cIds),
				Scores:   toGoFloats(cScores),
			}

			C.Contours_Release(cContours)
			C.RotatedRects_Release(cRR)
			C.IntVector_Release(cIds)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiMetric(gpuId int, weights []byte, clusters int, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MetricIn)

			res := C.FloatVector{}

			C.LynxiSession_RunMetric((C.LynxiSession)(net.p), data.InputBlob, C.int(clusters), &res)

			out := MetricOut{
				Embeddings: toGoFloats(res),
			}
			C.FloatVector_Release(res)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiCom(gpuId int, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ComIn)

			res := C.FloatVector{}

			C.LynxiSession_RunInference((C.LynxiSession)(net.p), data.InputBlob, &res)

			out := ComOut{
				Val: toGoFloats(res),
			}
			C.FloatVector_Release(res)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiMatting(gpuId int, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MattingIn)

			res := MattingOut{
				Matting: NewMat(),
			}

			C.LynxiSession_RunMatting((C.LynxiSession)(net.p), data.InputBlob, res.Matting.p)

			data.OutChan <- res
		}
	}()

	net := <-c
	return net
}

func CreateLynxiCenterFace(gpuId int, weights []byte, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(CenterNetIn)

			cBoxes := C.Rects{}
			cLandmarks := C.FloatVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunCenterFace((C.LynxiSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY,
				C.float(data.ConfThreshold), C.float(data.NMSThreshold), &cBoxes, &cLandmarks, &cScores)
			out := CenterNetOut{
				Boxes:     toGoRects(cBoxes),
				Landmarks: toGoFloats(cLandmarks),
				Scores:    toGoFloats(cScores),
			}

			C.Rects_Release(cBoxes)
			C.FloatVector_Release(cLandmarks)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiCenterPose(gpuId int, weights []byte, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(CenterNetIn)

			cBoxes := C.Rects{}
			cLandmarks := C.FloatVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunCenterPose((C.LynxiSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY,
				C.float(data.ConfThreshold), C.float(data.NMSThreshold), &cBoxes, &cLandmarks, &cScores)
			out := CenterNetOut{
				Boxes:     toGoRects(cBoxes),
				Landmarks: toGoFloats(cLandmarks),
				Scores:    toGoFloats(cScores),
			}

			C.Rects_Release(cBoxes)
			C.FloatVector_Release(cLandmarks)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateLynxiClassify(gpuId int, weights []byte, classes []string, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.LynxiSession_Create(*bModel, C.int(gpuId))), nativeKind: netNativeLynxi}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.LynxiSession_GetInputShapes(C.LynxiSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ClassifyIn)

			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.LynxiSession_RunClassify((C.LynxiSession)(net.p), data.InputBlob, &cIds, &cScores)
			out := ClassifyOut{
				Ids:    toGoInts(cIds),
				Scores: toGoFloats(cScores),
			}
			C.IntVector_Release(cIds)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}
