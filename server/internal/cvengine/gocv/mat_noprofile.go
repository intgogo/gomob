// +build !matprofile

package gocv

/*
#include <stdlib.h>
#include "ocv_core.h"
*/
import "C"

// newMat returns a new Mat from a C Mat
func newMat(p C.Mat) Mat {
	return Mat{p: p}
}

// Release the Mat object.
func (m *Mat) Release() error {
	C.Mat_Release(m.p)
	m.p = nil
	return nil
}
