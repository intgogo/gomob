package laser

import (
	"os"
	"testing"
)

// 用真机录制的 device_status / device_info 样本校验纯解析（无网络）。
// 真理源：/root/lilw/lidar/re/live/192.168.9.102_device_status.json + SAMPLE_device_info.json。

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
