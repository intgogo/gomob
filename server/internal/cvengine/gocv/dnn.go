package gocv

/*
#include <stdlib.h>
#include "ocv_dnn.h"
#include "ocv_cuda.h"
#include "ocv_imgproc.h"
*/
import "C"
import (
	// "fmt"
	"image"
	"runtime"
	"strconv"
	"strings"
	"unsafe"
)

type YoloOut struct {
	Boxes  []image.Rectangle
	Ids    []int
	Scores []float32
}

type YoloIn struct {
	InputBlob     C.Mat
	InputSize     C.Size
	InputScale    C.double
	ConfThreshold float32
	NMSThreshold  float32
	OutChan       chan YoloOut
}

type CenterNetOut struct {
	Boxes     []image.Rectangle
	Landmarks []float32
	Scores    []float32
}

type CenterNetIn struct {
	InputBlob     C.Mat
	InputSize     C.Size
	ScaleX        C.double
	ScaleY        C.double
	ConfThreshold float32
	NMSThreshold  float32
	OutChan       chan CenterNetOut
}

type MaskOut struct {
	Contours [][]image.Point
	RRects   []RotatedRect
	Ids      []int
	Scores   []float32
}

type MaskIn struct {
	InputBlob     C.Mat
	InputSize     C.Size
	ScaleX        C.double
	ScaleY        C.double
	ConfThreshold float32
	MaskThreshold float32
	NMSThreshold  float32
	OutChan       chan MaskOut
}

type DBOut struct {
	RRects []RotatedRect
	Scores []float32
}

type DBIn struct {
	InputBlob     C.Mat
	InputSize     C.Size
	ScaleX        C.double
	ScaleY        C.double
	ConfThreshold float32
	OutChan       chan DBOut
}

type ClassifyOut struct {
	Ids    []int
	Scores []float32
}

type ClassifyIn struct {
	InputBlob C.Mat
	OutChan   chan ClassifyOut
}

type MetricOut struct {
	Embeddings []float32
}

type MetricIn struct {
	InputBlob C.Mat
	OutChan   chan MetricOut
}

type ComOut struct {
	Val []float32
}

type ComIn struct {
	InputBlob C.Mat
	OutChan   chan ComOut
}

type MattingOut struct {
	Matting Mat
}

type MattingIn struct {
	InputBlob C.Mat
	OutChan   chan MattingOut
}

// Net allows you to create and manipulate comprehensive artificial neural networks.
//
// For further details, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html
type Net struct {
	p            unsafe.Pointer // C.Net
	inputChannel int
	inputSize    C.Size
	std          C.double
	imageMean    C.Scalar
	inChan       chan interface{}
	classes      []string
	maxInputSize image.Point // for dynamic input shape, the max input size is used to limit max image size
}

// NetBackendType is the type for the various different kinds of DNN backends.
type NetBackendType int

const (
	// NetBackendDefault is the default backend.
	NetBackendDefault NetBackendType = 0

	// NetBackendHalide is the Halide backend.
	NetBackendHalide NetBackendType = 1

	// NetBackendOpenVINO is the OpenVINO backend.
	NetBackendOpenVINO NetBackendType = 2

	// NetBackendOpenCV is the OpenCV backend.
	NetBackendOpenCV NetBackendType = 3

	// NetBackendVKCOM is the Vulkan backend.
	NetBackendVKCOM NetBackendType = 4

	// NetBackendCUDA is the Cuda backend.
	NetBackendCUDA NetBackendType = 5
)

// ParseNetBackend returns a valid NetBackendType given a string. Valid values are:
// - halide
// - openvino
// - opencv
// - vulkan
// - cuda
// - default
func ParseNetBackend(backend string) NetBackendType {
	switch backend {
	case "halide":
		return NetBackendHalide
	case "openvino":
		return NetBackendOpenVINO
	case "opencv":
		return NetBackendOpenCV
	case "vulkan":
		return NetBackendVKCOM
	case "cuda":
		return NetBackendCUDA
	default:
		return NetBackendDefault
	}
}

// NetTargetType is the type for the various different kinds of DNN device targets.
type NetTargetType int

const (
	// NetTargetCPU is the default CPU device target.
	NetTargetCPU NetTargetType = 0

	// NetTargetFP32 is the 32-bit OpenCL target.
	NetTargetFP32 NetTargetType = 1

	// NetTargetFP16 is the 16-bit OpenCL target.
	NetTargetFP16 NetTargetType = 2

	// NetTargetVPU is the Movidius VPU target.
	NetTargetVPU NetTargetType = 3

	// NetTargetVulkan is the NVIDIA Vulkan target.
	NetTargetVulkan NetTargetType = 4

	// NetTargetFPGA is the FPGA target.
	NetTargetFPGA NetTargetType = 5

	// NetTargetCUDA is the CUDA target.
	NetTargetCUDA NetTargetType = 6

	// NetTargetCUDAFP16 is the CUDA target.
	NetTargetCUDAFP16 NetTargetType = 7
)

// ParseNetTarget returns a valid NetTargetType given a string. Valid values are:
// - cpu
// - fp32
// - fp16
// - vpu
// - vulkan
// - fpga
// - cuda
// - cudafp16
func ParseNetTarget(target string) NetTargetType {
	switch target {
	case "cpu":
		return NetTargetCPU
	case "fp32":
		return NetTargetFP32
	case "fp16":
		return NetTargetFP16
	case "vpu":
		return NetTargetVPU
	case "vulkan":
		return NetTargetVulkan
	case "fpga":
		return NetTargetFPGA
	case "cuda":
		return NetTargetCUDA
	case "cudafp16":
		return NetTargetCUDAFP16
	default:
		return NetTargetCPU
	}
}

// Release Net
func (net *Net) Release() error {
	C.Net_Release((C.Net)(net.p))
	net.p = nil
	return nil
}

// Empty returns true if there are no layers in the network.
//
// For further details, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html#a6a5778787d5b8770deab5eda6968e66c
func (net *Net) Empty() bool {
	if net.p == nil {
		return true
	}
	return false
	// return bool(C.Net_Empty((C.Net)(net.p)))
}

// SetInput sets the new value for the layer output blob.
//
// For further details, please see:
// https://docs.opencv.org/trunk/db/d30/classcv_1_1dnn_1_1Net.html#a672a08ae76444d75d05d7bfea3e4a328
func (net *Net) SetInput(blob Mat, name string) {
	cName := C.CString(name)
	defer C.free(unsafe.Pointer(cName))

	C.Net_SetInput((C.Net)(net.p), blob.p, cName)
}

// Forward runs forward pass to compute output of layer with name outputName.
//
// For further details, please see:
// https://docs.opencv.org/trunk/db/d30/classcv_1_1dnn_1_1Net.html#a98ed94cb6ef7063d3697259566da310b
func (net *Net) Forward(outputName string) Mat {
	cName := C.CString(outputName)
	defer C.free(unsafe.Pointer(cName))

	return newMat(C.Net_Forward((C.Net)(net.p), cName))
}

// ForwardLayers forward pass to compute outputs of layers listed in outBlobNames.
//
// For further details, please see:
// https://docs.opencv.org/3.4.1/db/d30/classcv_1_1dnn_1_1Net.html#adb34d7650e555264c7da3b47d967311b
func (net *Net) ForwardLayers(outBlobNames []string) (blobs []Mat) {
	cMats := C.struct_Mats{}
	defer C.Mats_Release(cMats)
	C.Net_ForwardLayers((C.Net)(net.p), &(cMats), toCStrings(outBlobNames))
	blobs = make([]Mat, cMats.length)
	for i := C.int(0); i < cMats.length; i++ {
		blobs[i].p = C.Mats_get(cMats, i)
	}
	return
}

// SetPreferableBackend ask network to use specific computation backend.
//
// For further details, please see:
// https://docs.opencv.org/3.4/db/d30/classcv_1_1dnn_1_1Net.html#a7f767df11386d39374db49cd8df8f59e
func (net *Net) SetPreferableBackend(backend NetBackendType) error {
	C.Net_SetPreferableBackend((C.Net)(net.p), C.int(backend))
	return nil
}

// SetPreferableTarget ask network to make computations on specific target device.
//
// For further details, please see:
// https://docs.opencv.org/3.4/db/d30/classcv_1_1dnn_1_1Net.html#a9dddbefbc7f3defbe3eeb5dc3d3483f4
func (net *Net) SetPreferableTarget(target NetTargetType) error {
	C.Net_SetPreferableTarget((C.Net)(net.p), C.int(target))
	return nil
}

// ReadNet reads a deep learning network represented in one of the supported formats.
//
// For further details, please see:
// https://docs.opencv.org/3.4/d6/d0f/group__dnn.html#ga3b34fe7a29494a6a4295c169a7d32422
func ReadNet(model string, config string) Net {
	cModel := C.CString(model)
	defer C.free(unsafe.Pointer(cModel))

	cConfig := C.CString(config)
	defer C.free(unsafe.Pointer(cConfig))
	return Net{p: unsafe.Pointer(C.Net_ReadNet(cModel, cConfig))}
}

// ReadNetBytes reads a deep learning network represented in one of the supported formats.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#ga138439da76f26266fdefec9723f6c5cd
func ReadNetBytes(framework string, model []byte, config []byte) (Net, error) {
	cFramework := C.CString(framework)
	defer C.free(unsafe.Pointer(cFramework))
	bModel := toByteVector(model)
	bConfig := toByteVector(config)

	return Net{p: unsafe.Pointer(C.Net_ReadNetBytes(cFramework, *bModel, *bConfig))}, nil
}

// ReadNetFromCaffe reads a network model stored in Caffe framework's format.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#ga29d0ea5e52b1d1a6c2681e3f7d68473a
func ReadNetFromCaffe(prototxt string, caffeModel string) Net {
	cprototxt := C.CString(prototxt)
	defer C.free(unsafe.Pointer(cprototxt))

	cmodel := C.CString(caffeModel)
	defer C.free(unsafe.Pointer(cmodel))
	return Net{p: unsafe.Pointer(C.Net_ReadNetFromCaffe(cprototxt, cmodel))}
}

// ReadNetFromCaffeBytes reads a network model stored in Caffe model in memory.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#ga946b342af1355185a7107640f868b64a
func ReadNetFromCaffeBytes(prototxt []byte, caffeModel []byte) (Net, error) {
	bPrototxt := toByteVector(prototxt)
	bCaffeModel := toByteVector(caffeModel)
	return Net{p: unsafe.Pointer(C.Net_ReadNetFromCaffeBytes(*bPrototxt, *bCaffeModel))}, nil
}

// ReadNetFromTensorflow reads a network model stored in Tensorflow framework's format.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#gad820b280978d06773234ba6841e77e8d
func ReadNetFromTensorflow(model string) Net {
	cmodel := C.CString(model)
	defer C.free(unsafe.Pointer(cmodel))
	return Net{p: unsafe.Pointer(C.Net_ReadNetFromTensorflow(cmodel))}
}

// ReadNetFromTensorflowBytes reads a network model stored in Tensorflow framework's format.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#gacdba30a7c20db2788efbf5bb16a7884d
func ReadNetFromTensorflowBytes(model []byte) (Net, error) {
	bModel := toByteVector(model)
	return Net{p: unsafe.Pointer(C.Net_ReadNetFromTensorflowBytes(*bModel))}, nil
}

// BlobFromImage creates 4-dimensional blob from image. Optionally resizes and crops
// image from center, subtract mean values, scales values by scalefactor,
// swap Blue and Red channels.
//
// For further details, please see:
// https://docs.opencv.org/trunk/d6/d0f/group__dnn.html#ga152367f253c81b53fe6862b299f5c5cd
func BlobFromImage(img Mat, std float64, size image.Point, mean Scalar,
	swapRB bool, crop bool) Mat {

	sz := C.struct_Size{
		width:  C.int(size.X),
		height: C.int(size.Y),
	}

	sMean := C.struct_Scalar{
		val1: C.double(mean.Val1),
		val2: C.double(mean.Val2),
		val3: C.double(mean.Val3),
		val4: C.double(mean.Val4),
	}

	return newMat(C.Net_BlobFromImage(img.p, C.double(std), sz, sMean, C.bool(swapRB), C.bool(crop)))
}

// BlobFromImages Creates 4-dimensional blob from series of images.
// Optionally resizes and crops images from center, subtract mean values,
// scales values by scalefactor, swap Blue and Red channels.
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#ga2b89ed84432e4395f5a1412c2926293c
func BlobFromImages(imgs []Mat, blob *Mat, std float64, size image.Point, mean Scalar,
	swapRB bool, crop bool, ddepth int) {

	cMatArray := make([]C.Mat, len(imgs))
	for i, r := range imgs {
		cMatArray[i] = r.p
	}

	cMats := C.struct_Mats{
		mats:   (*C.Mat)(&cMatArray[0]),
		length: C.int(len(imgs)),
	}

	sz := C.struct_Size{
		width:  C.int(size.X),
		height: C.int(size.Y),
	}

	sMean := C.struct_Scalar{
		val1: C.double(mean.Val1),
		val2: C.double(mean.Val2),
		val3: C.double(mean.Val3),
		val4: C.double(mean.Val4),
	}

	C.Net_BlobFromImages(cMats, blob.p, C.double(std), sz, sMean, C.bool(swapRB), C.bool(crop), C.int(ddepth))
}

// ImagesFromBlob Parse a 4D blob and output the images it contains as
// 2D arrays through a simpler data structure (std::vector<cv::Mat>).
//
// For further details, please see:
// https://docs.opencv.org/master/d6/d0f/group__dnn.html#ga4051b5fa2ed5f54b76c059a8625df9f5
func ImagesFromBlob(blob Mat, imgs []Mat) {
	cMats := C.struct_Mats{}
	defer C.Mats_Release(cMats)

	C.Net_ImagesFromBlob(blob.p, &(cMats))
	// mv = make([]Mat, cMats.length)
	for i := C.int(0); i < cMats.length; i++ {
		imgs[i].p = C.Mats_get(cMats, i)
	}
}

// GetBlobChannel extracts a single (2d)channel from a 4 dimensional blob structure
// (this might e.g. contain the results of a SSD or YOLO detection,
//
//	a bones structure from pose detection, or a color plane from Colorization)
func GetBlobChannel(blob Mat, imgidx int, chnidx int) Mat {
	return newMat(C.Net_GetBlobChannel(blob.p, C.int(imgidx), C.int(chnidx)))
}

// GetBlobSize retrieves the 4 dimensional size information in (N,C,H,W) order
func GetBlobSize(blob Mat) Scalar {
	s := C.Net_GetBlobSize(blob.p)
	return NewScalar(float64(s.val1), float64(s.val2), float64(s.val3), float64(s.val4))
}

// Layer is a wrapper around the cv::dnn::Layer algorithm.
type Layer struct {
	// C.Layer
	p unsafe.Pointer
}

// GetLayer returns pointer to layer with specified id from the network.
//
// For further details, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html#a70aec7f768f38c32b1ee25f3a56526df
func (net *Net) GetLayer(layer int) Layer {
	return Layer{p: unsafe.Pointer(C.Net_GetLayer((C.Net)(net.p), C.int(layer)))}
}

// GetPerfProfile returns overall time for inference and timings (in ticks) for layers
//
// For further details, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html#a06ce946f675f75d1c020c5ddbc78aedc
func (net *Net) GetPerfProfile() float64 {
	return float64(C.Net_GetPerfProfile((C.Net)(net.p)))
}

// GetUnconnectedOutLayers returns indexes of layers with unconnected outputs.
//
// For further details, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html#ae62a73984f62c49fd3e8e689405b056a
func (net *Net) GetUnconnectedOutLayers() (ids []int) {
	cids := C.IntVector{}
	C.Net_GetUnconnectedOutLayers((C.Net)(net.p), &cids)
	defer C.IntVector_Release(cids)

	ids = toGoInts(cids)

	return
}

// GetLayerNames returns all layer names.
//
// For furtherdetails, please see:
// https://docs.opencv.org/master/db/d30/classcv_1_1dnn_1_1Net.html#ae8be9806024a0d1d41aba687cce99e6b
func (net *Net) GetLayerNames() (names []string) {
	cstrs := C.CStrings{}
	C.Net_GetLayerNames((C.Net)(net.p), &cstrs)
	defer C.CStrings_Release(cstrs)

	names = toGoStrings(cstrs)

	return
}

func (net *Net) GetOutputLayerNames() (names []string) {
	cstrs := C.CStrings{}
	C.Net_GetOutputLayerNames((C.Net)(net.p), &cstrs)
	defer C.CStrings_Release(cstrs)

	names = toGoStrings(cstrs)

	return
}

func (net *Net) SetParams(inputSize image.Point, inputChannel int, std float64, mean Scalar) {
	net.inputChannel = inputChannel
	net.inputSize = C.Size{
		width:  C.int(inputSize.X),
		height: C.int(inputSize.Y),
	}
	net.std = C.double(std)
	net.imageMean = C.Scalar{
		val1: C.double(mean.Val1),
		val2: C.double(mean.Val2),
		val3: C.double(mean.Val3),
		val4: C.double(mean.Val4),
	}
}

func (net *Net) PrepareImage(srcImg Mat, dstImg *Mat, padding, enhance bool) (scaleWidth, scaleHeight float64) {
	var tmp Mat
	if net.inputChannel == 1 && srcImg.Channels() == 3 {
		tmp = NewMat()
		CvtColor(srcImg, &tmp, ColorRGBToGray)
		srcImg = tmp
	} else if net.inputChannel == 3 && srcImg.Channels() == 1 {
		tmp = NewMat()
		CvtColor(srcImg, &tmp, ColorGrayToBGR)
		srcImg = tmp
	}

	if net.maxInputSize.X > 0 {
		// Dynamic input shape: resize to fixed height with proportional width, limited by maxInputSize
		targetHeight := net.maxInputSize.Y
		scale := float64(targetHeight) / float64(srcImg.Rows())
		targetWidth := int(float64(srcImg.Cols()) * scale)
		if targetWidth > net.maxInputSize.X {
			targetWidth = net.maxInputSize.X
		}
		if targetWidth < 32 {
			targetWidth = 32
		}
		targetSize := C.Size{width: C.int(targetWidth), height: C.int(targetHeight)}
		C.Resize(srcImg.p, dstImg.p, targetSize, C.double(0), C.double(0), C.int(InterpolationLinear))
		scaleWidth = float64(srcImg.Cols()) / float64(targetWidth)
		scaleHeight = float64(srcImg.Rows()) / float64(targetHeight)
	} else if padding {
		scale := C.ResizePadding(srcImg.p, dstImg.p, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
		scaleWidth = float64(scale)
		scaleHeight = scaleWidth
	} else {
		C.Resize(srcImg.p, dstImg.p, net.inputSize, C.double(0), C.double(0), C.int(InterpolationLinear))
		scaleWidth = float64(srcImg.Cols()) / float64(net.inputSize.width)
		scaleHeight = float64(srcImg.Rows()) / float64(net.inputSize.height)
	}

	if enhance {
		EnhanceGrayImage(*dstImg)
	}

	if tmp.p != nil {
		tmp.Release()
	}
	return
}

// Release Layer
func (l *Layer) Release() error {
	C.Layer_Release((C.Layer)(l.p))
	l.p = nil
	return nil
}

// GetName returns name for this layer.
func (l *Layer) GetName() string {
	return C.GoString(C.Layer_GetName((C.Layer)(l.p)))
}

// GetType returns type for this layer.
func (l *Layer) GetType() string {
	return C.GoString(C.Layer_GetType((C.Layer)(l.p)))
}

// InputNameToIndex returns index of input blob in input array.
//
// For further details, please see:
// https://docs.opencv.org/master/d3/d6c/classcv_1_1dnn_1_1Layer.html#a60ffc8238f3fa26cd3f49daa7ac0884b
func (l *Layer) InputNameToIndex(name string) int {
	cName := C.CString(name)
	defer C.free(unsafe.Pointer(cName))
	return int(C.Layer_InputNameToIndex((C.Layer)(l.p), cName))
}

// OutputNameToIndex returns index of output blob in output array.
//
// For further details, please see:
// https://docs.opencv.org/master/d3/d6c/classcv_1_1dnn_1_1Layer.html#a60ffc8238f3fa26cd3f49daa7ac0884b
func (l *Layer) OutputNameToIndex(name string) int {
	cName := C.CString(name)
	defer C.free(unsafe.Pointer(cName))
	return int(C.Layer_OutputNameToIndex((C.Layer)(l.p), cName))
}

func ParseDarknet(conf string) (width, height, channels int) {
	lines := strings.Split(conf, "\n")
	for _, line := range lines {
		i := strings.Index(line, "#")
		if i == 0 {
			continue
		}
		if i > 0 {
			line = line[0:i]
		}
		line = strings.Replace(line, " ", "", -1)
		if len(line) == 0 {
			continue
		}

		i = strings.Index(line, "width=")
		if i == 0 {
			width, _ = strconv.Atoi(line[6:len(line)])
		}
		i = strings.Index(line, "height=")
		if i == 0 {
			height, _ = strconv.Atoi(line[7:len(line)])
		}
		i = strings.Index(line, "channels=")
		if i == 0 {
			channels, _ = strconv.Atoi(line[9:len(line)])
		}
	}

	if width == 0 || height == 0 || channels == 0 {
		panic("net config error")
	}
	return
}

func ParseTensorflow(conf string) (width, height, channels int) {
	vv := make([]int, 4)
	idx := 0
	lines := strings.Split(conf, "\n")
	for _, line := range lines {
		i := strings.Index(line, "#")
		if i == 0 {
			continue
		}
		if i > 0 {
			line = line[0:i]
		}
		line = strings.Replace(line, " ", "", -1)
		if len(line) == 0 {
			continue
		}

		i = strings.Index(line, "size:")
		if i >= 0 {
			vv[idx], _ = strconv.Atoi(line[5:len(line)])
			idx += 1
			if idx > 3 {
				break
			}
		}
	}

	height = vv[1]
	width = vv[2]
	channels = vv[3]

	if width == 0 || height == 0 || channels == 0 {
		panic("net config error")
	}
	return
}

func CreateYolo(gpuId int, classes []string, cfgs, weights []byte) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		backend := NetBackendDefault
		target := NetTargetCPU
		if gpuId >= 0 && int(C.Cuda_GetDeviceCount()) > 0 {
			C.Cuda_SetDevice(C.int(gpuId))

			backend = NetBackendCUDA
			majorVersion := int(C.Cuda_GetDeviceMajorVersion(C.int(gpuId)))
			if majorVersion >= 7 {
				target = NetTargetCUDAFP16
			} else {
				target = NetTargetCUDA
			}
		}

		net, _ := ReadNetBytes("darknet", weights, cfgs)
		if net.Empty() {
			c <- &net
			return
		}

		net.SetPreferableBackend(NetBackendType(backend))
		net.SetPreferableTarget(NetTargetType(target))

		inputWidth, inputHeight, inputChannel := ParseDarknet(string(cfgs[:]))
		net.SetParams(image.Point{X: inputWidth, Y: inputHeight}, inputChannel, 1.0/255.0, NewScalar(0, 0, 0, 0))
		net.inChan = make(chan interface{})
		net.classes = classes

		outputLayerNames := C.CStrings{}
		C.Net_GetOutputLayerNames(C.Net(net.p), &outputLayerNames)
		defer C.CStrings_Release(outputLayerNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(YoloIn)

			cBoxes := C.Rects{}
			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.Net_RunYolo((C.Net)(net.p), data.InputBlob, data.InputSize, data.InputScale, outputLayerNames,
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

func RunYolo(net *Net, img Mat, confThreshold, nmsThreshold, rudeScale float32) ([]image.Rectangle, []int, []float32) {
	imgR := C.Mat_New()
	defer C.Mat_Release(imgR)

	// inputScale := C.ResizePadding(img.p, imgR, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
	var inputScale C.double
	if rudeScale > 0 {
		C.Resize(img.p, imgR, net.inputSize, C.double(0), C.double(0), C.int(InterpolationLinear))
		inputScale = C.double(float32(net.inputSize.width) / float32(img.Cols()))
		// scaleX = C.double(float32(net.inputSize.width) / float32(img.Cols()))
		// scaleY = C.double(float32(net.inputSize.height) / float32(img.Rows()))
	} else {
		inputScale = C.ResizePadding(img.p, imgR, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
		// scaleX = inputScale
		// scaleY = inputScale
	}

	inputBlob := imgR
	if float64(net.std) > 0 {
		inputBlob = C.Net_BlobFromImage(imgR, net.std, net.inputSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := YoloIn{
		InputBlob:     inputBlob,
		InputSize:     net.inputSize,
		InputScale:    inputScale,
		ConfThreshold: confThreshold,
		NMSThreshold:  nmsThreshold,
		OutChan:       make(chan YoloOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Boxes, out.Ids, out.Scores
}

func RunCenterNet(net *Net, img Mat, confThreshold, nmsThreshold, rudeScale float32) ([]image.Rectangle, []float32, []float32) {
	imgR := C.Mat_New()
	defer C.Mat_Release(imgR)

	var scaleX, scaleY C.double
	if rudeScale > 0 {
		C.Resize(img.p, imgR, net.inputSize, C.double(0), C.double(0), C.int(InterpolationLinear))
		scaleX = C.double(float32(net.inputSize.width) / float32(img.Cols()))
		scaleY = C.double(float32(net.inputSize.height) / float32(img.Rows()))
	} else {
		inputScale := C.ResizePadding(img.p, imgR, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
		scaleX = inputScale
		scaleY = inputScale
	}

	inputBlob := imgR
	if float64(net.std) > 0 {
		inputBlob = C.Net_BlobFromImage(imgR, net.std, net.inputSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := CenterNetIn{
		InputBlob:     inputBlob,
		InputSize:     net.inputSize,
		ScaleX:        scaleX,
		ScaleY:        scaleY,
		ConfThreshold: confThreshold,
		NMSThreshold:  nmsThreshold,
		OutChan:       make(chan CenterNetOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Boxes, out.Landmarks, out.Scores
}

func WarpFace(srcImg, dstImg *Mat, landmarks []float32) int {
	cLandmarks := toCFloatVector(landmarks)
	cRet := C.WarpFace(srcImg.p, dstImg.p, cLandmarks)
	return int(cRet)
}

func WarpRect(srcImg, dstImg *Mat, landmarks []float32) {
	cLandmarks := toCFloatVector(landmarks)
	C.WarpRect(srcImg.p, dstImg.p, cLandmarks)
}

func GetClasses(net *Net, ids []int) []string {
	classes := make([]string, len(ids))
	for i := 0; i < len(ids); i++ {
		if ids[i] >= 0 && ids[i] < len(net.classes) {
			classes[i] = net.classes[ids[i]]
		} else {
			classes[i] = ""
		}
	}
	return classes
}

func GetNetInputSize(net *Net) image.Point {
	return image.Point{X: int(net.inputSize.width), Y: int(net.inputSize.height)}
}

func RunNMS(inBoxes []image.Rectangle, inScores []float32, confThreshold, nmsThreshold float32) (indices []int) {
	cInBoxes := toCRects(inBoxes)
	cInScores := toCFloatVector(inScores)
	cIndices := C.IntVector{}
	defer C.IntVector_Release(cIndices)

	C.RunNMSRect(cInBoxes, cInScores, C.float(confThreshold), C.float(nmsThreshold), &cIndices)

	indices = toGoInts(cIndices)
	return
}

func RunRotatedNMS(inBoxes [][]image.Point, inScores []float32, confThreshold, nmsThreshold float32) (indices []int) {
	pts := []image.Point{}
	for _, pp := range inBoxes {
		pts = append(pts, pp...)
	}

	cInBoxes := toCPoints(pts)
	cInScores := toCFloatVector(inScores)
	cIndices := C.IntVector{}
	defer C.IntVector_Release(cIndices)

	C.RunNMSRotatedRect(cInBoxes, cInScores, C.float(confThreshold), C.float(nmsThreshold), &cIndices)

	indices = toGoInts(cIndices)
	return
}

func CreateClassify(gpuId int, framework string, cfgs, weights []byte, classes []string, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		backend := NetBackendDefault
		target := NetTargetCPU
		if gpuId >= 0 && int(C.Cuda_GetDeviceCount()) > 0 {
			C.Cuda_SetDevice(C.int(gpuId))

			backend = NetBackendCUDA
			majorVersion := int(C.Cuda_GetDeviceMajorVersion(C.int(gpuId)))
			if majorVersion >= 7 {
				target = NetTargetCUDAFP16
			} else {
				target = NetTargetCUDA
			}
		}

		net, _ := ReadNetBytes(framework, weights, cfgs)
		if net.Empty() {
			c <- &net
			return
		}

		net.SetPreferableBackend(NetBackendType(backend))
		net.SetPreferableTarget(NetTargetType(target))

		var inputWidth, inputHeight, inputChannel int
		if framework == "darknet" {
			inputWidth, inputHeight, inputChannel = ParseDarknet(string(cfgs[:]))
		} else if framework == "tensorflow" {
			inputWidth, inputHeight, inputChannel = ParseTensorflow(string(cfgs[:]))
		} else {
			panic("invalid classfy net cfg of input width,height,channel")
		}

		net.SetParams(image.Point{X: inputWidth, Y: inputHeight}, inputChannel, std, mean)
		net.inChan = make(chan interface{})
		net.classes = classes

		outputLayerNames := C.CStrings{}
		C.Net_GetOutputLayerNames(C.Net(net.p), &outputLayerNames)
		defer C.CStrings_Release(outputLayerNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ClassifyIn)

			cIds := C.IntVector{}
			cScores := C.FloatVector{}

			C.Net_RunClassify((C.Net)(net.p), data.InputBlob, outputLayerNames, &cIds, &cScores)
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

func RunClassify(net *Net, img Mat) (classes []string, scores []float32) {
	inputBlob := img.p
	if float64(net.std) > 0 {
		imgSize := C.Size{width: C.int(img.Cols()), height: C.int(img.Rows())}
		inputBlob = C.Net_BlobFromImage((C.Mat)(img.p), net.std, imgSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := ClassifyIn{
		InputBlob: inputBlob,
		OutChan:   make(chan ClassifyOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan

	classes = make([]string, len(out.Ids))
	for i := 0; i < len(out.Ids); i++ {
		classes[i] = net.classes[out.Ids[i]]
	}
	scores = out.Scores

	return
}

func CreateMetric(gpuId int, weights []byte, clusters int, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		backend := NetBackendDefault
		target := NetTargetCPU
		if gpuId >= 0 && int(C.Cuda_GetDeviceCount()) > 0 {
			C.Cuda_SetDevice(C.int(gpuId))

			backend = NetBackendCUDA
			majorVersion := int(C.Cuda_GetDeviceMajorVersion(C.int(gpuId)))
			if majorVersion >= 7 {
				target = NetTargetCUDAFP16
			} else {
				target = NetTargetCUDA
			}
		}

		net, _ := ReadNetBytes("onnx", weights, []byte{})
		if net.Empty() {
			c <- &net
			return
		}

		net.SetPreferableBackend(NetBackendType(backend))
		net.SetPreferableTarget(NetTargetType(target))

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		outputLayerNames := C.CStrings{}
		C.Net_GetOutputLayerNames(C.Net(net.p), &outputLayerNames)
		defer C.CStrings_Release(outputLayerNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(MetricIn)

			res := C.FloatVector{}

			C.Net_RunMetric((C.Net)(net.p), data.InputBlob, outputLayerNames, C.int(clusters), &res)

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

func RunMetric(net *Net, img Mat) []float32 {
	inputBlob := img.p
	if float64(net.std) > 0 {
		imgSize := C.Size{width: C.int(img.Cols()), height: C.int(img.Rows())}
		inputBlob = C.Net_BlobFromImage((C.Mat)(img.p), net.std, imgSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := MetricIn{
		InputBlob: inputBlob,
		OutChan:   make(chan MetricOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Embeddings
}

func CreateNetCom(framework string, gpuId int, cfgs, weights []byte, iSize image.Point, iChan int, std float64, mean Scalar) *Net {
	c := make(chan *Net)

	go func() {
		runtime.LockOSThread()

		backend := NetBackendDefault
		target := NetTargetCPU
		if gpuId >= 0 && int(C.Cuda_GetDeviceCount()) > 0 {
			C.Cuda_SetDevice(C.int(gpuId))

			backend = NetBackendCUDA
			majorVersion := int(C.Cuda_GetDeviceMajorVersion(C.int(gpuId)))
			if majorVersion >= 7 {
				target = NetTargetCUDAFP16
			} else {
				target = NetTargetCUDA
			}
		}

		net, _ := ReadNetBytes(framework, weights, cfgs)
		if net.Empty() {
			c <- &net
			return
		}

		net.SetPreferableBackend(NetBackendType(backend))
		net.SetPreferableTarget(NetTargetType(target))

		net.SetParams(iSize, iChan, std, mean)
		net.inChan = make(chan interface{})

		outputLayerNames := C.CStrings{}
		C.Net_GetOutputLayerNames(C.Net(net.p), &outputLayerNames)
		defer C.CStrings_Release(outputLayerNames)

		c <- &net

		for {
			inData := <-net.inChan
			data := inData.(ComIn)

			res := C.FloatVector{}

			C.Net_RunInference((C.Net)(net.p), data.InputBlob, outputLayerNames, &res)

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

func RunNetCom(net *Net, img Mat) []float32 {
	inputBlob := img.p
	if float64(net.std) > 0 {
		imgSize := C.Size{width: C.int(img.Cols()), height: C.int(img.Rows())}
		inputBlob = C.Net_BlobFromImage((C.Mat)(img.p), net.std, imgSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := ComIn{
		InputBlob: inputBlob,
		OutChan:   make(chan ComOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Val
}

func RunMatting(net *Net, img Mat) Mat {
	inputBlob := img.p
	if float64(net.std) > 0 {
		imgSize := C.Size{width: C.int(img.Cols()), height: C.int(img.Rows())}
		inputBlob = C.Net_BlobFromImage((C.Mat)(img.p), net.std, imgSize, net.imageMean, C.bool(false), C.bool(false))
		defer C.Mat_Release(inputBlob)
	}

	data := MattingIn{
		InputBlob: inputBlob,
		OutChan:   make(chan MattingOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Matting
}

func RunMask(net *Net, img Mat, confThreshold, maskThreshold, nmsThreshold, rudeScale float32) ([][]image.Point, []RotatedRect, []int, []float32) {
	imgR := C.Mat_New()
	defer C.Mat_Release(imgR)

	var scaleX, scaleY C.double
	if rudeScale > 0 {
		C.Resize(img.p, imgR, net.inputSize, C.double(0), C.double(0), C.int(InterpolationLinear))
		scaleX = C.double(float32(net.inputSize.width) / float32(img.Cols()))
		scaleY = C.double(float32(net.inputSize.height) / float32(img.Rows()))
	} else {
		inputScale := C.ResizePadding(img.p, imgR, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
		scaleX = inputScale
		scaleY = inputScale
	}

	inputBlob := C.Net_BlobFromImage(imgR, net.std, net.inputSize, net.imageMean, C.bool(false), C.bool(false))
	defer C.Mat_Release(inputBlob)

	data := MaskIn{
		InputBlob:     inputBlob,
		InputSize:     net.inputSize,
		ScaleX:        scaleX,
		ScaleY:        scaleY,
		ConfThreshold: confThreshold,
		MaskThreshold: maskThreshold,
		NMSThreshold:  nmsThreshold,
		OutChan:       make(chan MaskOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.Contours, out.RRects, out.Ids, out.Scores
}

func RunDB(net *Net, img Mat, confThreshold float32) ([]RotatedRect, []float32) {
	imgR := C.Mat_New()
	defer C.Mat_Release(imgR)

	var scaleX, scaleY C.double
	inputScale := C.ResizePadding(img.p, imgR, net.inputSize, C.Scalar{}, C.int(InterpolationLinear))
	scaleX = inputScale
	scaleY = inputScale

	inputBlob := C.Net_BlobFromImage(imgR, net.std, net.inputSize, net.imageMean, C.bool(false), C.bool(false))
	defer C.Mat_Release(inputBlob)

	data := DBIn{
		InputBlob:     inputBlob,
		InputSize:     net.inputSize,
		ScaleX:        scaleX,
		ScaleY:        scaleY,
		ConfThreshold: confThreshold,
		OutChan:       make(chan DBOut),
	}
	defer close(data.OutChan)

	net.inChan <- data
	out := <-data.OutChan
	return out.RRects, out.Scores
}
