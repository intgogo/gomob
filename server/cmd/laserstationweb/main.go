package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"html/template"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"io.gomob/server/pkg/token"
)

const sessionCookieName = "gomob_laser_station_session"

type webServer struct {
	webDir   string
	secret   []byte
	password string // 高熵登录口令，从环境变量读取，无内置默认
	now      func() time.Time
}

func main() {
	// 默认绑回环：本台直接发 admin 网关 JWT，LAN 可达即冒充，故不暴露到 0.0.0.0；
	// 需要远程访问时显式设 GOMOB_LASER_STATION_ADDR 并置于反代/鉴权之后。
	addr := flag.String("addr", envOr("GOMOB_LASER_STATION_ADDR", "127.0.0.1:5177"), "监听地址（默认绑回环，勿直接暴露公网）")
	webDirArg := flag.String("web-dir", os.Getenv("GOMOB_LASER_STATION_WEB_DIR"), "网页目录")
	flag.Parse()

	webDir, err := resolveWebDir(*webDirArg)
	if err != nil {
		log.Fatal(err)
	}
	secret, err := cookieSecret()
	if err != nil {
		log.Fatal(err)
	}
	password, err := loginPassword()
	if err != nil {
		log.Fatal(err)
	}
	gatewayTarget, err := gatewayTargetURL()
	if err != nil {
		log.Fatal(err)
	}

	s := &webServer{webDir: webDir, secret: secret, password: password, now: time.Now}
	mux := http.NewServeMux()
	mux.HandleFunc("/login", s.login)
	mux.HandleFunc("/logout", s.logout)
	mux.HandleFunc("/station/session", s.stationSession)
	mux.Handle("/gateway/", s.requireLogin(gatewayProxy(gatewayTarget)))
	mux.Handle("/", s.requireLogin(http.FileServer(http.Dir(webDir))))

	log.Printf("3D 扫描工位管理台已启动 addr=%s web_dir=%s gateway=%s", *addr, webDir, gatewayTarget.String())
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func resolveWebDir(arg string) (string, error) {
	candidates := []string{}
	if arg != "" {
		candidates = append(candidates, arg)
	}
	candidates = append(candidates,
		"web/laser-station",
		"../web/laser-station",
		"../../web/laser-station",
	)
	for _, p := range candidates {
		abs, err := filepath.Abs(p)
		if err != nil {
			continue
		}
		if st, err := os.Stat(filepath.Join(abs, "index.html")); err == nil && !st.IsDir() {
			return abs, nil
		}
	}
	return "", fmt.Errorf("找不到 web/laser-station/index.html，请设置 GOMOB_LASER_STATION_WEB_DIR")
}

func cookieSecret() ([]byte, error) {
	if raw := os.Getenv("GOMOB_LASER_STATION_COOKIE_SECRET"); raw != "" {
		return []byte(raw), nil
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, fmt.Errorf("生成 cookie secret 失败: %w", err)
	}
	return secret, nil
}

func (s *webServer) login(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.renderLogin(w, "")
	case http.MethodPost:
		if err := r.ParseForm(); err != nil {
			s.renderLogin(w, "表单解析失败")
			return
		}
		if !s.passwordOK(r.FormValue("password")) {
			s.renderLogin(w, "口令不正确")
			return
		}
		s.setSession(w)
		http.Redirect(w, r, "/", http.StatusSeeOther)
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *webServer) logout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (s *webServer) stationSession(w http.ResponseWriter, r *http.Request) {
	if !s.validSession(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID := int64FromEnv("GOMOB_LASER_STATION_USER_ID", 1)
	role := envOr("GOMOB_LASER_STATION_ROLE", "admin")
	access, err := token.IssueAccess(userID, role)
	if err != nil {
		http.Error(w, "issue token failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"access_token": access,
		"user_id":      userID,
		"role":         role,
		"gateway":      gatewayURL(r),
	})
}

func (s *webServer) requireLogin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.validSession(r) {
			next.ServeHTTP(w, r)
			return
		}
		http.Redirect(w, r, "/login", http.StatusSeeOther)
	})
}

func (s *webServer) setSession(w http.ResponseWriter) {
	today := s.todayKey()
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    today + "." + s.sign(today),
		Path:     "/",
		Expires:  nextLocalDay(s.now()),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

func (s *webServer) validSession(r *http.Request) bool {
	c, err := r.Cookie(sessionCookieName)
	if err != nil {
		return false
	}
	date, sig, ok := strings.Cut(c.Value, ".")
	if !ok || date != s.todayKey() {
		return false
	}
	want := s.sign(date)
	return subtle.ConstantTimeCompare([]byte(sig), []byte(want)) == 1
}

func (s *webServer) todayKey() string {
	return s.now().Format("20060102")
}

func (s *webServer) sign(value string) string {
	mac := hmac.New(sha256.New, s.secret)
	_, _ = mac.Write([]byte(value))
	return hex.EncodeToString(mac.Sum(nil))
}

// loginPassword 从环境变量读高熵登录口令；未设置或过弱则拒绝启动，绝不内置可推算的弱默认。
func loginPassword() (string, error) {
	pw := os.Getenv("GOMOB_LASER_STATION_PASSWORD")
	if pw == "" {
		return "", fmt.Errorf("必须设置 GOMOB_LASER_STATION_PASSWORD（高熵口令）；本台登录即发 admin 网关 JWT，禁止内置弱默认。生成示例：openssl rand -base64 24")
	}
	if len([]rune(pw)) < 16 {
		return "", fmt.Errorf("GOMOB_LASER_STATION_PASSWORD 过弱（需 ≥16 字符）；生成示例：openssl rand -base64 24")
	}
	return pw, nil
}

func (s *webServer) passwordOK(input string) bool {
	if s.password == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(input), []byte(s.password)) == 1
}

func nextLocalDay(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d+1, 0, 0, 0, 0, t.Location())
}

func (s *webServer) renderLogin(w http.ResponseWriter, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	data := struct {
		Message string
	}{
		Message: message,
	}
	if err := loginPage.Execute(w, data); err != nil {
		log.Printf("渲染登录页失败: %v", err)
	}
}

func int64FromEnv(key string, fallback int64) int64 {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || v <= 0 {
		return fallback
	}
	return v
}

func gatewayURL(r *http.Request) string {
	if v := os.Getenv("GOMOB_LASER_STATION_PUBLIC_GATEWAY"); v != "" {
		return strings.TrimRight(v, "/")
	}
	return requestBaseURL(r) + "/gateway"
}

func gatewayTargetURL() (*url.URL, error) {
	raw := envOr("GOMOB_LASER_STATION_GATEWAY", "http://127.0.0.1:18808")
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, fmt.Errorf("GOMOB_LASER_STATION_GATEWAY 非法: %q", raw)
	}
	return u, nil
}

func requestBaseURL(r *http.Request) string {
	scheme := "http"
	if proto := r.Header.Get("X-Forwarded-Proto"); proto == "https" || proto == "http" {
		scheme = proto
	}
	host := r.Header.Get("X-Forwarded-Host")
	if host == "" {
		host = r.Host
	}
	if host == "" {
		host = "127.0.0.1"
	}
	return scheme + "://" + host
}

func gatewayProxy(target *url.URL) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Director = func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.URL.Path = joinURLPath(target.Path, strings.TrimPrefix(req.URL.Path, "/gateway"))
		if target.RawQuery == "" || req.URL.RawQuery == "" {
			req.URL.RawQuery = target.RawQuery + req.URL.RawQuery
		} else {
			req.URL.RawQuery = target.RawQuery + "&" + req.URL.RawQuery
		}
		req.Host = target.Host
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		http.Error(w, "gateway 不可达: "+err.Error(), http.StatusBadGateway)
	}
	return proxy
}

func joinURLPath(base, incoming string) string {
	if incoming == "" {
		incoming = "/"
	}
	if base == "" || base == "/" {
		return incoming
	}
	baseSlash := strings.HasSuffix(base, "/")
	inSlash := strings.HasPrefix(incoming, "/")
	switch {
	case baseSlash && inSlash:
		return base + incoming[1:]
	case !baseSlash && !inSlash:
		return base + "/" + incoming
	default:
		return base + incoming
	}
}

var loginPage = template.Must(template.New("login").Parse(`<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>3D 扫描工位登录</title>
    <style>
      :root { color-scheme: light; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif; }
      * { box-sizing: border-box; }
      body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f5f6f4; color: #1c2424; }
      main { width: min(420px, calc(100vw - 32px)); padding: 28px; border: 1px solid #d5dbd8; border-radius: 8px; background: #fff; box-shadow: 0 18px 60px rgba(20, 32, 30, 0.12); }
      h1 { margin: 0; font-size: 22px; line-height: 1.25; }
      p { margin: 8px 0 22px; color: #64706f; font-size: 13px; line-height: 1.6; }
      label { display: grid; gap: 8px; color: #64706f; font-size: 13px; }
      input { width: 100%; border: 1px solid #d5dbd8; border-radius: 6px; padding: 11px 12px; font: inherit; color: #1c2424; }
      button { width: 100%; margin-top: 14px; border: 1px solid #0f8c86; border-radius: 6px; padding: 11px 12px; background: #0f8c86; color: #fff; font: inherit; cursor: pointer; }
      .message { margin: 0 0 14px; padding: 9px 10px; border-radius: 6px; background: #f6d8da; color: #842029; }
    </style>
  </head>
  <body>
    <main>
      <h1>3D 扫描工位</h1>
      <p>请输入管理口令进入管理台。</p>
      {{if .Message}}<div class="message">{{.Message}}</div>{{end}}
      <form method="post" action="/login">
        <label>
          管理口令
          <input name="password" type="password" autocomplete="current-password" autofocus>
        </label>
        <button type="submit">登录</button>
      </form>
    </main>
  </body>
</html>`))
