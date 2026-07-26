package laser

// siteframing.go — 现场标定「实时取景」：一次可控扫掠，相机逐帧把 RGB 预览 + ArUco 检测推到端，
// 扫完解 A↔B 外参。点云看不清标记的痛点用「直接看相机图」解决。
//
// 拓扑：HTTP POST 触发 → 设两单元扫描角/速度（云台控制）→ exec lidar_cli framing-stream（被动连
// 4003 双流，stdout 二进制帧协议）→ 见 READY 即 devctl SCAN_START 起扫 → 读协议：'m' 帧转 NATS
// laser.frame（owner 路由到 ws，端侧渲染胶片+检测框），'r' 结果回 HTTP 响应。扫完 SCAN_STOP。
// 解算器（OpenCV aruco + Umeyama）在 lidar_cli，不进 cgo 精简库。详见 docs/architecture/17 §9.6。

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// TopicLaserFrame = 取景帧 NATS 主题（signaling LaserBridge 订阅，按 owner 路由 ws）。
const TopicLaserFrame = "laser.frame"

// LaserFrameMsg = laser.frame 载荷。jpeg 走 base64（NATS/JSON 文本通道，0.33fps 下体量可忽略）。
type LaserFrameMsg struct {
	OwnerUserID *int64        `json:"owner_user_id,omitempty"`
	SessionKey  string        `json:"session_key"`
	Unit        int           `json:"unit"` // 0=A, 1=B
	Seq         int           `json:"seq"`
	HeadingDeg  float64       `json:"heading_deg"`
	W           int           `json:"w"`
	H           int           `json:"h"`
	JPEGB64     string        `json:"jpeg_b64"`
	Markers     []FrameMarker `json:"markers"`
}

// FrameMarker = 一个检测到的标记（预览像素系角点，供端侧叠加）。
type FrameMarker struct {
	ID int     `json:"id"`
	PX [][]int `json:"px"`
}

// frameMeta 镜像 framing_stream.cpp 的 'm' 帧 meta JSON。
type frameMeta struct {
	Unit    int           `json:"unit"`
	Seq     int           `json:"seq"`
	Heading float64       `json:"heading"`
	W       int           `json:"w"`
	H       int           `json:"h"`
	Markers []FrameMarker `json:"markers"`
}

// SiteFraming POST /v1/scans/laser/site-framing
//
//	?unit_a_ip=&unit_b_ip=&marker_len=0.15&min_common=4&preview_width=1280
//	&a_start=&a_stop=&b_start=&b_stop=&speed=   （角度/速度可选；缺省沿用设备持久化值）
func (h *Handler) SiteFraming(w http.ResponseWriter, r *http.Request) {
	owner := callerUserID(r)
	if owner == 0 {
		writeErr(w, http.StatusUnauthorized, "需要登录")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "执行工位取景标定需 admin 角色")
		return
	}
	q := r.URL.Query()
	ipA, ipB, status, message := h.managedStationUnitIPs(q.Get("unit_a_ip"), q.Get("unit_b_ip"))
	if status != 0 {
		writeErr(w, status, message)
		return
	}
	markerLen := strings.TrimSpace(q.Get("marker_len"))
	if markerLen == "" {
		markerLen = "0.15"
	}
	markerLenValue, err := strconv.ParseFloat(markerLen, 64)
	if err != nil || math.IsNaN(markerLenValue) || math.IsInf(markerLenValue, 0) || markerLenValue <= 0 {
		writeErr(w, http.StatusBadRequest, "marker_len 必须是正数（米）")
		return
	}
	minCommon := strings.TrimSpace(q.Get("min_common"))
	if minCommon == "" {
		minCommon = "4"
	}
	minCommonValue, err := strconv.Atoi(minCommon)
	if err != nil || minCommonValue < minProductionSiteCommonMarkers {
		writeErr(w, http.StatusBadRequest, "min_common 不得低于生产下限 "+strconv.Itoa(minProductionSiteCommonMarkers))
		return
	}
	previewWidth := strings.TrimSpace(q.Get("preview_width"))
	if previewWidth == "" {
		previewWidth = "1280"
	}
	framingSessionKey := strings.TrimSpace(q.Get("session_key"))
	if framingSessionKey == "" {
		writeErr(w, http.StatusBadRequest, "session_key 必填，且每次取景必须使用新的唯一值")
		return
	}
	if !validFramingSessionKey(framingSessionKey) {
		writeErr(w, http.StatusBadRequest, "session_key 仅允许 1-96 位字母、数字、点、下划线或连字符")
		return
	}
	aStart, aStop, hasA, err := parseFramingSweep(q, "a_start", "a_stop", "A")
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	bStart, bStop, hasB, err := parseFramingSweep(q, "b_start", "b_stop", "B")
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if speed := strings.TrimSpace(q.Get("speed")); speed != "" {
		value, parseErr := strconv.ParseFloat(speed, 64)
		if parseErr != nil || math.IsNaN(value) || math.IsInf(value, 0) || value <= 0 {
			writeErr(w, http.StatusBadRequest, "speed 必须是正的有限数字")
			return
		}
	}

	if !h.sessions.tryReserve(reservationFraming) {
		writeErr(w, http.StatusConflict, "有扫描/标定进行中，请稍后再试")
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	_, ok := h.sessions.registerFraming(framingSessionKey, owner, ipA, ipB, cancel)
	if !ok {
		cancel()
		h.sessions.release()
		writeErr(w, http.StatusConflict, "取景标定会话注册失败，请稍后重试")
		return
	}
	motionPossible := false
	devicesReady := false
	defer func() {
		cancel()
		if !motionPossible || devicesReady {
			h.sessions.markFramingDevicesReady(framingSessionKey)
		}
		h.sessions.markFramingPipelineDone(framingSessionKey)
		if h.sessions.framingCancelRequested(framingSessionKey) {
			// DELETE 已启动独立清理协程；由它完成唯一一次 finish，避免旧清理越过 release 误停下一会话。
			return
		}
		if !motionPossible || devicesReady {
			h.sessions.finishFraming(framingSessionKey)
			return
		}
		if cleanup, started := h.sessions.startFramingCleanup(framingSessionKey); started {
			h.log.Warn("取景管线结束但 A/B READY 尚未确认，后台清理保持会话锁", "session_key", framingSessionKey)
			go h.recoverFramingSessionUntilReady(cleanup)
		}
	}()

	if pr := h.probe.Probe(ctx, ipA); !pr.Reachable || !pr.Online {
		writeErr(w, http.StatusBadGateway, "unitA("+ipA+") 不可达或子系统离线: "+pr.Err)
		return
	} else if pr.State != StateReady {
		writeErr(w, http.StatusConflict, "unitA("+ipA+") 当前状态为 "+pr.State+"，仅 READY 允许启动取景标定")
		return
	}
	if pr := h.probe.Probe(ctx, ipB); !pr.Reachable || !pr.Online {
		writeErr(w, http.StatusBadGateway, "unitB("+ipB+") 不可达或子系统离线: "+pr.Err)
		return
	} else if pr.State != StateReady {
		writeErr(w, http.StatusConflict, "unitB("+ipB+") 当前状态为 "+pr.State+"，仅 READY 允许启动取景标定")
		return
	}

	// 设扫描角/速度（云台控制）：仅当请求带角度时下发，read-modify-write 保留其它运动参数。
	// update_control 可能触发设备应用参数/回中，因此从第一次控制读取起就按“可能运动”处理，异常必须 STOP+READY。
	motionPossible = hasA || hasB
	if hasA {
		if err := h.applyFramingControl(ctx, ipA, aStart, aStop, q.Get("speed"), "A"); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"error": err.Error(), "cleanup_pending": true, "devices_ready": false,
				"session_key": framingSessionKey,
			})
			return
		}
	}
	if hasB {
		if err := h.applyFramingControl(ctx, ipB, bStart, bStop, q.Get("speed"), "B"); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]any{
				"error": err.Error(), "cleanup_pending": true, "devices_ready": false,
				"session_key": framingSessionKey,
			})
			return
		}
	}

	base, err := os.MkdirTemp("", "site-framing-")
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "建临时目录失败: "+err.Error())
		return
	}
	defer os.RemoveAll(base)
	outJSON := filepath.Join(base, "site_extrinsic.json")

	motionPossible = true
	res, err := h.runFramingStream(ctx, owner, ipA, ipB, outJSON, markerLen, minCommon, previewWidth, framingSessionKey)
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error": err.Error(), "cleanup_pending": true, "devices_ready": false,
			"session_key": framingSessionKey,
		})
		return
	}
	stopReadyErr := h.stopFramingUnitsAndWaitReady(context.WithoutCancel(ctx), ipA, ipB)
	if stopReadyErr != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error": stopReadyErr.Error(), "cleanup_pending": true, "devices_ready": false,
			"session_key": framingSessionKey,
		})
		return
	}
	devicesReady = true
	h.sessions.markFramingDevicesReady(framingSessionKey)
	if solved, _ := res["ok"].(bool); solved {
		if !h.sessions.beginFramingCommit(framingSessionKey) {
			writeErr(w, http.StatusConflict, "取景会话已停止，解算结果未保存")
			return
		}
	}
	if err := h.finalizeSolvedSiteCalibration(r, ipA, ipB, "laser.site_framing", res); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if solved, _ := res["ok"].(bool); solved {
		h.sessions.markFramingCommitted(framingSessionKey)
	}
	writeJSON(w, http.StatusOK, res)
}

// StopSiteFraming DELETE /v1/scans/laser/site-framing?session_key=...
// 先取消服务端 context，再等待原请求完成清理；因此即使仍处于参数 settle 阶段，也不会随后反向 SCAN_START。
func (h *Handler) StopSiteFraming(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要登录")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "停止工位取景标定需 admin 角色")
		return
	}
	sessionKey := strings.TrimSpace(r.URL.Query().Get("session_key"))
	if !validFramingSessionKey(sessionKey) {
		writeErr(w, http.StatusBadRequest, "session_key 仅允许 1-96 位字母、数字、点、下划线或连字符")
		return
	}
	decision := h.sessions.requestFramingCancel(sessionKey)
	if !decision.matched && decision.active {
		writeErr(w, http.StatusConflict, "当前活动取景会话与 session_key 不匹配")
		return
	}
	if !decision.matched {
		writeErr(w, http.StatusNotFound, "未找到该取景会话，无法确认是否已保存外参")
		return
	}
	framing := decision.session
	if decision.cancelWon {
		if cleanup, started := h.sessions.startFramingCleanup(sessionKey); started {
			go h.recoverFramingSessionUntilReady(cleanup)
		}
	}
	select {
	case <-framing.done:
		// 后台清理已确认 pipeline 退出、双机 STOP 且 A/B READY。
	case <-r.Context().Done():
		return
	case <-time.After(15 * time.Second):
		writeJSON(w, http.StatusAccepted, map[string]any{
			"ok": true, "active": true, "stopping": true, "cancel_won": decision.cancelWon,
			"commit_started": !decision.cancelWon, "session_key": sessionKey,
		})
		return
	}
	var readyErr error
	if !framing.devicesReady {
		readyErr = errors.New("取景管线已结束，但没有 A/B READY 终态证明")
	}
	if framing.commitStarted {
		body := map[string]any{
			"error":  "取景结果已进入服务端提交阶段，停止不能撤销该次提交；请回读权威外参状态",
			"active": false, "commit_started": true, "server_persisted": framing.committed,
			"devices_ready": readyErr == nil, "session_key": sessionKey,
		}
		if readyErr != nil {
			body["error"] = body["error"].(string) + "；" + readyErr.Error()
		}
		writeJSON(w, http.StatusConflict, body)
		return
	}
	if readyErr != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error": readyErr.Error(), "active": false, "cancel_won": true,
			"server_persisted": false, "devices_ready": false, "session_key": sessionKey,
		})
		return
	}
	h.recordAudit(r, "laser.site_framing_stop", "session:"+sessionKey, map[string]any{
		"session_key": sessionKey,
		"unit_a_ip":   framing.unitAIP,
		"unit_b_ip":   framing.unitBIP,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "active": false, "cancel_won": true, "server_persisted": false,
		"devices_ready": true, "session_key": sessionKey,
	})
}

func (h *Handler) recoverFramingSessionUntilReady(framing *activeFramingSession) {
	select {
	case <-framing.pipelineDone:
	case <-framing.done:
		return
	}
	if framing.devicesReady {
		h.sessions.finishFraming(framing.key)
		return
	}
	for {
		if err := h.stopFramingUnitsAndWaitReady(context.Background(), framing.unitAIP, framing.unitBIP); err == nil {
			h.sessions.markFramingDevicesReady(framing.key)
			h.sessions.finishFraming(framing.key)
			h.log.Info("取景异常收尾已恢复，A/B 均回到 READY", "session_key", framing.key)
			return
		}
		time.Sleep(2 * time.Second)
	}
}

func (h *Handler) stopFramingUnitsAndWaitReady(parent context.Context, ipA, ipB string) error {
	ctx, cancel := context.WithTimeout(parent, 10*time.Second)
	defer cancel()
	var stopA, stopB error
	var stopWG sync.WaitGroup
	stopWG.Add(2)
	go func() {
		defer stopWG.Done()
		stopA = h.newDev(ipA).ControlScan(ctx, ScanStop)
	}()
	go func() {
		defer stopWG.Done()
		stopB = h.newDev(ipB).ControlScan(ctx, ScanStop)
	}()
	stopWG.Wait()
	var lastA, lastB ProbeResult
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		lastA = h.probe.Probe(ctx, ipA)
		lastB = h.probe.Probe(ctx, ipB)
		if lastA.Reachable && lastA.Online && lastA.State == StateReady &&
			lastB.Reachable && lastB.Online && lastB.State == StateReady {
			return nil
		}
		select {
		case <-ctx.Done():
			parts := []string{
				fmt.Sprintf("A=%s reachable=%t online=%t", lastA.State, lastA.Reachable, lastA.Online),
				fmt.Sprintf("B=%s reachable=%t online=%t", lastB.State, lastB.Reachable, lastB.Online),
			}
			if stopA != nil {
				parts = append(parts, "A STOP失败="+stopA.Error())
			}
			if stopB != nil {
				parts = append(parts, "B STOP失败="+stopB.Error())
			}
			return fmt.Errorf("取景会话已结束，但设备未确认回到 READY：%s", strings.Join(parts, "；"))
		case <-ticker.C:
		}
	}
}

// applyFramingControl 读-改-写并回读确认单元扫描角/速度（保留其它运动参数）。
//
// ★ 设备实测雷点（2026-06-15）：扫前用 update_control 重设角/速度，会让随后的 SCAN sweep 忽略慢速、
// 退化成 ~12s 短快扫 → 0~2 帧（相机 0.33fps 抓不到）；而持久化配置不重设时 sweep 慢而长（[0,90]@0.5
// 跑满 180s → 6 帧）。故：① 已是目标值就**不重发**（幂等）；② 确需改时改完**留够 settle 时间**让设备稳定。
func (h *Handler) applyFramingControl(ctx context.Context, ip string, start, stop float64, speedStr, tag string) error {
	dev := h.newDev(ip)
	info, err := dev.GetInfo(ctx)
	if err != nil {
		return fmt.Errorf("取景：读取 unit%s 控制设置失败: %w", tag, err)
	}
	ctrl := info.Control
	wantSpeed := ctrl.ScanSpeed
	if speed := strings.TrimSpace(speedStr); speed != "" {
		sp, parseErr := strconv.ParseFloat(speed, 64)
		if parseErr != nil || math.IsNaN(sp) || math.IsInf(sp, 0) || sp <= 0 {
			return fmt.Errorf("取景：unit%s 扫描速度无效", tag)
		}
		wantSpeed = sp
	}
	// 幂等：已是目标角/速度就不重发，避免触发"扫前重设→短快扫"退化。
	if framingControlMatches(ctrl, start, stop, wantSpeed) {
		h.log.Info("取景：扫描角/速度已是目标值，免重设", "unit", tag, "start", start, "stop", stop, "speed", wantSpeed)
		return nil
	}
	ctrl.ScanStartAngle = start
	ctrl.ScanStopAngle = stop
	ctrl.ScanSpeed = wantSpeed
	if err := dev.UpdateControl(ctx, ctrl); err != nil {
		return fmt.Errorf("取景：下发 unit%s 扫描角/速度失败: %w", tag, err)
	}
	h.log.Info("取景：已设扫描角/速度（settle 中）", "unit", tag, "start", start, "stop", stop, "speed", wantSpeed)
	// 改配置后让设备稳定（应用参数/可能回中），否则随后 sweep 退化为短快扫。
	select {
	case <-ctx.Done():
		return fmt.Errorf("取景：等待 unit%s 控制参数生效时取消: %w", tag, ctx.Err())
	case <-time.After(framingSettleDelay):
	}
	confirmed, err := dev.GetInfo(ctx)
	if err != nil {
		return fmt.Errorf("取景：回读 unit%s 控制设置失败: %w", tag, err)
	}
	if !framingControlMatches(confirmed.Control, start, stop, wantSpeed) {
		return fmt.Errorf(
			"取景：unit%s 控制设置回读不一致，期望 start=%.3f stop=%.3f speed=%.3f，实际 start=%.3f stop=%.3f speed=%.3f",
			tag, start, stop, wantSpeed,
			confirmed.Control.ScanStartAngle, confirmed.Control.ScanStopAngle, confirmed.Control.ScanSpeed,
		)
	}
	return nil
}

func framingControlMatches(control ControlSettings, start, stop, speed float64) bool {
	const tolerance = 1e-6
	return math.Abs(control.ScanStartAngle-start) <= tolerance &&
		math.Abs(control.ScanStopAngle-stop) <= tolerance &&
		math.Abs(control.ScanSpeed-speed) <= tolerance
}

// framingSettleDelay = update_control 改配置后等待设备稳定的时间（防短快扫退化）。
const framingSettleDelay = 4 * time.Second

// runFramingStream exec lidar_cli framing-stream，读 stdout 帧协议：'m' 转 NATS、'r' 收结果；
// 见 READY 即 SCAN_START 起扫，进程结束 SCAN_STOP。返回 {ok,n_common,rms_m,b_to_a[16],a_frames,...}。
func (h *Handler) runFramingStream(ctx context.Context, owner int64, ipA, ipB, outJSON, markerLen, minCommon, previewWidth, sessionKey string) (map[string]any, error) {
	cli := localEnvOr("GOMOB_LASER_CLI_BIN", "server/native/lidar/build/lidar_cli")
	calib := localEnvOr("GOMOB_LASER_CALIB_DIR", "server/native/lidar/calib")
	args := []string{"framing-stream",
		ipA, filepath.Join(calib, "config_101_live.yaml"), filepath.Join(calib, "calib_101.json"),
		ipB, filepath.Join(calib, "config_102_live.yaml"), filepath.Join(calib, "calib_102.json"),
		outJSON, markerLen, minCommon, previewWidth}

	cctx, cancel := context.WithCancel(ctx)
	defer cancel()
	cmd := exec.CommandContext(cctx, cli, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("建 stdout 管道失败: %w", err)
	}
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("启动 lidar_cli framing-stream 失败（缺失？）: %w", err)
	}

	gate := h.newFramingGate(ipA, ipB)
	var (
		startOnce sync.Once
		started   bool
		gateErr   error
		result    map[string]any
	)
	startGate := func() {
		startOnce.Do(func() {
			if err := gate.Start(cctx); err != nil {
				gateErr = err
				cancel() // 起扫失败：取消采集进程，避免空转到超时
				return
			}
			started = true
		})
	}

	readErr := readFramingRecords(stdout, func(typ byte, payload []byte) {
		switch typ {
		case 'm':
			msg, ok := decodeFrameRecord(payload, owner, sessionKey)
			if !ok {
				return
			}
			if h.pub != nil {
				pctx, pc := context.WithTimeout(context.Background(), 2*time.Second)
				_ = h.pub.Publish(pctx, TopicLaserFrame, msg)
				pc()
			}
		case 's':
			var ev struct {
				Ev string `json:"ev"`
			}
			_ = json.Unmarshal(payload, &ev)
			if ev.Ev == "ready" {
				startGate()
			}
			h.log.Info("取景事件", "ev", string(payload))
		case 'r':
			var rr map[string]any
			if json.Unmarshal(payload, &rr) == nil {
				result = rr
			}
		}
	})

	waitErr := cmd.Wait()
	if started {
		_ = gate.Stop(context.WithoutCancel(ctx))
	}
	if gateErr != nil {
		return nil, fmt.Errorf("SCAN_START 失败: %w", gateErr)
	}
	if result == nil {
		detail := tail(stderr.String(), 400)
		if readErr != nil {
			detail = readErr.Error() + " " + detail
		}
		if waitErr != nil {
			detail = waitErr.Error() + " " + detail
		}
		return nil, fmt.Errorf("取景流未产出结果: %s", strings.TrimSpace(detail))
	}
	result["log"] = tail(stderr.String(), 1200)
	if m, ok := result["b_to_a"]; ok {
		result["matrix"] = m
	}
	return result, nil
}

func validFramingSessionKey(value string) bool {
	if len(value) < 1 || len(value) > 96 {
		return false
	}
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' || r == '.' {
			continue
		}
		return false
	}
	return true
}

func parseFramingSweep(q map[string][]string, startKey, stopKey, role string) (float64, float64, bool, error) {
	startRaw := strings.TrimSpace(firstQueryValue(q, startKey))
	stopRaw := strings.TrimSpace(firstQueryValue(q, stopKey))
	if startRaw == "" && stopRaw == "" {
		return 0, 0, false, nil
	}
	if startRaw == "" || stopRaw == "" {
		return 0, 0, false, fmt.Errorf("镜头 %s 的起止角必须同时提供", role)
	}
	start, err := strconv.ParseFloat(startRaw, 64)
	if err != nil {
		return 0, 0, false, fmt.Errorf("镜头 %s 起角不是有效数字", role)
	}
	stop, err := strconv.ParseFloat(stopRaw, 64)
	if err != nil {
		return 0, 0, false, fmt.Errorf("镜头 %s 止角不是有效数字", role)
	}
	if err := validateFramingSweep(start, stop); err != nil {
		return 0, 0, false, fmt.Errorf("镜头 %s：%w", role, err)
	}
	return start, stop, true, nil
}

func validateFramingSweep(start, stop float64) error {
	return validateScanAngles(start, stop)
}

func firstQueryValue(q map[string][]string, key string) string {
	values := q[key]
	if len(values) == 0 {
		return ""
	}
	return values[0]
}

type readyCheckedGate struct {
	base      DeviceGate
	probe     Prober
	ipA       string
	ipB       string
	operation string
}

func (g *readyCheckedGate) Start(ctx context.Context) error {
	for _, unit := range []struct {
		label string
		ip    string
	}{{"A", g.ipA}, {"B", g.ipB}} {
		probe := g.probe.Probe(ctx, unit.ip)
		if !probe.Reachable || !probe.Online {
			return fmt.Errorf("%s起扫前 unit%s(%s) 不可达或子系统离线: %s", g.operation, unit.label, unit.ip, probe.Err)
		}
		if probe.State != StateReady {
			return fmt.Errorf("%s起扫前 unit%s(%s) 状态为 %s，仅 READY 允许 SCAN_START", g.operation, unit.label, unit.ip, probe.State)
		}
	}
	return g.base.Start(ctx)
}

func (g *readyCheckedGate) Stop(ctx context.Context) error { return g.base.Stop(ctx) }

func (h *Handler) withReadyCheckedGate(ipA, ipB, operation string, base DeviceGate) DeviceGate {
	return &readyCheckedGate{base: base, probe: h.probe, ipA: ipA, ipB: ipB, operation: operation}
}

// newFramingGate 取景门控：起扫前紧邻复核双机 READY，再委托纯 SCAN_START/STOP。
func (h *Handler) newFramingGate(ipA, ipB string) DeviceGate {
	base := NewDevctlGate(ipA, ipB, 0, 0, 0, 0, false, h.log)
	return h.withReadyCheckedGate(ipA, ipB, "取景标定", base)
}

// readFramingRecords 读二进制帧协议 [4B BE N][1B type][N payload]，逐条回调。EOF 正常结束。
func readFramingRecords(r io.Reader, onRecord func(typ byte, payload []byte)) error {
	hdr := make([]byte, 5)
	for {
		if _, err := io.ReadFull(r, hdr); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				return nil
			}
			return err
		}
		n := binary.BigEndian.Uint32(hdr[:4])
		typ := hdr[4]
		// 防御：单条记录最大 64MB。越界=流被污染（如 native 误写文本到 stdout），明确报错而非试图分配巨块。
		if n > 64*1024*1024 {
			return fmt.Errorf("帧协议记录异常大(%d 字节)，stdout 可能被污染", n)
		}
		payload := make([]byte, n)
		if _, err := io.ReadFull(r, payload); err != nil {
			return err
		}
		onRecord(typ, payload)
	}
}

// decodeFrameRecord 解 'm' 帧 payload = [4B BE metaLen][meta JSON][jpeg] → NATS 载荷。
func decodeFrameRecord(payload []byte, owner int64, sessionKey string) (LaserFrameMsg, bool) {
	if len(payload) < 4 {
		return LaserFrameMsg{}, false
	}
	metaLen := binary.BigEndian.Uint32(payload[:4])
	if int(4+metaLen) > len(payload) {
		return LaserFrameMsg{}, false
	}
	var meta frameMeta
	if err := json.Unmarshal(payload[4:4+metaLen], &meta); err != nil {
		return LaserFrameMsg{}, false
	}
	jpeg := payload[4+metaLen:]
	o := owner
	return LaserFrameMsg{
		OwnerUserID: &o,
		SessionKey:  sessionKey,
		Unit:        meta.Unit,
		Seq:         meta.Seq,
		HeadingDeg:  meta.Heading,
		W:           meta.W,
		H:           meta.H,
		JPEGB64:     base64.StdEncoding.EncodeToString(jpeg),
		Markers:     meta.Markers,
	}, true
}

func parseFloatQ(q map[string][]string, key string) (float64, bool) {
	vs, ok := q[key]
	if !ok || len(vs) == 0 {
		return 0, false
	}
	f, err := strconv.ParseFloat(strings.TrimSpace(vs[0]), 64)
	if err != nil {
		return 0, false
	}
	return f, true
}
