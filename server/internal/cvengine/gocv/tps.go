package gocv

/*
#include <stdlib.h>
#include "ocv_tps.h"
#include "ocv_imgproc.h"
*/
import "C"
import (
	"image"
	"sync"
)

type TPS struct {
	p     C.TPS
	sz    C.Size
	mutex sync.Mutex
}

func CreateTPS(outSize image.Point) *TPS {
	tps := new(TPS)
	tps.p = C.TPS_Create(C.double(0.0))
	tps.sz = C.Size{
		width:  C.int(outSize.X),
		height: C.int(outSize.Y),
	}
	return tps
}

func RunTPS(tps *TPS, src Mat, dst *Mat, pts []float32) {
	pp := toCFloatVector(pts)

	tps.mutex.Lock()
	defer tps.mutex.Unlock()
	C.TPS_RunImage(tps.p, src.p, dst.p, tps.sz, pp)
}
