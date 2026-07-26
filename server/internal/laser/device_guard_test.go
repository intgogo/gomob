package laser

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"io.gomob/server/pkg/audit"
)

const validDeviceScanSettingsBody = `{
	"scan_speed":6,
	"zero_speed":20,
	"scan_start_angle":0,
	"scan_stop_angle":90,
	"watching_angle":0,
	"lidar_filter_ghost":0.02,
	"lidar_filter_zone":[-180,180],
	"camera_fps":0.33
}`

func TestPhysicalDeviceMutationsRequireAdmin(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	tests := []struct {
		name   string
		target string
		body   string
	}{
		{"设备命令", "/v1/scans/laser/device-command?unit=a", `{"cmd":"ALIGN_ZERO"}`},
		{"扫描设置", "/v1/scans/laser/device-scan-settings?unit=a", validDeviceScanSettingsBody},
		{"设备标定", "/v1/scans/laser/device-calib?unit=a", `{}`},
		{"自动工位标定", "/v1/scans/laser/site-calib", ``},
		{"取景工位标定", "/v1/scans/laser/site-framing", ``},
		{"手动工位标定", "/v1/scans/laser/site-framing/manual", `{}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := doAs(h, http.MethodPost, tc.target, tc.body, "7", "inspector")
			if rec.Code != http.StatusForbidden {
				t.Fatalf("非 admin 应 403，得 %d: %s", rec.Code, rec.Body.String())
			}
		})
	}
}

func TestPhysicalDeviceMutationsRejectUnmanagedTargets(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	tests := []struct {
		name   string
		target string
		body   string
	}{
		{"设备命令", "/v1/scans/laser/device-command?ip=192.168.9.150", `{"cmd":"ALIGN_ZERO"}`},
		{"扫描设置", "/v1/scans/laser/device-scan-settings?ip=192.168.9.150", validDeviceScanSettingsBody},
		{"设备标定", "/v1/scans/laser/device-calib?ip=192.168.9.150", `{}`},
		{"自动工位标定", "/v1/scans/laser/site-calib?unit_a_ip=192.168.9.150&unit_b_ip=192.168.9.102", ``},
		{"取景工位标定", "/v1/scans/laser/site-framing?unit_a_ip=192.168.9.150&unit_b_ip=192.168.9.102", ``},
		{"手动工位标定", "/v1/scans/laser/site-framing/manual", `{"unit_a_ip":"192.168.9.150","unit_b_ip":"192.168.9.102"}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := doAs(h, http.MethodPost, tc.target, tc.body, "7", "admin")
			if rec.Code != http.StatusForbidden {
				t.Fatalf("非受管目标应 403，得 %d: %s", rec.Code, rec.Body.String())
			}
		})
	}
}

func TestSiteCalibrationRejectsBelowProductionSolverThreshold(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	for _, target := range []string{
		"/v1/scans/laser/site-calib?min_common=2",
		"/v1/scans/laser/site-framing?min_common=2",
	} {
		rec := doAs(h, http.MethodPost, target, "", "7", "admin")
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "生产下限") {
			t.Fatalf("不得用弱化解算阈值绕过生产门，target=%s code=%d body=%s", target, rec.Code, rec.Body.String())
		}
	}
}

func TestDeviceReadEndpointsKeepExplicitIPSupport(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	dev := &fakeDeviceAPI{}
	h.newDev = func(string) DeviceAPI { return dev }
	for _, target := range []string{
		"/v1/scans/laser/device-status?ip=192.168.9.150",
		"/v1/scans/laser/device-info?ip=192.168.9.150",
	} {
		if rec := doAs(h, http.MethodGet, target, "", "7", "inspector"); rec.Code != http.StatusOK {
			t.Fatalf("读端显式 IP 不应被写端硬门影响，target=%s code=%d body=%s", target, rec.Code, rec.Body.String())
		}
	}
}

func TestDeviceMutationsConflictWithBusySession(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	dev := &fakeDeviceAPI{}
	h.newDev = func(string) DeviceAPI { return dev }
	if !h.sessions.tryReserve(reservationFormalScan) {
		t.Fatal("测试预留正式扫描失败")
	}
	defer h.sessions.release()

	requests := []struct {
		target string
		body   string
	}{
		{"/v1/scans/laser/device-command?unit=a", `{"cmd":"ALIGN_ZERO"}`},
		{"/v1/scans/laser/device-scan-settings?unit=a", validDeviceScanSettingsBody},
		{"/v1/scans/laser/device-calib?unit=a", `{}`},
	}
	for _, request := range requests {
		rec := doAs(h, http.MethodPost, request.target, request.body, "7", "admin")
		if rec.Code != http.StatusConflict {
			t.Fatalf("忙时应 409，target=%s code=%d body=%s", request.target, rec.Code, rec.Body.String())
		}
	}
	if len(dev.commands) != 0 || dev.controlWrites != 0 || dev.calibWrites != 0 {
		t.Fatalf("忙时不得写物理设备: %+v", dev)
	}
}

func TestBareScanStopRejectsFormalScanButAllowsFraming(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	dev := &fakeDeviceAPI{}
	h.newDev = func(string) DeviceAPI { return dev }
	h.sessions.set(42, &activeSession{jobID: 42, unitAIP: h.cfg.DefaultUnitAIP, unitBIP: h.cfg.DefaultUnitBIP})

	rec := doAs(h, http.MethodPost, "/v1/scans/laser/device-command?unit=a", `{"cmd":"SCAN_STOP"}`, "7", "admin")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "/{id}/stop") {
		t.Fatalf("正式扫描裸 STOP 应 409 并指向任务端点，得 %d: %s", rec.Code, rec.Body.String())
	}
	if len(dev.commands) != 0 {
		t.Fatalf("正式扫描时不得直达设备 STOP: %v", dev.commands)
	}
	h.sessions.clear(42)
	if !h.sessions.tryReserve(reservationFraming) {
		t.Fatal("测试预留取景失败")
	}
	defer h.sessions.release()

	rec = doAs(h, http.MethodPost, "/v1/scans/laser/device-command?unit=a", `{"cmd":"SCAN_STOP"}`, "7", "admin")
	if rec.Code != http.StatusOK {
		t.Fatalf("取景无正式 job 时 admin STOP 应允许，得 %d: %s", rec.Code, rec.Body.String())
	}
	if len(dev.commands) != 1 || dev.commands[0] != ScanStop {
		t.Fatalf("取景 STOP 未下发: %v", dev.commands)
	}
}

func TestPhysicalDeviceMutationsAreAudited(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	recorder := &audit.InMemory{}
	h.SetAuditRecorder(recorder)
	dev := &fakeDeviceAPI{}
	h.newDev = func(string) DeviceAPI { return dev }

	requests := []struct {
		target string
		body   string
	}{
		{"/v1/scans/laser/device-command?unit=a", `{"cmd":"ALIGN_ZERO"}`},
		{"/v1/scans/laser/device-scan-settings?unit=a", validDeviceScanSettingsBody},
		{"/v1/scans/laser/device-calib?unit=a", `{}`},
	}
	for _, request := range requests {
		if rec := doAs(h, http.MethodPost, request.target, request.body, "7", "admin"); rec.Code != http.StatusOK {
			t.Fatalf("设备写操作失败 target=%s code=%d body=%s", request.target, rec.Code, rec.Body.String())
		}
	}

	manualBody, _ := json.Marshal(manualSiteFramingRequest{
		Pairs: []manualSiteFramingPair{
			manualSiteFramingTestPair("P1", 100, 110, 140, 130),
			manualSiteFramingTestPair("P2", 300, 120, 340, 140),
			manualSiteFramingTestPair("P3", 180, 260, 220, 280),
			manualSiteFramingTestPair("P4", 420, 300, 460, 320),
		},
	})
	if rec := doAs(h, http.MethodPost, "/v1/scans/laser/site-framing/manual", string(manualBody), "7", "admin"); rec.Code != http.StatusOK {
		t.Fatalf("手动标定请求失败: %d %s", rec.Code, rec.Body.String())
	}

	waitAuditCount(t, recorder, 4)
	actions := map[string]bool{}
	for _, entry := range recorder.Snapshot() {
		actions[entry.Action] = true
	}
	for _, action := range []string{
		"laser.device_command",
		"laser.device_scan_settings",
		"laser.device_calib",
		"laser.site_framing_manual",
	} {
		if !actions[action] {
			t.Fatalf("缺少审计动作 %s，实际=%v", action, actions)
		}
	}
}

func TestSolvedSiteCalibrationPersistsBeforeResponseContract(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	store := newFakeSiteCalibrationStore()
	h.SetSiteCalibrationStore(store)
	recorder := &audit.InMemory{}
	h.SetAuditRecorder(recorder)
	var site map[string]any
	if err := json.Unmarshal([]byte(testSiteJSON), &site); err != nil {
		t.Fatal(err)
	}
	result := map[string]any{
		"ok": true, "b_to_a": site["b_to_a"], "matrix": site["b_to_a"],
		"rms_m": 0.0032, "n_common": 6,
	}
	req := newAdminRequest(http.MethodPost, "/v1/scans/laser/site-calib", "7")
	if err := h.finalizeSolvedSiteCalibration(
		req, h.cfg.DefaultUnitAIP, h.cfg.DefaultUnitBIP, "laser.site_calib", result,
	); err != nil {
		t.Fatal(err)
	}
	stored, err := store.Get(context.Background(), h.cfg.DefaultUnitAIP, h.cfg.DefaultUnitBIP)
	if err != nil {
		t.Fatal(err)
	}
	if stored.Source != "aruco" || stored.RMSErrorMM == nil || *stored.RMSErrorMM != 3.2 ||
		stored.CommonMarkers == nil || *stored.CommonMarkers != 6 ||
		stored.UpdatedBy == nil || *stored.UpdatedBy != 7 {
		t.Fatalf("服务端解算结果未完整入库: %+v", stored)
	}
	if result["server_persisted"] != true || result["revision"] == "" {
		t.Fatalf("响应前应标记已持久化版本: %+v", result)
	}
	waitAuditCount(t, recorder, 1)
	entry, ok := recorder.EntryAt(0)
	if !ok || entry.Action != "laser.site_calib" {
		t.Fatalf("标定入库审计错误: %+v", recorder.Snapshot())
	}
}

func TestStartScanRequiresBothDevicesReady(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateScan, Model: "LTS-T1"}}
	rec := do(h, http.MethodPost, "/v1/scans/laser", siteStartBody(""), "7")
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "READY") {
		t.Fatalf("在扫设备必须拒绝叠扫，得 %d: %s", rec.Code, rec.Body.String())
	}
	h.probe = fakeProber{res: ProbeResult{Reachable: true, Online: true, State: StateReady, Model: "LTS-T1"}}
	if rec := do(h, http.MethodPost, "/v1/scans/laser", siteStartBody(""), "7"); rec.Code != http.StatusCreated {
		t.Fatalf("拒绝后应释放会话名额，READY 应可起扫，得 %d: %s", rec.Code, rec.Body.String())
	}
}

func newAdminRequest(method, target, userID string) *http.Request {
	req, _ := http.NewRequest(method, target, nil)
	req.Header.Set("X-Gomob-User-Id", userID)
	req.Header.Set("X-Gomob-Roles", "admin")
	return req
}

func waitAuditCount(t *testing.T, recorder *audit.InMemory, want int) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if recorder.Count() >= want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("审计记录超时：期望 %d，实际 %d", want, recorder.Count())
}
