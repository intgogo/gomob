package gocv

/*
#include "ocv_basetypes.h"

extern void goLogCallback(char* msg);

static void ccv_log_bridge(const char* msg) {
    goLogCallback((char*)msg);
}

static void ccv_init_go_log() {
    ccv_set_log_callback(ccv_log_bridge);
}
*/
import "C"
import (
	"strings"

	"github.com/sirupsen/logrus"
)

//export goLogCallback
func goLogCallback(msg *C.char) {
	s := strings.TrimRight(C.GoString(msg), "\n")
	if s != "" {
		logrus.Info("[ccv] ", s)
	}
}

func init() {
	C.ccv_init_go_log()
}
