package laser

// sitecalib.go — 现场共享 ArUco 标记场「一键自动标定」A↔B site 外参。
// 流程：tryReserve(与扫描互斥) → 探活 → 触发两单元 gated raw sweep + 图像落盘(dump env) →
//        exec lidar_cli calib-site-markers 解 B→A → 返回 {ok,n_common,rms_m,b_to_a[16]}。
// 解算器(Umeyama+OpenCV aruco)走独立 lidar_cli，不进 cgo 精简库。详见 docs/architecture/17 §9.5。

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// SiteCalib POST /v1/scans/laser/site-calib?unit_a_ip=&unit_b_ip=&marker_len=0.15&min_common=4
func (h *Handler) SiteCalib(w http.ResponseWriter, r *http.Request) {
	ipA := h.cfg.DefaultUnitAIP
	if v, ok := normalizeOptionalIPv4(r.URL.Query().Get("unit_a_ip")); ok && v != "" {
		ipA = v
	}
	ipB := h.cfg.DefaultUnitBIP
	if v, ok := normalizeOptionalIPv4(r.URL.Query().Get("unit_b_ip")); ok && v != "" {
		ipB = v
	}
	markerLen := strings.TrimSpace(r.URL.Query().Get("marker_len"))
	if markerLen == "" {
		markerLen = "0.15"
	}
	minCommon := strings.TrimSpace(r.URL.Query().Get("min_common"))
	if minCommon == "" {
		minCommon = "2" // 角点法单标记即可解，2 做冗余（旧中心法需 ≥4）
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

	base, err := os.MkdirTemp("", "site-calib-")
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "建临时目录失败: "+err.Error())
		return
	}
	defer os.RemoveAll(base)
	dirA, dirB := filepath.Join(base, "a"), filepath.Join(base, "b")

	// 采图：dump env 落盘两单元图像 + 关纹理（只采图不上色）。串行下 env 安全（tryReserve 已互斥）。
	os.Setenv("GOMOB_LASER_MARKER_DUMP_A", dirA)
	os.Setenv("GOMOB_LASER_MARKER_DUMP_B", dirB)
	prevTex, hadTex := os.LookupEnv("GOMOB_LASER_TEXTURE")
	os.Setenv("GOMOB_LASER_TEXTURE", "off")
	defer func() {
		os.Unsetenv("GOMOB_LASER_MARKER_DUMP_A")
		os.Unsetenv("GOMOB_LASER_MARKER_DUMP_B")
		if hadTex {
			os.Setenv("GOMOB_LASER_TEXTURE", prevTex)
		} else {
			os.Unsetenv("GOMOB_LASER_TEXTURE")
		}
	}()

	h.runner.Gate = h.newGate(ipA, ipB)
	if err := h.runner.CaptureMarkers(ctx, ipA, ipB); err != nil {
		writeErr(w, http.StatusBadGateway, "标定采图失败: "+err.Error())
		return
	}

	outJSON := filepath.Join(base, "site_extrinsic.json")
	res, err := solveSiteMarkers(ctx, dirA, dirB, outJSON, markerLen, minCommon)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, res)
}

// CaptureMarkers 触发两单元 gated sweep + 采图（图像落盘由 dump env 控制），align=raw 不融合/不存储。
func (r *Runner) CaptureMarkers(ctx context.Context, ipA, ipB string) error {
	if r.Live == nil {
		r.Live = LiveScan
	}
	var once sync.Once
	var mu sync.Mutex
	var started bool
	var gateErr error
	startGate := func() {
		if r.Gate == nil {
			return
		}
		once.Do(func() {
			if err := r.Gate.Start(ctx); err != nil {
				mu.Lock()
				gateErr = err
				mu.Unlock()
				CancelScan()
				return
			}
			mu.Lock()
			started = true
			mu.Unlock()
		})
	}
	defer func() {
		if r.Gate == nil {
			return
		}
		mu.Lock()
		s := started
		mu.Unlock()
		if s {
			_ = r.Gate.Stop(context.WithoutCancel(ctx))
		}
	}()

	cb := ScanCallbacks{
		OnPoints:      func(PointFrame) {},
		OnColorPoints: func(ColorPointFrame) {}, // 非空 → 触发两单元图像采集线程
		OnStatus: func(state string, _, _ int) {
			if state == "armed" {
				startGate()
			}
		},
	}
	_, err := r.Live(ipA, ipB, "raw", "", 1.0, cb)
	mu.Lock()
	ge := gateErr
	mu.Unlock()
	if ge != nil {
		return fmt.Errorf("SCAN_START 失败: %w", ge)
	}
	return err
}

var siteMarkerRe = regexp.MustCompile(`ok=(\d+)\s+common=(\d+)\s+rms=([0-9.eE+-]+)m`)

// solveSiteMarkers exec lidar_cli calib-site-markers，解析结果 + 读 B→A。退码 1(未达标)也解析输出。
func solveSiteMarkers(ctx context.Context, dirA, dirB, outJSON, markerLen, minCommon string) (map[string]any, error) {
	cli := localEnvOr("GOMOB_LASER_CLI_BIN", "server/native/lidar/build/lidar_cli")
	calib := localEnvOr("GOMOB_LASER_CALIB_DIR", "server/native/lidar/calib")
	args := []string{"calib-site-markers",
		dirA, filepath.Join(calib, "config_101_live.yaml"), filepath.Join(calib, "calib_101.json"),
		dirB, filepath.Join(calib, "config_102_live.yaml"), filepath.Join(calib, "calib_102.json"),
		outJSON, markerLen, minCommon}
	out, _ := exec.CommandContext(ctx, cli, args...).CombinedOutput()
	text := string(out)

	m := siteMarkerRe.FindStringSubmatch(text)
	if m == nil {
		return nil, fmt.Errorf("解算器未产出结果（lidar_cli 缺失或采图为空）: %s", strings.TrimSpace(tail(text, 400)))
	}
	common, _ := strconv.Atoi(m[2])
	rms, _ := strconv.ParseFloat(m[3], 64)
	ok := strings.Contains(text, "ok=1")
	res := map[string]any{"ok": ok, "n_common": common, "rms_m": rms, "log": tail(text, 1200)}
	if ok {
		if b, err := os.ReadFile(outJSON); err == nil {
			var parsed struct {
				BToA []float64 `json:"b_to_a"`
			}
			if json.Unmarshal(b, &parsed) == nil && len(parsed.BToA) == 16 {
				res["b_to_a"] = parsed.BToA
				res["matrix"] = parsed.BToA
			}
		}
	}
	return res, nil
}

func localEnvOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func tail(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}
