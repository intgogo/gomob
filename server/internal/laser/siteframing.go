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
	"fmt"
	"io"
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
	q := r.URL.Query()
	ipA := h.cfg.DefaultUnitAIP
	if v, ok := normalizeOptionalIPv4(q.Get("unit_a_ip")); ok && v != "" {
		ipA = v
	}
	ipB := h.cfg.DefaultUnitBIP
	if v, ok := normalizeOptionalIPv4(q.Get("unit_b_ip")); ok && v != "" {
		ipB = v
	}
	markerLen := strings.TrimSpace(q.Get("marker_len"))
	if markerLen == "" {
		markerLen = "0.15"
	}
	minCommon := strings.TrimSpace(q.Get("min_common"))
	if minCommon == "" {
		minCommon = "2" // 角点法单标记即可解，2 做冗余（旧中心法需 ≥4）
	}
	previewWidth := strings.TrimSpace(q.Get("preview_width"))
	if previewWidth == "" {
		previewWidth = "1280"
	}

	if !h.sessions.tryReserve() {
		writeErr(w, http.StatusConflict, "有扫描/标定进行中，请稍后再试")
		return
	}
	defer h.sessions.release()

	ctx := r.Context()
	if pr := h.probe.Probe(ctx, ipA); !pr.Reachable || !pr.Online {
		writeErr(w, http.StatusBadGateway, "unitA("+ipA+") 不可达或子系统离线: "+pr.Err)
		return
	}
	if pr := h.probe.Probe(ctx, ipB); !pr.Reachable || !pr.Online {
		writeErr(w, http.StatusBadGateway, "unitB("+ipB+") 不可达或子系统离线: "+pr.Err)
		return
	}

	// 设扫描角/速度（云台控制）：仅当请求带角度时下发，read-modify-write 保留其它运动参数。
	if as, ok := parseFloatQ(q, "a_start"); ok {
		if astp, ok2 := parseFloatQ(q, "a_stop"); ok2 {
			h.applyFramingControl(ctx, ipA, as, astp, q.Get("speed"), "A")
		}
	}
	if bs, ok := parseFloatQ(q, "b_start"); ok {
		if bstp, ok2 := parseFloatQ(q, "b_stop"); ok2 {
			h.applyFramingControl(ctx, ipB, bs, bstp, q.Get("speed"), "B")
		}
	}

	base, err := os.MkdirTemp("", "site-framing-")
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "建临时目录失败: "+err.Error())
		return
	}
	defer os.RemoveAll(base)
	outJSON := filepath.Join(base, "site_extrinsic.json")

	res, err := h.runFramingStream(ctx, owner, ipA, ipB, outJSON, markerLen, minCommon, previewWidth)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// applyFramingControl 读-改-写单元扫描角/速度（保留其它运动参数）。失败只记日志不阻断（沿用设备值）。
//
// ★ 设备实测雷点（2026-06-15）：扫前用 update_control 重设角/速度，会让随后的 SCAN sweep 忽略慢速、
// 退化成 ~12s 短快扫 → 0~2 帧（相机 0.33fps 抓不到）；而持久化配置不重设时 sweep 慢而长（[0,90]@0.5
// 跑满 180s → 6 帧）。故：① 已是目标值就**不重发**（幂等）；② 确需改时改完**留够 settle 时间**让设备稳定。
func (h *Handler) applyFramingControl(ctx context.Context, ip string, start, stop float64, speedStr, tag string) {
	dev := h.newDev(ip)
	info, err := dev.GetInfo(ctx)
	if err != nil {
		h.log.Warn("取景：读控制设置失败，沿用设备持久化值", "unit", tag, "err", err)
		return
	}
	ctrl := info.Control
	wantSpeed := ctrl.ScanSpeed
	if sp, perr := strconv.ParseFloat(strings.TrimSpace(speedStr), 64); perr == nil && sp > 0 {
		wantSpeed = sp
	}
	// 幂等：已是目标角/速度就不重发，避免触发"扫前重设→短快扫"退化。
	if ctrl.ScanStartAngle == start && ctrl.ScanStopAngle == stop && ctrl.ScanSpeed == wantSpeed {
		h.log.Info("取景：扫描角/速度已是目标值，免重设", "unit", tag, "start", start, "stop", stop, "speed", wantSpeed)
		return
	}
	ctrl.ScanStartAngle = start
	ctrl.ScanStopAngle = stop
	ctrl.ScanSpeed = wantSpeed
	if err := dev.UpdateControl(ctx, ctrl); err != nil {
		h.log.Warn("取景：下发扫描角/速度失败，沿用设备持久化值", "unit", tag, "err", err)
		return
	}
	h.log.Info("取景：已设扫描角/速度（settle 中）", "unit", tag, "start", start, "stop", stop, "speed", wantSpeed)
	// 改配置后让设备稳定（应用参数/可能回中），否则随后 sweep 退化为短快扫。
	select {
	case <-ctx.Done():
	case <-time.After(framingSettleDelay):
	}
}

// framingSettleDelay = update_control 改配置后等待设备稳定的时间（防短快扫退化）。
const framingSettleDelay = 4 * time.Second

// runFramingStream exec lidar_cli framing-stream，读 stdout 帧协议：'m' 转 NATS、'r' 收结果；
// 见 READY 即 SCAN_START 起扫，进程结束 SCAN_STOP。返回 {ok,n_common,rms_m,b_to_a[16],a_frames,...}。
func (h *Handler) runFramingStream(ctx context.Context, owner int64, ipA, ipB, outJSON, markerLen, minCommon, previewWidth string) (map[string]any, error) {
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
		sessionKey = "site-framing"
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

// newFramingGate 取景门控：纯 SCAN_START/STOP（角度/速度已由 applyFramingControl 单独下发）。
func (h *Handler) newFramingGate(ipA, ipB string) DeviceGate {
	return NewDevctlGate(ipA, ipB, 0, 0, 0, 0, false, h.log)
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
