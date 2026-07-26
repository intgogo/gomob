package laser

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 用真机录制的 device_status / device_info / get_config 样本校验纯解析（无网络）。
// 真理源：/root/lilw/lidar/re/live/ 下的 .101/.102 响应。

func TestParseDeviceStatusReal(t *testing.T) {
	body, err := os.ReadFile("testdata/device_status.json")
	if err != nil {
		t.Fatalf("读样本失败: %v", err)
	}
	st, err := ParseDeviceStatus(body)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if st.State != StateReady {
		t.Errorf("state = %q，期望 READY", st.State)
	}
	if !st.EncoderOnline || !st.LidarOnline || !st.ControlOnline {
		t.Errorf("子系统在线位错: enc=%v lidar=%v ctrl=%v", st.EncoderOnline, st.LidarOnline, st.ControlOnline)
	}
	if !st.Online() {
		t.Error("Online() 应为 true（enc+lidar+ctrl 都在线）")
	}
	if st.ErrorCode != 32 {
		t.Errorf("error_code = %d，期望 32", st.ErrorCode)
	}
	if st.Tempre < 40 || st.Tempre > 50 {
		t.Errorf("tempre = %.2f，期望 ~46.9", st.Tempre)
	}
	if st.Uptime <= 0 {
		t.Error("uptime 应 > 0")
	}
}

func TestParseDeviceInfoReal(t *testing.T) {
	body, err := os.ReadFile("testdata/device_info.json")
	if err != nil {
		t.Fatalf("读样本失败: %v", err)
	}
	info, err := ParseDeviceInfo(body)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if info.Model != "LTS-T1" {
		t.Errorf("model = %q，期望 LTS-T1", info.Model)
	}
	if info.SN == "" {
		t.Error("sn 不应为空")
	}
	if info.LidarModel != "Pico100" {
		t.Errorf("lidar.model = %q，期望 Pico100", info.LidarModel)
	}
}

func TestParseDeviceConfigReal(t *testing.T) {
	body, err := os.ReadFile("testdata/get_config.json")
	if err != nil {
		t.Fatalf("读样本失败: %v", err)
	}
	cfg, err := ParseDeviceConfig(body)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if cfg.Control.ScanSpeed != 3 || cfg.Control.ZeroSpeed != 20 ||
		cfg.Control.ScanStartAngle != 0 || cfg.Control.ScanStopAngle != 90 || cfg.Control.WatchingAngle != 0 {
		t.Errorf("扫描运动配置错误: %+v", cfg.Control)
	}
	if cfg.Control.LidarFilterGhost != 0.02 || cfg.Control.LidarFilterZone != [2]float64{-180, 180} {
		t.Errorf("calculate 滤波配置错误: %+v", cfg.Control)
	}
	if cfg.CameraFPS != 0.33 || cfg.Control.CameraFPS != 0.33 {
		t.Errorf("camera fps 错误: config=%v control=%v", cfg.CameraFPS, cfg.Control.CameraFPS)
	}
	if got := cfg.Calib.Lidar.CorrOffset[0]; got != 0.0002859028264719313 {
		t.Errorf("lidar corr_offset 未取 get_config 真值: %.16f", got)
	}
	if got := cfg.Calib.Camera.Intrinsic[0]; got != 2084.414773200171 {
		t.Errorf("camera intrinsic 未取 get_config 真值: %.16f", got)
	}
	if cfg.Calib.Body2World.Quat != [4]float64{1, 0, 0, 0} || cfg.Calib.Body2World.Scale != 1 {
		t.Errorf("body2world 错误: %+v", cfg.Calib.Body2World)
	}
}

func TestParseDeviceConfigRejectsIncompleteRuntimeFingerprint(t *testing.T) {
	body, err := os.ReadFile("testdata/get_config.json")
	if err != nil {
		t.Fatal(err)
	}
	decode := func(t *testing.T) map[string]any {
		t.Helper()
		var value map[string]any
		if err := json.Unmarshal(body, &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	encode := func(t *testing.T, value map[string]any) []byte {
		t.Helper()
		out, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		return out
	}

	cases := []struct {
		name string
		edit func(map[string]any)
		want string
	}{
		{
			name: "缺扫描速度",
			edit: func(v map[string]any) { delete(v["control"].(map[string]any), "scan_speed") },
			want: "control.scan_speed",
		},
		{
			name: "滤波区长度错误",
			edit: func(v map[string]any) { v["calculate"].(map[string]any)["lidar_filter_zone"] = []any{-180} },
			want: "lidar_filter_zone 必须包含 2 个数",
		},
		{
			name: "缺相机标定",
			edit: func(v map[string]any) { delete(v["camera"].(map[string]any), "intrinsic") },
			want: "camera.intrinsic",
		},
		{
			name: "缺身体到世界",
			edit: func(v map[string]any) { delete(v["calculate"].(map[string]any), "b2w_quat") },
			want: "calculate.b2w_quat",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			value := decode(t)
			tc.edit(value)
			if _, err := ParseDeviceConfig(encode(t, value)); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("应拒绝并包含 %q，得 %v", tc.want, err)
			}
		})
	}
}

func TestDeviceClientGetInfoMergesDeviceInfoAndConfig(t *testing.T) {
	infoBody := mustReadDeviceTestdata(t, "testdata/device_info.json")
	configBody := mustReadDeviceTestdata(t, "testdata/get_config.json")
	calls := map[string]int{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls[r.URL.Path]++
		switch r.URL.Path {
		case "/api/device_info":
			_, _ = w.Write(infoBody)
		case "/api/get_config":
			_, _ = w.Write(configBody)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	client := deviceClientForServer(t, srv)
	info, err := client.GetInfo(t.Context())
	if err != nil {
		t.Fatalf("GetInfo: %v", err)
	}
	if calls["/api/device_info"] != 1 || calls["/api/get_config"] != 1 {
		t.Fatalf("必须各读取一次 device_info/get_config，调用=%v", calls)
	}
	if info.Model != "LTS-T1" || info.SN == "" {
		t.Fatalf("device_info 身份丢失: %+v", info)
	}
	if info.Control.LidarFilterGhost != 0.02 || info.CameraCaptureFPS != 0.33 {
		t.Fatalf("运行配置未覆盖 DeviceInfo: control=%+v camera_fps=%v", info.Control, info.CameraCaptureFPS)
	}
	if info.Control.ScanSpeed != 3 || info.Control.ScanStartAngle != 0 ||
		info.Control.ScanStopAngle != 90 || info.Control.WatchingAngle != 0 {
		t.Fatalf("扫描角/速度未由 get_config 覆盖: %+v", info.Control)
	}
	// device_info 样本来自 .101，get_config 样本来自 .102；该值证明最终标定来自 get_config。
	if info.Calib.Lidar.CorrOffset[0] != 0.0002859028264719313 {
		t.Fatalf("标定未由 get_config 覆盖: %+v", info.Calib.Lidar)
	}
	if info.Calib.Camera.Intrinsic[0] != 2084.414773200171 ||
		info.Calib.Body2World.Quat != [4]float64{1, 0, 0, 0} || info.Calib.Body2World.Scale != 1 {
		t.Fatalf("camera/body2world 未由 get_config 覆盖: camera=%+v body=%+v",
			info.Calib.Camera, info.Calib.Body2World)
	}
}

func TestDeviceClientGetInfoRejectsConfigFailure(t *testing.T) {
	infoBody := mustReadDeviceTestdata(t, "testdata/device_info.json")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/device_info":
			_, _ = w.Write(infoBody)
		case "/api/get_config":
			http.Error(w, "config unavailable", http.StatusServiceUnavailable)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	client := deviceClientForServer(t, srv)
	if info, err := client.GetInfo(t.Context()); err == nil || info != nil || !strings.Contains(err.Error(), "/api/get_config") {
		t.Fatalf("get_config 失败必须整体失败，info=%+v err=%v", info, err)
	}
}

func TestDeviceProbeRejectsInvalidConfig(t *testing.T) {
	statusBody := mustReadDeviceTestdata(t, "testdata/device_status.json")
	infoBody := mustReadDeviceTestdata(t, "testdata/device_info.json")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/device_status":
			_, _ = w.Write(statusBody)
		case "/api/device_info":
			_, _ = w.Write(infoBody)
		case "/api/get_config":
			_, _ = w.Write([]byte(`{"control":{}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	result := deviceClientForServer(t, srv).Probe(t.Context())
	if !result.Reachable || result.Online || !strings.Contains(result.Err, "get_config") {
		t.Fatalf("配置无效不得继续判在线: %+v", result)
	}
}

func mustReadDeviceTestdata(t *testing.T, path string) []byte {
	t.Helper()
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func deviceClientForServer(t *testing.T, server *httptest.Server) *DeviceClient {
	t.Helper()
	u, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	host, portText, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	return &DeviceClient{ip: host, port: port, hc: &http.Client{Timeout: time.Second}}
}

func TestProbeResultIsLTST1(t *testing.T) {
	if !(ProbeResult{Model: "LTS-T1"}).IsLTST1() {
		t.Error("LTS-T1 应判为真")
	}
	if !(ProbeResult{Model: "lts-t1"}).IsLTST1() {
		t.Error("大小写不敏感应判为真")
	}
	if (ProbeResult{Model: "Pico100"}).IsLTST1() {
		t.Error("非 LTS-T1 应判为假")
	}
}

// device_status 缺 scan 节点时 State 应为空、不 panic。
func TestParseDeviceStatusPartial(t *testing.T) {
	st, err := ParseDeviceStatus([]byte(`{"uptime":1.0,"encoder":{"online":true}}`))
	if err != nil {
		t.Fatalf("部分 JSON 不应报错: %v", err)
	}
	if st.State != "" {
		t.Errorf("缺 scan 时 state 应为空，得 %q", st.State)
	}
	if st.Online() {
		t.Error("仅 encoder 在线不应判 Online")
	}
}
