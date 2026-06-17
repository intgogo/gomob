package laser

import "errors"

// scan_types.go = 激光采集/融合的**与构建标签无关**的共享类型，供 cgo.go(laser_cgo)、
// cgo_stub.go(!laser_cgo) 与 runner/handler 共同引用。真实 cgo 实现见 cgo.go；缺 native
// 依赖时由 cgo_stub.go 提供同签名桩。

// ErrCgoDisabled = 未带 laser_cgo 标签构建时调用扫描函数的返回错误。
var ErrCgoDisabled = errors.New("laser: 未启用 laser_cgo 构建标签（缺 liblidar_scan.a / PCL）；" +
	"laserworker 须以 -tags laser_cgo 构建，并先跑 scripts/laser-cgo-setup.sh 备好 native 库")

// ScanError = C-ABI 返回非 0（1=连接/读失败 或 2=取消）时的错误，携带原始 Error 文案。
type ScanError struct {
	Code int    // C-ABI 返回码：1=错误, 2=取消
	Msg  string // LidarScanResult.error
}

func (e *ScanError) Error() string {
	if e.Msg != "" {
		return e.Msg
	}
	if e.Code == 2 {
		return "扫描已取消"
	}
	return "扫描失败"
}

// Cancelled = 该错误是否为「协作取消」（返回码 2），上层据此区分用户 stop vs 真故障。
func (e *ScanError) Cancelled() bool { return e.Code == 2 }

// PointFrame = 一批流式点（采集中实时，或融合后整云）。XYZmm 已从 C 内存拷出，归 Go 所有。
type PointFrame struct {
	Unit      int       // 0=unitA 原始, 1=unitB 原始, 2=融合结果
	XYZmm     []float32 // [x,y,z, x,y,z, ...] 毫米；len == 3*点数
	HAngleDeg float32   // 该帧水平角（融合帧为 0）
}

// Points 返回该帧点数（三元组个数）。
func (f PointFrame) Points() int { return len(f.XYZmm) / 3 }

// ColorPointFrame = 纹理投影后的彩色点云。RGB 为 0xRRGGBB，每点一项。
type ColorPointFrame struct {
	Unit  int
	XYZmm []float32
	RGB   []uint32
}

func (f ColorPointFrame) Points() int { return len(f.XYZmm) / 3 }

// ImageFrame = 采集中相机 RGB 预览帧（已降采样 JPEG，从 C 拷出）。供端侧小窗实时预览。
type ImageFrame struct {
	Unit      int     // 0=unitA, 1=unitB
	JPEG      []byte  // 降采样后的 JPEG 字节
	HAngleDeg float32 // 该帧水平角
}

// ScanResult = 一次扫描完成后的汇总（对应 C LidarScanResult）。
type ScanResult struct {
	PtsA      int         // unitA 原始点数
	PtsB      int         // unitB 原始点数
	Fused     int         // 融合点数
	AfterCrop int         // 裁剪后点数（当前 == Fused）
	BToA      [16]float32 // B→A 4x4 行优先（mm 平移）
	Align     string      // 实际配准法 "icp"|"none"|"site"
	Error     string      // 失败原因（成功为空）
}

// ScanCallbacks = 扫描过程的流式回调。两者均可为 nil。
// 注意：OnPoints 在 live 模式下可能被两条采集线程**并发**调用（unitA/unitB 各一线程），
// 实现须自带同步（如往 channel 投递 / 加锁）。
type ScanCallbacks struct {
	OnPoints      func(PointFrame)
	OnColorPoints func(ColorPointFrame)
	OnImage       func(ImageFrame) // 采集中相机 RGB 预览帧（端侧小窗）；nil 则关闭预览
	OnStatus      func(state string, framesA, framesB int) // state: connecting|armed|scanning|fusing|done|error|cancelled

	// ExpectedSweep*Deg 是当前设备配置的线性目标扫掠角，仅用于诊断日志；不再作为 live 终止/失败条件。
	ExpectedSweepADeg float32
	ExpectedSweepBDeg float32
}
