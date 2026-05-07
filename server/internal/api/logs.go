// 端侧 → 服务端日志同步：POST /v1/logs/upload
//
// 业务定位：用户在端侧扫描时（M3 三维外廓 / M4 VIN 拓印），native + Kotlin 侧产生大量诊断日志
// （ICP / TSDF voxel 积分计数 / SDK 状态 / 异常栈），原本只在 logcat 里转瞬即逝。开启日志同步后，
// 端侧 LogcatTailer 把指定 tag 的日志按批 POST 到这里，服务端按用户落 jsonl，开发端用
// `scripts/tail-user-logs.sh <user_id>` tail -f 看实时排查。
//
// 第一性：不接受"先上线再看"。日志要 user_id 标定（从 gateway 注入的 X-Gomob-User-Id 拿）；
// jsonl 一行一条不丢字段；服务端不做解析（日志是 grep / jq 友好的，复杂分析交给端到端的 OpenTelemetry / Loki 后续）。
//
// 错误码：
//   40110 — body 不是合法 JSON / 不是 `{"entries":[...]}` 形态
//   40111 — entries 数组空 或 超过单批次上限（默认 500 条）
//   10001 — 单条 entry 字段缺失（ts/level/tag/msg 任一空）

package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"io.gomob/server/pkg/httpx"
)

// LogEntry — 端侧上传的单条日志
type LogEntry struct {
	// 端侧观测的时间戳（毫秒 Unix epoch；time.now() 时取，不是 logcat 行的时间）
	TsMs int64 `json:"ts_ms"`
	// V/D/I/W/E/F；端侧从 logcat -v time 解析；缺省 I
	Level string `json:"level"`
	// logcat tag（如 "gomob_native" / "Scan3dRecordingVM" / "BerxelService"）
	Tag string `json:"tag"`
	// 单行消息内容
	Msg string `json:"msg"`
	// 端侧设备 SN（可选，便于多设备区分）
	DeviceSerial string `json:"device_serial,omitempty"`
	// 端侧 frameIndex / sessionId 等（可选，便于 jq 过滤）
	Extra json.RawMessage `json:"extra,omitempty"`
}

type LogUploadReq struct {
	Entries []LogEntry `json:"entries"`
}

type LogsHandler struct {
	root        string // ${LOG_DIR}/uploads/，按 <user_id>/<YYYY-MM-DD>.jsonl
	maxBatch    int
	mu          sync.Mutex // 保护 file handle map
	openFiles   map[string]*os.File
	openFilesAt map[string]time.Time // 用于 idle close（30 分钟无写入则关）
}

// NewLogsHandler — root 是日志根目录（启动期 mkdir）；maxBatch 是单 POST 最多条数；0 → 默认 500
func NewLogsHandler(root string, maxBatch int) (*LogsHandler, error) {
	if root == "" {
		root = ".dev/server-logs"
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, fmt.Errorf("mkdir log root %s: %w", root, err)
	}
	if maxBatch <= 0 {
		maxBatch = 500
	}
	return &LogsHandler{
		root:        root,
		maxBatch:    maxBatch,
		openFiles:   make(map[string]*os.File),
		openFilesAt: make(map[string]time.Time),
	}, nil
}

func (h *LogsHandler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/logs/upload", h.Upload)
}

func (h *LogsHandler) Upload(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		// gateway 模式下 callerUserID 来自 X-Gomob-User-Id；缺失说明 gateway 没认证 → 401
		httpx.WriteError(w, httpx.NewError(40102, http.StatusUnauthorized, "缺少 X-Gomob-User-Id"))
		return
	}

	var req LogUploadReq
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		httpx.WriteError(w, httpx.NewError(40110, http.StatusBadRequest, "body 不是合法 LogUploadReq："+err.Error()))
		return
	}
	if len(req.Entries) == 0 {
		httpx.WriteError(w, httpx.NewError(40111, http.StatusBadRequest, "entries 不能为空"))
		return
	}
	if len(req.Entries) > h.maxBatch {
		httpx.WriteError(w, httpx.NewError(40111, http.StatusBadRequest,
			fmt.Sprintf("单批次最多 %d 条，收到 %d", h.maxBatch, len(req.Entries))))
		return
	}

	for i, e := range req.Entries {
		if e.TsMs == 0 || e.Tag == "" || e.Msg == "" {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
				fmt.Sprintf("entries[%d] 缺 ts_ms/tag/msg", i)))
			return
		}
	}

	if err := h.appendBatch(uid, req.Entries); err != nil {
		httpx.WriteError(w, httpx.NewError(50001, http.StatusInternalServerError, "落盘失败："+err.Error()))
		return
	}
	httpx.OK(w, map[string]any{
		"accepted": len(req.Entries),
	})
}

func (h *LogsHandler) appendBatch(userID int64, entries []LogEntry) error {
	// 按上传时刻的 UTC 日期切片；同一文件单 user 当天所有日志（jsonl 一行一条）
	day := time.Now().UTC().Format("2006-01-02")
	key := fmt.Sprintf("%d/%s", userID, day)

	h.mu.Lock()
	f, ok := h.openFiles[key]
	if !ok {
		dir := filepath.Join(h.root, fmt.Sprintf("%d", userID))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			h.mu.Unlock()
			return fmt.Errorf("mkdir %s: %w", dir, err)
		}
		path := filepath.Join(dir, day+".jsonl")
		var err error
		f, err = os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			h.mu.Unlock()
			return fmt.Errorf("open %s: %w", path, err)
		}
		h.openFiles[key] = f
	}
	h.openFilesAt[key] = time.Now()
	h.mu.Unlock()

	// 单行 JSON 字符串拼接（不要给 Encoder 因为它会写 buffered + Flush 难控）
	var sb strings.Builder
	for _, e := range entries {
		// 服务端补一个收到时间，便于排查端侧时钟漂移
		line := struct {
			LogEntry
			RecvMs   int64 `json:"recv_ms"`
			UserID   int64 `json:"user_id"`
		}{e, time.Now().UnixMilli(), userID}
		buf, err := json.Marshal(line)
		if err != nil {
			return fmt.Errorf("marshal entry: %w", err)
		}
		sb.Write(buf)
		sb.WriteByte('\n')
	}
	_, err := f.WriteString(sb.String())
	return err
}

// CloseIdle — 给主程序定时调，关 30 分钟无写入的 file handle 防泄漏
func (h *LogsHandler) CloseIdle(idle time.Duration) {
	cutoff := time.Now().Add(-idle)
	h.mu.Lock()
	defer h.mu.Unlock()
	for k, t := range h.openFilesAt {
		if t.Before(cutoff) {
			if f, ok := h.openFiles[k]; ok {
				_ = f.Close()
			}
			delete(h.openFiles, k)
			delete(h.openFilesAt, k)
		}
	}
}

// Close — main 退出时调
func (h *LogsHandler) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for k, f := range h.openFiles {
		_ = f.Close()
		delete(h.openFiles, k)
		delete(h.openFilesAt, k)
	}
}
