// gomob-devserver — 开发模式合体进程。
//
// 环境变量：
//
//	GOMOB_LISTEN           HTTP 监听地址（默认 :18808）
//	GOMOB_DISCOVERY_ADDR   UDP 服务发现监听地址（默认 :18809；空字符串禁用）
//	GOMOB_DISCOVERY_NAME   服务发现展示名称（默认 gomob-devserver）
//
// 当前装载 auth + me 路由（接 PostgreSQL）。后续 api / asset / signaling / worker
// 各自实现成熟后再合并；保持 devserver 永远是"全部已实现路由"的并集。
package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"golang.org/x/crypto/bcrypt"

	"io.gomob/server/internal/api"
	"io.gomob/server/internal/asset"
	"io.gomob/server/internal/auth"
	"io.gomob/server/internal/gateway"
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
	apiH := api.NewHandler(pool, audit.NewPG(pool), rbac.Baseline())
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
		return ensureDevHelpExperts(ctx, pool, stationID)
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
	return ensureDevHelpExperts(ctx, pool, stationID)
}

type devHelpExpert struct {
	Username   string
	RealName   string
	EmployeeID string
	Role       string
	Note       string
}

var devHelpExperts = []devHelpExpert{
	{
		Username:   "expert_vin",
		RealName:   "陈若愚",
		EmployeeID: "EXP-VIN-0001",
		Role:       "reviewer",
		Note:       "devserver fixed help expert: VIN 拓印专家",
	},
	{
		Username:   "expert_3d",
		RealName:   "林知远",
		EmployeeID: "EXP-3D-0002",
		Role:       "reviewer",
		Note:       "devserver fixed help expert: 三维外廓专家",
	},
	{
		Username:   "expert_device",
		RealName:   "周一苇",
		EmployeeID: "EXP-DEV-0003",
		Role:       "supervisor",
		Note:       "devserver fixed help expert: 设备链路专家",
	},
	{
		Username:   "expert_reg",
		RealName:   "许明庭",
		EmployeeID: "EXP-REG-0004",
		Role:       "supervisor",
		Note:       "devserver fixed help expert: 监管会审专家",
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
			_, err = pool.Exec(ctx, `
				INSERT INTO users (username, real_name, employee_id, station_id, password_hash, role, status, note, activated_at)
				VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, now())`,
				expert.Username, expert.RealName, expert.EmployeeID, stationID, string(hash), expert.Role, expert.Note,
			)
			if err != nil {
				return err
			}
			continue
		}
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
