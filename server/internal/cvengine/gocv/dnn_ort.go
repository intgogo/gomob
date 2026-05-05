//go:build linux
// +build linux

package gocv

/*
#include <stdlib.h>
#include "ocv_dnn.h"
#include "ocv_dnn_ort.h"
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

func CreateORTMetric(gpuId, modelId int, weights []byte, clusters int, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MetricIn)

			res := C.FloatVector{}

			C.ORTSession_RunMetric((C.ORTSession)(net.p), data.InputBlob, inputNames, outputNames, C.int(clusters), &res)

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

func CreateORTClassify(gpuId, modelId int, weights []byte, classes []string, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ClassifyIn)

			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunClassify((C.ORTSession)(net.p), data.InputBlob, inputNames, outputNames, &cIds, &cScores)
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

func CreateORTCom(gpuId, modelId int, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		if iSize.Y == 48 && iSize.X <= 0 { // OCR
			optShapes = toCStrings([]string{"x:1x3x48x32", "x:1x3x48x336", "x:1x3x48x960"})
		}

		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		if iSize.Y == 48 && iSize.X <= 0 {
			net.maxInputSize = image.Point{X: 960, Y: 48}
		}
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ComIn)

			res := C.FloatVector{}

			C.ORTSession_RunInference((C.ORTSession)(net.p), data.InputBlob, inputNames, outputNames, &res)

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

func CreateORTYolo(gpuId, modelId int, classes []string, weights []byte, strides, anchors []int) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		cShapes := C.IntVector{}
		C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
		shapes := toGoInts(cShapes)
		iChan := shapes[0]
		iSize := image.Point{X: shapes[2], Y: shapes[1]}
		C.IntVector_Release(cShapes)

		net.SetParams(iSize, iChan, 1.0/255.0, NewScalar(0, 0, 0, 0))
		net.inChan = make(chan interface{})
		net.classes = classes

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		cStrides := toCIntVector(strides)
		cAnchors := toCIntVector(anchors)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(YoloIn)

			cBoxes := C.Rects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunYolo((C.ORTSession)(net.p), data.InputBlob, data.InputScale, data.InputScale, inputNames, outputNames, cStrides, cAnchors,
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

func CreateORTMask(gpuId, modelId int, classes []string, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MaskIn)

			cContours := C.Contours{}
			cRR := C.RotatedRects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunMask((C.ORTSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY, inputNames, outputNames,
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

func CreateORTDB(gpuId, modelId int, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(DBIn)

			cRR := C.RotatedRects{}
			cScores := C.FloatVector{}

			C.ORTSession_RunDB((C.ORTSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY, inputNames, outputNames,
				C.float(0.3), C.float(data.ConfThreshold), &cRR, &cScores)

			out := DBOut{
				RRects: toGoRotatedRects(cRR),
				Scores: toGoFloats(cScores),
			}

			C.RotatedRects_Release(cRR)
			C.FloatVector_Release(cScores)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}

func CreateORTYolact(gpuId, modelId int, classes []string, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)
			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}
			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MaskIn)

			cContours := C.Contours{}
			cRR := C.RotatedRects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunYolact((C.ORTSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY, inputNames, outputNames,
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

func CreateORTCenterFace(gpuId, modelId int, weights []byte, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(CenterNetIn)

			cBoxes := C.Rects{}
			cLandmarks := C.FloatVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunCenterFace((C.ORTSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY, inputNames, outputNames,
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

func CreateORTCenterPose(gpuId, modelId int, weights []byte, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(CenterNetIn)

			cBoxes := C.Rects{}
			cLandmarks := C.FloatVector{}
			cScores := C.FloatVector{}

			C.ORTSession_RunCenterPose((C.ORTSession)(net.p), data.InputBlob, data.ScaleX, data.ScaleY, inputNames, outputNames,
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

func CreateORTMatting(gpuId, modelId int, weights []byte, iSize image.Point, iChan int,
	std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		optShapes := C.CStrings{}
		defer C.CStrings_Release(optShapes)
		bModel := toByteVector(weights)
		net := Net{p: unsafe.Pointer(C.ORTSession_Create(*bModel, C.int(gpuId), C.int(modelId), optShapes))}
		if net.p == nil {
			c <- &net
			return
		}

		if iChan <= 0 {
			cShapes := C.IntVector{}
			C.ORTSession_GetInputShapes(C.ORTSession(net.p), &cShapes)
			shapes := toGoInts(cShapes)

			iChan = shapes[0]
			iSize = image.Point{X: shapes[2], Y: shapes[1]}

			C.IntVector_Release(cShapes)
		}

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		inputNames := C.CStrings{}
		C.ORTSession_GetInputNames(C.ORTSession(net.p), &inputNames)
		defer C.CStrings_Release(inputNames)

		outputNames := C.CStrings{}
		C.ORTSession_GetOutputNames(C.ORTSession(net.p), &outputNames)
		defer C.CStrings_Release(outputNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MattingIn)
			out := MattingOut{
				Matting: NewMat(),
			}

			C.ORTSession_RunMatting((C.ORTSession)(net.p), data.InputBlob, inputNames, outputNames, out.Matting.p)

			data.OutChan <- out
		}
	}()

	net := <-c
	return net
}
