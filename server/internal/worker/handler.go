// Package worker —— gomob 异步任务工人（M-S5.3）。
//
// 当前阶段消费一种事件：
//
//	inspection.scan_completed  payload {inspection_id, vehicle_model_id, vin, characters[]}
//	  → 每个 character 的扫描图（characters[i].alpha_object_key）调 cv-engine
//	    /cv/ocr/v1/vin_character_compare_with_ref 与厂家库比对
//	  → 聚合 17 个字符的 similarity 算总分
//	  → repo.UpdatePreliminary 写 preliminary_verdict + preliminary_reasons
//	  → repo.Transition 把状态从 scanning → preliminary
//	  → publish inspection.preliminary_done（signaling / App 收到后推消息）
//
// 真业务后续会从扫描端拍到的整张 VIN 图开始走 ProcVINDet 切字符（M-S10 Phase 2.x）；
// 当前 worker 接受"已切好的字符 alpha key 列表"作为输入，负责厂家库对照 + 写库。
package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/nats-io/nats.go"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

const (
	TopicScanCompleted    = "inspection.scan_completed"
	TopicPreliminaryDone  = "inspection.preliminary_done"

	// 总分阈值 — 与厂家库平均相似度判定 verdict
	verdictPassThreshold    = 0.85
	verdictWarningThreshold = 0.60
)

// Config 全部依赖。
type Config struct {
	NATSConn        *nats.Conn
	Pool            *pgxpool.Pool
	CVEngineTarget  string         // http://127.0.0.1:18810
	HTTPClient      *http.Client   // 默认 30s timeout
	Audit           audit.Recorder
	Publisher       pubsub.Publisher

	// MinIO 直接拉字符 alpha 字节（与 vin-ref 同 bucket）
	MinIOEndpoint   string
	MinIOAccessKey  string
	MinIOSecretKey  string
	MinIOUseSSL     bool
	Bucket          string

	Log             *slog.Logger
}

// CharacterScan worker 收到的事件里，每个字符的扫描快照。
type CharacterScan struct {
	Position       int    `json:"position"`        // 1..17
	Character      string `json:"character"`       // 检测到的字符（非合法 VIN 字符返 ""）
	AlphaObjectKey string `json:"alpha_object_key"` // MinIO key（worker 直拉）
}

// ScanCompletedEvent NATS 入参。
type ScanCompletedEvent struct {
	InspectionID   int64           `json:"inspection_id"`
	VehicleModelID int64           `json:"vehicle_model_id"`
	VIN            string          `json:"vin"`
	Characters     []CharacterScan `json:"characters"`
}

// PreliminaryDoneEvent worker 写完 preliminary 后发出，signaling / App 监听这个推 ws 消息。
type PreliminaryDoneEvent struct {
	InspectionID int64    `json:"inspection_id"`
	Verdict      string   `json:"verdict"`
	Reasons      []string `json:"reasons"`
	Score        float64  `json:"score"`
	CompletedAt  string   `json:"completed_at"`
}

// Worker 业务句柄。
type Worker struct {
	cfg    Config
	insps  *repo.InspectionRepo
	mc     *minio.Client
	log    *slog.Logger
}

func New(cfg Config) (*Worker, error) {
	if cfg.NATSConn == nil {
		return nil, errors.New("NATSConn 必填")
	}
	if cfg.Pool == nil {
		return nil, errors.New("Pool 必填")
	}
	if cfg.HTTPClient == nil {
		cfg.HTTPClient = &http.Client{Timeout: 30 * time.Second}
	}
	if cfg.Log == nil {
		cfg.Log = logger.New("worker")
	}
	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("minio: %w", err)
	}
	return &Worker{
		cfg:    cfg,
		insps:  repo.NewInspectionRepo(cfg.Pool),
		mc:     mc,
		log:    cfg.Log,
	}, nil
}

// Run 订阅 NATS 主题，阻塞返。ctx 取消时取消订阅 + drain。
func (w *Worker) Run(ctx context.Context) error {
	sub, err := w.cfg.NATSConn.Subscribe(TopicScanCompleted, func(msg *nats.Msg) {
		jobCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if err := w.handle(jobCtx, msg.Data); err != nil {
			w.log.Error("处理失败", "err", err, "data", string(msg.Data[:min(200, len(msg.Data))]))
		}
	})
	if err != nil {
		return fmt.Errorf("subscribe: %w", err)
	}
	defer func() { _ = sub.Unsubscribe() }()
	w.log.Info("worker 已订阅", "topic", TopicScanCompleted)

	<-ctx.Done()
	return nil
}

// handle 处理一条 scan_completed 事件。
func (w *Worker) handle(ctx context.Context, data []byte) error {
	var ev ScanCompletedEvent
	if err := json.Unmarshal(data, &ev); err != nil {
		return fmt.Errorf("payload 解析: %w", err)
	}
	if ev.InspectionID <= 0 {
		return errors.New("inspection_id 必填")
	}
	if len(ev.Characters) == 0 {
		return errors.New("characters 为空")
	}
	w.log.Info("收到 scan_completed",
		"inspection_id", ev.InspectionID,
		"vehicle_model_id", ev.VehicleModelID,
		"vin", ev.VIN,
		"chars", len(ev.Characters))

	// 逐字符调 cv-engine 比对
	type charResult struct {
		Position   int
		Character  string
		Similarity float64
		Reason     string // 失败原因（缺图 / 不合法 / cv-engine 报错）
	}
	results := make([]charResult, 0, len(ev.Characters))

	for _, c := range ev.Characters {
		if !isValidVinChar(c.Character) {
			results = append(results, charResult{
				Position:  c.Position,
				Character: c.Character,
				Reason:    "字符不在 VIN 33 字符集内",
			})
			continue
		}
		if c.AlphaObjectKey == "" {
			results = append(results, charResult{
				Position:  c.Position,
				Character: c.Character,
				Reason:    "缺 alpha_object_key",
			})
			continue
		}
		// 拉 MinIO 字节
		alpha, err := w.fetchObject(ctx, c.AlphaObjectKey)
		if err != nil {
			w.log.Warn("MinIO 拉取失败", "err", err, "key", c.AlphaObjectKey)
			results = append(results, charResult{
				Position:  c.Position,
				Character: c.Character,
				Reason:    fmt.Sprintf("MinIO 拉取: %v", err),
			})
			continue
		}
		// 调 cv-engine
		sim, err := w.compareWithRef(ctx, ev.VehicleModelID, c.Character, alpha)
		if err != nil {
			results = append(results, charResult{
				Position:  c.Position,
				Character: c.Character,
				Reason:    fmt.Sprintf("cv-engine: %v", err),
			})
			continue
		}
		results = append(results, charResult{
			Position:   c.Position,
			Character:  c.Character,
			Similarity: sim,
		})
	}

	// 聚合
	total := 0.0
	scored := 0
	var reasons []string
	for _, r := range results {
		if r.Reason != "" {
			reasons = append(reasons, fmt.Sprintf("位置%d 字符 %s: %s", r.Position, r.Character, r.Reason))
			continue
		}
		total += r.Similarity
		scored++
	}
	avg := 0.0
	if scored > 0 {
		avg = total / float64(scored)
	}
	verdict := decideVerdict(avg, scored, len(ev.Characters))
	if verdict != "pass" {
		// 给低分字符也加一条原因
		for _, r := range results {
			if r.Reason == "" && r.Similarity < verdictWarningThreshold {
				reasons = append(reasons, fmt.Sprintf("位置%d 字符 %s 厂家相似度=%.3f", r.Position, r.Character, r.Similarity))
			}
		}
	}
	if len(reasons) == 0 {
		reasons = []string{fmt.Sprintf("avg_similarity=%.3f scored=%d/%d", avg, scored, len(ev.Characters))}
	}

	// 写库
	if err := w.insps.UpdatePreliminary(ctx, ev.InspectionID, verdict, reasons); err != nil {
		return fmt.Errorf("UpdatePreliminary: %w", err)
	}
	// 状态机推进 scanning → preliminary（容忍当前 status 已经是 preliminary 的幂等）
	if err := w.insps.Transition(ctx, ev.InspectionID, []string{"scanning", "preliminary"}, "preliminary"); err != nil {
		// 状态机不允许时不致命；写预审已成功
		w.log.Warn("Transition scanning→preliminary 失败", "err", err, "inspection_id", ev.InspectionID)
	}

	// audit
	if w.cfg.Audit != nil {
		afterRaw, _ := audit.Encode(map[string]any{
			"verdict":  verdict,
			"avg":      avg,
			"scored":   scored,
			"total":    len(ev.Characters),
			"reasons":  reasons,
		})
		_ = w.cfg.Audit.Record(ctx, audit.Entry{
			Action:   "worker.preliminary_done",
			Target:   "inspection:" + strconv.FormatInt(ev.InspectionID, 10),
			AfterRaw: afterRaw,
		})
	}

	// 发完成事件
	if w.cfg.Publisher != nil {
		done := PreliminaryDoneEvent{
			InspectionID: ev.InspectionID,
			Verdict:      verdict,
			Reasons:      reasons,
			Score:        avg,
			CompletedAt:  time.Now().UTC().Format(time.RFC3339Nano),
		}
		if err := w.cfg.Publisher.Publish(ctx, TopicPreliminaryDone, done); err != nil {
			w.log.Warn("publish preliminary_done 失败", "err", err)
		}
	}

	w.log.Info("preliminary 完成",
		"inspection_id", ev.InspectionID,
		"verdict", verdict,
		"avg", avg, "scored", scored)
	return nil
}

func decideVerdict(avg float64, scored, total int) string {
	if scored == 0 {
		return "fail"
	}
	missingRatio := float64(total-scored) / float64(total)
	if missingRatio > 0.3 {
		return "fail"
	}
	if avg >= verdictPassThreshold && missingRatio == 0 {
		return "pass"
	}
	if avg >= verdictWarningThreshold {
		return "warning"
	}
	return "fail"
}

// fetchObject MinIO 直拉对象字节。
func (w *Worker) fetchObject(ctx context.Context, key string) ([]byte, error) {
	rctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	obj, err := w.mc.GetObject(rctx, w.cfg.Bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer obj.Close()
	return io.ReadAll(io.LimitReader(obj, 16<<20))
}

// compareWithRef 调 cv-engine /cv/ocr/v1/vin_character_compare_with_ref。
func (w *Worker) compareWithRef(ctx context.Context, vmid int64, character string, scan []byte) (float64, error) {
	body := &bytes.Buffer{}
	mw := multipart.NewWriter(body)
	if err := mw.WriteField("vehicle_model_id", strconv.FormatInt(vmid, 10)); err != nil {
		return 0, err
	}
	if err := mw.WriteField("character", strings.ToUpper(character)); err != nil {
		return 0, err
	}
	if err := mw.WriteField("method", "0"); err != nil {
		return 0, err
	}
	fw, err := mw.CreateFormFile("image_binary", "scan.webp")
	if err != nil {
		return 0, err
	}
	if _, err := fw.Write(scan); err != nil {
		return 0, err
	}
	mw.Close()

	rctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodPost,
		w.cfg.CVEngineTarget+"/cv/ocr/v1/vin_character_compare_with_ref", body)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Gomob-User-Id", "0")          // worker 内部调用，gateway 注入约定
	req.Header.Set("X-Gomob-Roles", "inspector")    // 任意非空角色都过 require_auth
	resp, err := w.cfg.HTTPClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("cv-engine http=%d body=%s", resp.StatusCode, string(respBody))
	}
	var env struct {
		Code int `json:"code"`
		Data struct {
			Best struct {
				Similarity float64 `json:"similarity"`
			} `json:"best"`
		} `json:"data"`
		Msg string `json:"message,omitempty"`
	}
	if err := json.Unmarshal(respBody, &env); err != nil {
		return 0, err
	}
	if env.Code != 0 {
		return 0, fmt.Errorf("cv-engine code=%d %s", env.Code, env.Msg)
	}
	return env.Data.Best.Similarity, nil
}

func isValidVinChar(c string) bool {
	if len(c) != 1 {
		return false
	}
	b := c[0]
	if b >= 'a' && b <= 'z' {
		b -= 32
	}
	switch {
	case b >= '0' && b <= '9':
		return true
	case b >= 'A' && b <= 'Z':
		return b != 'I' && b != 'O' && b != 'Q'
	}
	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
