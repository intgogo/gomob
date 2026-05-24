// gomob-devserver — 开发模式合体进程。
//
// 环境变量：
//
//	GOMOB_LISTEN           HTTP 监听地址（默认 :18808）
//	GOMOB_DISCOVERY_ADDR   UDP 服务发现监听地址（默认 :18809；空字符串禁用）
//	GOMOB_DISCOVERY_NAME   服务发现展示名称（默认 gomob-devserver）
//
// 当前装载 auth / api / asset / signaling 路由（接 PostgreSQL）。
// 保持 devserver 永远是"全部已实现路由"的并集，方便 App 只连一个开发网关。
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"golang.org/x/crypto/bcrypt"

	"io.gomob/server/internal/api"
	"io.gomob/server/internal/asr"
	"io.gomob/server/internal/asset"
	"io.gomob/server/internal/auth"
	"io.gomob/server/internal/gateway"
	"io.gomob/server/internal/signaling"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("devserver")
	log.Info("gomob-devserver starting", "version", "0.1.0")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("connect pg failed", "err", err)
		os.Exit(1)
	}
	defer pool.Close()
	log.Info("pg connected")
	if os.Getenv("GOMOB_DEV_SEED_LOGIN") != "false" {
		if err := ensureDevSeedLogin(ctx, pool); err != nil {
			log.Warn("dev seed 登录用户准备失败", "err", err)
		} else {
			log.Info("dev seed 登录用户已就绪", "username", devSeedUsername)
		}
	}

	devAutoActivate := os.Getenv("GOMOB_DEV_AUTO_ACTIVATE") != "false"
	authH := auth.NewHandler(pool, devAutoActivate)
	signalingHub := signaling.NewHub()
	signalingRouter := signaling.NewRouter(pool, signalingHub, audit.NewPG(pool), parseDuration("GOMOB_PENDING_CALL_TTL", 60*time.Second))
	signalingH := signaling.NewHandler(signalingRouter, signalingHub)
	apiH := api.NewHandler(pool, audit.NewPG(pool), rbac.Baseline())
	transcriptCfg := transcriptConfigFromEnv()
	apiH.SetTranscriptConfig(transcriptCfg)
	signalingRouter.SetTranscriptConfig(transcriptCfg)
	apiH.SetRealtimeMessageNotifier(signalingRouter)
	startASRWorker(ctx, pool, signalingRouter, transcriptCfg, log)
	assetH := newDevAssetHandler(ctx, pool, log)
	logRoot := envOr("GOMOB_LOG_UPLOAD_DIR", ".dev/server-logs")
	logsH, err := api.NewLogsHandler(logRoot, 0)
	if err != nil {
		log.Error("logs handler 初始化失败", "err", err)
		os.Exit(1)
	}
	defer logsH.Close()
	log.Info("logs upload 已挂载", "root", logRoot)

	mux := http.NewServeMux()

	// 公共 endpoints
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintln(w, "ok")
	})
	mux.HandleFunc("/v1/version", func(w http.ResponseWriter, _ *http.Request) {
		httpx.OK(w, map[string]string{
			"name":          "gomob-devserver",
			"version":       "0.1.0",
			"auto_activate": boolStr(devAutoActivate),
		})
	})

	// 不需登录的 auth 路由
	mux.HandleFunc("POST /v1/auth/register", authH.Register)
	mux.HandleFunc("POST /v1/auth/login", authH.Login)
	mux.HandleFunc("POST /v1/auth/refresh", authH.Refresh)
	signalingH.Mount(mux)
	go signalingRouter.SweepLoop(ctx, parseDuration("GOMOB_PENDING_CALL_SWEEP", 30*time.Second))

	// 受保护路由（包一层 Required）
	protected := http.NewServeMux()
	protected.HandleFunc("POST /v1/auth/password", authH.ChangePassword)
	protected.HandleFunc("GET /v1/me", authH.Me)
	apiH.Mount(protected)
	if assetH != nil {
		assetH.Mount(protected)
	} else {
		protected.HandleFunc("/v1/assets/", func(w http.ResponseWriter, _ *http.Request) {
			httpx.WriteError(w, httpx.NewError(40501, http.StatusServiceUnavailable, "资产服务未配置：请启动 MinIO 或设置 GOMOB_MINIO_*"))
		})
	}
	logsH.Mount(protected)
	protectedHandler := auth.Required(protected)
	mux.Handle("/v1/auth/password", protectedHandler)
	mux.Handle("/v1/me", protectedHandler)
	mux.Handle("/v1/inspections", protectedHandler)
	mux.Handle("/v1/inspections/", protectedHandler)
	mux.Handle("/v1/reviews", protectedHandler)
	mux.Handle("/v1/reviews/", protectedHandler)
	mux.Handle("/v1/conversations", protectedHandler)
	mux.Handle("/v1/conversations/", protectedHandler)
	mux.Handle("/v1/contacts", protectedHandler)
	mux.Handle("/v1/messages/", protectedHandler)
	mux.Handle("/v1/assets/", protectedHandler)
	mux.Handle("/v1/media/", protectedHandler)
	mux.Handle("/v1/live-sessions", protectedHandler)
	mux.Handle("/v1/live-sessions/", protectedHandler)
	mux.Handle("/v1/livekit/", protectedHandler)
	mux.Handle("/v1/logs/upload", protectedHandler)

	addr := os.Getenv("GOMOB_LISTEN")
	if addr == "" {
		addr = ":18808"
	}
	discoveryAddr := gateway.DefaultDiscoveryAddr
	if v, ok := os.LookupEnv("GOMOB_DISCOVERY_ADDR"); ok {
		discoveryAddr = v
	}
	discoveryName := os.Getenv("GOMOB_DISCOVERY_NAME")
	if discoveryName == "" {
		discoveryName = "gomob-devserver"
	}
	if err := gateway.StartDiscoveryResponder(ctx, discoveryAddr, addr, discoveryName, log); err != nil {
		log.Warn("UDP 服务发现不可用", "addr", discoveryAddr, "err", err)
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           withCORS(withLog(mux, log)),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		log.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	log.Info("shutting down")
	shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutCtx)
	log.Info("bye")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func newDevAssetHandler(ctx context.Context, pool *pgxpool.Pool, log interface{ Warn(string, ...any) }) *asset.Handler {
	rdb := redis.NewClient(&redis.Options{
		Addr: envOr("GOMOB_REDIS_ADDR", "127.0.0.1:6379"),
	})
	pingCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := rdb.Ping(pingCtx).Err(); err != nil {
		log.Warn("Redis 不可达，asset etag 缓存禁用", "err", err)
		_ = rdb.Close()
		rdb = nil
	}

	cfg := asset.DefaultConfig()
	cfg.MinIOEndpoint = envOr("GOMOB_MINIO_ENDPOINT", cfg.MinIOEndpoint)
	cfg.MinIOAccessKey = envOr("GOMOB_MINIO_ACCESS_KEY", cfg.MinIOAccessKey)
	cfg.MinIOSecretKey = envOr("GOMOB_MINIO_SECRET_KEY", cfg.MinIOSecretKey)
	cfg.Bucket = envOr("GOMOB_MINIO_BUCKET", cfg.Bucket)
	cfg.MinIOUseSSL = os.Getenv("GOMOB_MINIO_USE_SSL") == "true"

	h, err := asset.NewHandler(cfg, pool, rdb, audit.NewPG(pool))
	if err != nil {
		log.Warn("asset handler 初始化失败，上传接口将返回不可用", "err", err)
		if rdb != nil {
			_ = rdb.Close()
		}
		return nil
	}
	return h
}

// 简易访问日志中间件
func withLog(next http.Handler, log interface{ Info(string, ...any) }) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := &statusRec{ResponseWriter: w, status: 200}
		next.ServeHTTP(ww, r)
		log.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", ww.status,
			"dur_ms", time.Since(start).Milliseconds(),
			"ip", clientIP(r),
		)
	})
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Gomob-Client, X-Gomob-Trace-Id")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type statusRec struct {
	http.ResponseWriter
	status int
}

func (r *statusRec) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRec) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := r.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("response writer 不支持 hijack")
	}
	return h.Hijack()
}

func (r *statusRec) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		if i := strings.IndexByte(v, ','); i > 0 {
			return v[:i]
		}
		return v
	}
	return r.RemoteAddr
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

func parseDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

func parseInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.Atoi(v)
		if err == nil {
			return n
		}
	}
	return def
}

func parseInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil {
			return n
		}
	}
	return def
}

func transcriptConfigFromEnv() repo.TranscriptConfig {
	return repo.TranscriptConfig{
		Engine:   envOr("GOMOB_ASR_ENGINE", "fireredasr2"),
		Model:    envOr("GOMOB_ASR_MODEL", "FireRedASR2-AED"),
		Language: envOr("GOMOB_ASR_LANGUAGE", "zh"),
	}
}

func startASRWorker(
	ctx context.Context,
	pool *pgxpool.Pool,
	notifier asr.TranscriptNotifier,
	trCfg repo.TranscriptConfig,
	log interface {
		Info(string, ...any)
		Error(string, ...any)
	},
) {
	serviceURL := strings.TrimSpace(os.Getenv("GOMOB_ASR_URL"))
	if serviceURL == "" {
		log.Info("语音转写 worker 未启动", "reason", "GOMOB_ASR_URL 未配置")
		return
	}
	worker, err := asr.NewWorker(pool, notifier, asr.Config{
		ServiceURL:     serviceURL,
		Engine:         trCfg.Engine,
		Model:          trCfg.Model,
		Language:       trCfg.Language,
		MinIOEndpoint:  envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey: envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey: envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		MinIOUseSSL:    os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:         envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
		PollInterval:   parseDuration("GOMOB_ASR_POLL_INTERVAL", 2*time.Second),
		RetryAfter:     parseDuration("GOMOB_ASR_RETRY_AFTER", 30*time.Second),
		MaxAttempts:    parseInt("GOMOB_ASR_MAX_ATTEMPTS", 3),
		MaxAudioBytes:  parseInt64("GOMOB_ASR_MAX_AUDIO_BYTES", 50*1024*1024),
	})
	if err != nil {
		log.Error("语音转写 worker 初始化失败", "err", err)
		os.Exit(1)
	}
	go worker.Start(ctx)
	log.Info("语音转写 worker 已启动",
		"url", serviceURL,
		"engine", trCfg.Engine,
		"model", trCfg.Model,
		"language", trCfg.Language,
	)
}

const (
	devSeedUsername = "shenhm"
	devSeedPassword = "shenhm123"
	devSeedRealName = "沈海明"
	devSeedEmployee = "ZAA0120230001"
	devSeedStation  = "杭州市西湖区车管所检测站"
)

func ensureDevSeedLogin(ctx context.Context, pool *pgxpool.Pool) error {
	stationID, err := ensureDevSeedStation(ctx, pool)
	if err != nil {
		return err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(devSeedPassword), 12)
	if err != nil {
		return err
	}

	var userID int64
	err = pool.QueryRow(ctx, `
		SELECT id
		FROM users
		WHERE username=$1 OR employee_id=$2
		ORDER BY CASE WHEN username=$1 THEN 0 ELSE 1 END, id
		LIMIT 1`,
		devSeedUsername, devSeedEmployee,
	).Scan(&userID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = pool.Exec(ctx, `
			INSERT INTO users (username, real_name, employee_id, station_id, password_hash, role, status, note, activated_at)
			VALUES ($1, $2, $3, $4, $5, 'inspector', 'active', 'devserver seed login', now())`,
			devSeedUsername, devSeedRealName, devSeedEmployee, stationID, string(hash),
		)
		if err != nil {
			return err
		}
		if err := ensureDevHelpExperts(ctx, pool, stationID); err != nil {
			return err
		}
		return ensureDevContacts(ctx, pool, stationID)
	}

	_, err = pool.Exec(ctx, `
		UPDATE users
		SET username=$1,
		    real_name=$2,
		    employee_id=$3,
		    station_id=$4,
		    password_hash=$5,
		    role='inspector',
		    status='active',
		    activated_at=COALESCE(activated_at, now())
		WHERE id=$6`,
		devSeedUsername, devSeedRealName, devSeedEmployee, stationID, string(hash), userID,
	)
	if err != nil {
		return err
	}
	if err := ensureDevHelpExperts(ctx, pool, stationID); err != nil {
		return err
	}
	return ensureDevContacts(ctx, pool, stationID)
}

type devHelpExpert struct {
	Username   string
	RealName   string
	EmployeeID string
	Role       string
	Note       string
	Cases      []devHelpExpertCase
}

type devHelpExpertCase struct {
	Title    string
	Summary  string
	Category string
}

var devHelpExperts = []devHelpExpert{
	{
		Username:   "expert_vin",
		RealName:   "陈若愚",
		EmployeeID: "EXP-VIN-0001",
		Role:       "reviewer",
		Note:       "devserver fixed help expert: VIN 拓印专家",
		Cases: []devHelpExpertCase{
			{
				Title:    "新能源 VIN 浅刻复核",
				Summary:  "现场补光后复拍铭牌与拓印图，定位两处浅刻字符误读。",
				Category: "VIN",
			},
			{
				Title:    "铭牌换装疑点会审",
				Summary:  "通过字符间距、铆钉痕迹与历史档案比对确认异常。",
				Category: "合规",
			},
		},
	},
	{
		Username:   "expert_3d",
		RealName:   "林知远",
		EmployeeID: "EXP-3D-0002",
		Role:       "reviewer",
		Note:       "devserver fixed help expert: 三维外廓专家",
		Cases: []devHelpExpertCase{
			{
				Title:    "厢式货车外廓复核",
				Summary:  "结合多视角 RGBD 与人工量测，复核顶棚加装导致的高度偏差。",
				Category: "3D 外廓",
			},
			{
				Title:    "点云空洞质量判定",
				Summary:  "根据遮挡区域和采样轨迹判断需要补扫的关键视角。",
				Category: "点云",
			},
		},
	},
	{
		Username:   "expert_device",
		RealName:   "周一苇",
		EmployeeID: "EXP-DEV-0003",
		Role:       "supervisor",
		Note:       "devserver fixed help expert: 设备链路专家",
		Cases: []devHelpExpertCase{
			{
				Title:    "深度相机掉线排障",
				Summary:  "从 OTG 供电、USB 枚举和帧时间戳三层定位现场链路抖动。",
				Category: "设备",
			},
		},
	},
	{
		Username:   "expert_reg",
		RealName:   "许明庭",
		EmployeeID: "EXP-REG-0004",
		Role:       "supervisor",
		Note:       "devserver fixed help expert: 监管会审专家",
		Cases: []devHelpExpertCase{
			{
				Title:    "跨站异常查验复盘",
				Summary:  "汇总多站同类异常，形成复核口径与会审留痕模板。",
				Category: "监管",
			},
		},
	},
}

type devContact struct {
	Username   string
	RealName   string
	EmployeeID string
	Role       string
	Note       string
}

var devContacts = []devContact{
	{
		Username:   "contact_zhou",
		RealName:   "周科",
		EmployeeID: "ZAA01",
		Role:       "reviewer",
		Note:       "devserver contact: OBD 主审",
	},
	{
		Username:   "contact_wu",
		RealName:   "吴风",
		EmployeeID: "ZAA02",
		Role:       "reviewer",
		Note:       "devserver contact: 外观件专家",
	},
	{
		Username:   "contact_liu",
		RealName:   "刘冶",
		EmployeeID: "ZAA03",
		Role:       "inspector",
		Note:       "devserver contact: VIN 拓印",
	},
	{
		Username:   "contact_jiang",
		RealName:   "江庆宇",
		EmployeeID: "ZAA04",
		Role:       "inspector",
		Note:       "devserver contact: 查验员",
	},
	{
		Username:   "contact_reg_review",
		RealName:   "省所复核",
		EmployeeID: "REG01",
		Role:       "supervisor",
		Note:       "devserver contact: 监管复核",
	},
	{
		Username:   "contact_reg_duty",
		RealName:   "值班督导",
		EmployeeID: "REG02",
		Role:       "supervisor",
		Note:       "devserver contact: 异常督办",
	},
}

func ensureDevHelpExperts(ctx context.Context, pool *pgxpool.Pool, stationID int64) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(devSeedPassword), 12)
	if err != nil {
		return err
	}
	for _, expert := range devHelpExperts {
		var userID int64
		err = pool.QueryRow(ctx, `
			SELECT id
			FROM users
			WHERE username=$1 OR employee_id=$2
			ORDER BY CASE WHEN username=$1 THEN 0 ELSE 1 END, id
			LIMIT 1`,
			expert.Username, expert.EmployeeID,
		).Scan(&userID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if errors.Is(err, pgx.ErrNoRows) {
			err = pool.QueryRow(ctx, `
				INSERT INTO users (username, real_name, employee_id, station_id, password_hash, role, status, note, activated_at)
				VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, now())
				RETURNING id`,
				expert.Username, expert.RealName, expert.EmployeeID, stationID, string(hash), expert.Role, expert.Note,
			).Scan(&userID)
			if err != nil {
				return err
			}
		} else {
			_, err = pool.Exec(ctx, `
				UPDATE users
				SET username=$1,
				    real_name=$2,
				    employee_id=$3,
				    station_id=$4,
				    password_hash=$5,
				    role=$6,
				    status='active',
				    note=$7,
				    activated_at=COALESCE(activated_at, now())
				WHERE id=$8`,
				expert.Username, expert.RealName, expert.EmployeeID, stationID, string(hash), expert.Role, expert.Note, userID,
			)
			if err != nil {
				return err
			}
		}
		if err := ensureDevHelpExpertCases(ctx, pool, userID, expert.Cases); err != nil {
			return err
		}
	}
	return nil
}

func ensureDevContacts(ctx context.Context, pool *pgxpool.Pool, stationID int64) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(devSeedPassword), 12)
	if err != nil {
		return err
	}
	for _, contact := range devContacts {
		var userID int64
		err = pool.QueryRow(ctx, `
			SELECT id
			FROM users
			WHERE username=$1 OR employee_id=$2
			ORDER BY CASE WHEN username=$1 THEN 0 ELSE 1 END, id
			LIMIT 1`,
			contact.Username, contact.EmployeeID,
		).Scan(&userID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if errors.Is(err, pgx.ErrNoRows) {
			_, err = pool.Exec(ctx, `
				INSERT INTO users (username, real_name, employee_id, station_id, password_hash, role, status, note, activated_at)
				VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, now())`,
				contact.Username, contact.RealName, contact.EmployeeID, stationID, string(hash), contact.Role, contact.Note,
			)
			if err != nil {
				return err
			}
		} else {
			_, err = pool.Exec(ctx, `
				UPDATE users
				SET username=$1,
				    real_name=$2,
				    employee_id=$3,
				    station_id=$4,
				    password_hash=$5,
				    role=$6,
				    status='active',
				    note=$7,
				    activated_at=COALESCE(activated_at, now())
				WHERE id=$8`,
				contact.Username, contact.RealName, contact.EmployeeID, stationID, string(hash), contact.Role, contact.Note, userID,
			)
			if err != nil {
				return err
			}
		}
	}
	return nil
}

func ensureDevHelpExpertCases(ctx context.Context, pool *pgxpool.Pool, userID int64, cases []devHelpExpertCase) error {
	for index, item := range cases {
		_, err := pool.Exec(ctx, `
			INSERT INTO help_expert_cases (author_id, title, summary, category, status, published_at)
			SELECT $1, $2, $3, $4, 'published', now() - ($5::int * interval '1 day')
			WHERE NOT EXISTS (
				SELECT 1 FROM help_expert_cases WHERE author_id = $1 AND title = $2
			)`,
			userID, item.Title, item.Summary, item.Category, index,
		)
		if err != nil {
			return err
		}
	}
	return nil
}

func ensureDevSeedStation(ctx context.Context, pool *pgxpool.Pool) (int64, error) {
	var stationID int64
	err := pool.QueryRow(ctx,
		`SELECT id FROM stations WHERE name=$1 ORDER BY id LIMIT 1`,
		devSeedStation,
	).Scan(&stationID)
	if err == nil {
		return stationID, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return 0, err
	}
	err = pool.QueryRow(ctx, `
		INSERT INTO stations (name, region, gateway_addr)
		VALUES ($1, '浙江杭州', '127.0.0.1:18808')
		RETURNING id`,
		devSeedStation,
	).Scan(&stationID)
	return stationID, err
}
