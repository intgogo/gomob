// Package laser 是双单元激光（LIDAR-PTZ / LTS-T1）车辆外廓扫描的服务端控制 + 采集 + 融合。
//
// devctl.go = 设备控制面：HTTP 打 .101/.102 的 :4000 REST（device_status / device_info /
// control_scan）。这是**控制面**（探活 + 起停扫描）；采集面（TCP CA-FE/CRC/zstd 抓点流）不在
// Go 手写，交已 byte 验证的 C++ 管线经 cgo（见 cgo.go）。契约逆向真理源 = /root/lilw/lidar/src/device/
// http_client.{h,cpp}（对照 LTS-T1 固件 v1.4）。
package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ScanCmd = control_scan 的命令枚举（对齐 lidar http_client.h ScanCmd）。
type ScanCmd string

const (
	ScanStart  ScanCmd = "SCAN_START"  // 起扫（转电机，须现场清空）
	ScanStop   ScanCmd = "SCAN_STOP"   // 停扫
	ScanWatch  ScanCmd = "SCAN_WATCH"  // 看守角
	AlignZero  ScanCmd = "ALIGN_ZERO"  // 回零
	ClearError ScanCmd = "CLEAR_ERROR" // 清错
	SoftReboot ScanCmd = "SOFT_REBOOT" // 软重启
)

// 设备扫描状态机 token（device_status.scan.state）。
const (
	StateReady = "READY"
	StateScan  = "SCAN"
	StateBusy  = "BUSY"
	StateWatch = "WATCH"
	StateAlign = "ALIGN"
	StateError = "ERROR"
)

// DeviceStatus = device_status 扁平视图。嵌套真理源见 lidar parseDeviceStatus。
type DeviceStatus struct {
	State         string  `json:"state"`          // scan.state: READY|SCAN|BUSY|WATCH|ALIGN|ERROR
	ScanMsg       string  `json:"scan_msg"`       // scan.msg
	Uptime        float64 `json:"uptime"`         // 顶层 uptime
	EncoderOnline bool    `json:"encoder_online"` // encoder.online
	LidarOnline   bool    `json:"lidar_online"`   // lidar.online
	CameraOnline  bool    `json:"camera_online"`  // camera.online
	ControlOnline bool    `json:"control_online"` // control.online
	LatestAngle   float64 `json:"latest_angle"`   // encoder.latest_angle
	ZeroDegs      float64 `json:"zero_degs"`      // encoder.zero_degs
	AngleDegs     float64 `json:"angle_degs"`     // control.angle_degs
	ErrorCode     int64   `json:"error_code"`     // control.error_code 位掩码
	Tempre        float64 `json:"tempre"`         // control.tempre
}

// Online = 采集所需的子系统都在线（编码器 + 激光 + 控制；相机故障不阻断点云采集）。
func (s DeviceStatus) Online() bool {
	return s.EncoderOnline && s.LidarOnline && s.ControlOnline
}

// DeviceInfo = device_info 关键标识（用于探活校验是不是 LTS-T1）。
type DeviceInfo struct {
	Model       string `json:"model"`        // device.model，应为 "LTS-T1"
	SN          string `json:"sn"`           // device.sn
	HWVer       string `json:"hwver"`        // device.hwver
	SWVer       string `json:"swver"`        // device.swver
	NetworkType string `json:"network_type"` // device.network_type
	Network     string `json:"network"`      // device.network (ip/cidr)
	LidarModel  string `json:"lidar_model"`  // lidar.model
	CameraModel string `json:"camera_model"` // camera.model
}

// --- 纯解析（无网络，可对真机样本单测）---

// rawStatus 镜像 device_status 的嵌套结构，再扁平化到 DeviceStatus。
type rawStatus struct {
	Uptime  float64 `json:"uptime"`
	Encoder struct {
		Online      bool    `json:"online"`
		LatestAngle float64 `json:"latest_angle"`
		ZeroDegs    float64 `json:"zero_degs"`
	} `json:"encoder"`
	Lidar struct {
		Online bool `json:"online"`
	} `json:"lidar"`
	Camera struct {
		Online bool `json:"online"`
	} `json:"camera"`
	Control struct {
		Online    bool    `json:"online"`
		AngleDegs float64 `json:"angle_degs"`
		ErrorCode int64   `json:"error_code"`
		Tempre    float64 `json:"tempre"`
	} `json:"control"`
	Scan struct {
		State string `json:"state"`
		Msg   string `json:"msg"`
	} `json:"scan"`
}

// ParseDeviceStatus 解析 device_status JSON 为扁平 DeviceStatus。
func ParseDeviceStatus(body []byte) (DeviceStatus, error) {
	var r rawStatus
	if err := json.Unmarshal(body, &r); err != nil {
		return DeviceStatus{}, fmt.Errorf("device_status 解析失败: %w", err)
	}
	return DeviceStatus{
		State:         r.Scan.State,
		ScanMsg:       r.Scan.Msg,
		Uptime:        r.Uptime,
		EncoderOnline: r.Encoder.Online,
		LidarOnline:   r.Lidar.Online,
		CameraOnline:  r.Camera.Online,
		ControlOnline: r.Control.Online,
		LatestAngle:   r.Encoder.LatestAngle,
		ZeroDegs:      r.Encoder.ZeroDegs,
		AngleDegs:     r.Control.AngleDegs,
		ErrorCode:     r.Control.ErrorCode,
		Tempre:        r.Control.Tempre,
	}, nil
}

type rawInfo struct {
	Device struct {
		Model       string `json:"model"`
		SN          string `json:"sn"`
		HWVer       string `json:"hwver"`
		SWVer       string `json:"swver"`
		NetworkType string `json:"network_type"`
		Network     string `json:"network"`
	} `json:"device"`
	Lidar struct {
		Model string `json:"model"`
	} `json:"lidar"`
	Camera struct {
		Model string `json:"model"`
	} `json:"camera"`
}

// ParseDeviceInfo 解析 device_info JSON 为关键标识。
func ParseDeviceInfo(body []byte) (DeviceInfo, error) {
	var r rawInfo
	if err := json.Unmarshal(body, &r); err != nil {
		return DeviceInfo{}, fmt.Errorf("device_info 解析失败: %w", err)
	}
	return DeviceInfo{
		Model:       r.Device.Model,
		SN:          r.Device.SN,
		HWVer:       r.Device.HWVer,
		SWVer:       r.Device.SWVer,
		NetworkType: r.Device.NetworkType,
		Network:     r.Device.Network,
		LidarModel:  r.Lidar.Model,
		CameraModel: r.Camera.Model,
	}, nil
}

// --- 传输 ---

// DeviceClient 对单台扫描单元（.101 或 .102）的 :4000 REST 客户端。
type DeviceClient struct {
	ip   string
	port int
	hc   *http.Client
}

// NewDeviceClient ip=单元 IP（如 192.168.9.101）；timeout 控制每次请求超时（0=默认 3s）。
func NewDeviceClient(ip string, timeout time.Duration) *DeviceClient {
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	return &DeviceClient{ip: ip, port: 4000, hc: &http.Client{Timeout: timeout}}
}

func (c *DeviceClient) IP() string { return c.ip }

func (c *DeviceClient) url(path string) string {
	return fmt.Sprintf("http://%s:%d%s", c.ip, c.port, path)
}

// GetStatus GET /api/device_status。
func (c *DeviceClient) GetStatus(ctx context.Context) (*DeviceStatus, error) {
	body, err := c.get(ctx, "/api/device_status")
	if err != nil {
		return nil, err
	}
	s, err := ParseDeviceStatus(body)
	if err != nil {
		return nil, err
	}
	return &s, nil
}

// GetInfo GET /api/device_info。
func (c *DeviceClient) GetInfo(ctx context.Context) (*DeviceInfo, error) {
	body, err := c.get(ctx, "/api/device_info")
	if err != nil {
		return nil, err
	}
	info, err := ParseDeviceInfo(body)
	if err != nil {
		return nil, err
	}
	return &info, nil
}

// ControlScan POST /api/control_scan {"cmd":"…"}。
func (c *DeviceClient) ControlScan(ctx context.Context, cmd ScanCmd) error {
	payload, _ := json.Marshal(map[string]string{"cmd": string(cmd)})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url("/api/control_scan"), bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return fmt.Errorf("control_scan(%s) 打 %s 失败: %w", cmd, c.ip, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("control_scan(%s) %s 返回 %d: %s", cmd, c.ip, resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

func (c *DeviceClient) get(ctx context.Context, path string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url(path), nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("GET %s%s 失败: %w", c.ip, path, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("GET %s%s 返回 %d: %s", c.ip, path, resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return body, nil
}

// ProbeResult = 一次探活结果。
type ProbeResult struct {
	IP        string
	Reachable bool   // :4000 可达且 device_status 解析成功
	Online    bool   // 采集子系统在线
	State     string // 当前扫描状态
	Model     string // device.model（LTS-T1 校验）
	SN        string
	Err       string // 失败原因（成功为空）
}

// IsLTST1 = 该单元确为 LTS-T1 激光扫描单元。
func (p ProbeResult) IsLTST1() bool { return strings.EqualFold(p.Model, "LTS-T1") }

// Probe 一次性探活：device_status（可达 + 在线 + 状态）+ device_info（型号/SN）。不抛错，
// 失败原因塞进 ProbeResult.Err，供上层「两台都就绪才放行扫描」的门控判定。
func (c *DeviceClient) Probe(ctx context.Context) ProbeResult {
	pr := ProbeResult{IP: c.ip}
	st, err := c.GetStatus(ctx)
	if err != nil {
		pr.Err = err.Error()
		return pr
	}
	pr.Reachable = true
	pr.Online = st.Online()
	pr.State = st.State
	if info, ierr := c.GetInfo(ctx); ierr == nil {
		pr.Model = info.Model
		pr.SN = info.SN
	}
	return pr
}
