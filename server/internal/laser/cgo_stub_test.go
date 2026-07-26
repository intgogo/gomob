//go:build !laser_cgo

package laser

import "testing"

func TestNativeScanUnavailableWithoutLaserCgoTag(t *testing.T) {
	if NativeScanAvailable() {
		t.Fatal("未带 laser_cgo 标签的二进制不得报告 native 采集可用")
	}
}
