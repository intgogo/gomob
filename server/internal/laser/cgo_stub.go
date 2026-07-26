//go:build !laser_cgo

// cgo_stub.go = 缺 native 依赖（未带 laser_cgo 标签）时的同签名桩。
// 让默认 `go build ./...` / `go test ./...` 不依赖 liblidar_scan.a / PCL 即可编译整个 monorepo；
// laserworker 真正运行须以 -tags laser_cgo 构建（见 cgo.go）。桩**显式返回错误**而非假成功——
// 不构成「静默假 fallback」。
package laser

// NativeScanAvailable 表示当前二进制是否真实链接了激光 native 采集实现。
func NativeScanAvailable() bool { return false }

// LiveScan 桩：直接返回 ErrCgoDisabled。
func LiveScan(ipA, ipB, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error) {
	return ScanResult{Error: ErrCgoDisabled.Error()}, ErrCgoDisabled
}

// ReplayScan 桩：直接返回 ErrCgoDisabled。
func ReplayScan(binA, binB, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error) {
	return ScanResult{Error: ErrCgoDisabled.Error()}, ErrCgoDisabled
}

// CancelScan 桩：无操作。
func CancelScan() {}
