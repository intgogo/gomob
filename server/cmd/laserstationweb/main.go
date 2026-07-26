package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
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
	"regexp"
	"strconv"
	"strings"
	"time"

	"io.gomob/server/pkg/token"
)

const (
	sessionCookieName  = "gomob_laser_station_session"
	defaultSessionDays = 30
	maxSessionDays     = 90
	maxFeedbackBody    = 24 << 20
	maxFeedbackImage   = 12 << 20
)

type webServer struct {
	webDir      string
	feedbackDir string
	secret      []byte
	password    string // 高熵登录口令，从环境变量读取，无内置默认
	sessionTTL  time.Duration
	now         func() time.Time
}

type stationFeedbackReq struct {
	Title            string               `json:"title"`
	Severity         string               `json:"severity"`
	Category         string               `json:"category"`
	PageURL          string               `json:"pageUrl"`
	UserAgent        string               `json:"userAgent"`
	ImageDataURL     string               `json:"imageDataUrl"`
	AnnotatedDataURL string               `json:"annotatedDataUrl"`
	Boxes            []stationFeedbackBox `json:"boxes"`
}

type stationFeedbackBox struct {
	X    float64 `json:"x"`
	Y    float64 `json:"y"`
	W    float64 `json:"w"`
	H    float64 `json:"h"`
	Note string  `json:"note"`
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
	feedbackDir, err := resolveFeedbackDir(webDir)
	if err != nil {
		log.Fatal(err)
	}
	password, err := loginPassword()
	if err != nil {
		log.Fatal(err)
	}
	secret, err := cookieSecret(password)
	if err != nil {
		log.Fatal(err)
	}
	sessionTTL, err := sessionTTL()
	if err != nil {
		log.Fatal(err)
	}
	gatewayTarget, err := gatewayTargetURL()
	if err != nil {
		log.Fatal(err)
	}

	s := &webServer{
		webDir:      webDir,
		feedbackDir: feedbackDir,
		secret:      secret,
		password:    password,
		sessionTTL:  sessionTTL,
		now:         time.Now,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/login", s.login)
	mux.HandleFunc("/logout", s.logout)
	mux.HandleFunc("/station/session", s.stationSession)
	mux.HandleFunc("/station/feedback", s.submitFeedback)
	mux.Handle("/gateway/", s.requireLogin(gatewayProxy(gatewayTarget)))
	mux.Handle("/", s.requireLogin(http.FileServer(http.Dir(webDir))))

	log.Printf("3D 扫描工位管理台已启动 addr=%s web_dir=%s gateway=%s feedback_dir=%s", *addr, webDir, gatewayTarget.String(), feedbackDir)
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

func resolveFeedbackDir(webDir string) (string, error) {
	if raw := os.Getenv("GOMOB_LASER_STATION_FEEDBACK_DIR"); raw != "" {
		return filepath.Abs(raw)
	}
	root := filepath.Clean(filepath.Join(webDir, "..", ".."))
	return filepath.Join(root, ".dev", "laser-station-feedback"), nil
}

func cookieSecret(password string) ([]byte, error) {
	if raw := os.Getenv("GOMOB_LASER_STATION_COOKIE_SECRET"); raw != "" {
		return []byte(raw), nil
	}
	sum := sha256.Sum256([]byte("gomob-laser-station-cookie:" + password))
	return sum[:], nil
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

func (s *webServer) submitFeedback(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.validSession(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxFeedbackBody)
	defer r.Body.Close()

	var req stationFeedbackReq
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&req); err != nil {
		http.Error(w, "反馈 JSON 解析失败: "+err.Error(), http.StatusBadRequest)
		return
	}
	cleaned, err := sanitizeFeedback(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	shot, err := decodePNGDataURL(cleaned.ImageDataURL, maxFeedbackImage)
	if err != nil {
		http.Error(w, "截图无效: "+err.Error(), http.StatusBadRequest)
		return
	}
	annotated := shot
	if cleaned.AnnotatedDataURL != "" {
		annotated, err = decodePNGDataURL(cleaned.AnnotatedDataURL, maxFeedbackImage)
		if err != nil {
			http.Error(w, "标注截图无效: "+err.Error(), http.StatusBadRequest)
			return
		}
	}

	id := time.Now().Format("20060102-150405.000000000")
	dir := filepath.Join(s.feedbackDir, id+"_"+safeFilePart(cleaned.Title))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		http.Error(w, "创建反馈目录失败: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "screenshot.png"), shot, 0o644); err != nil {
		http.Error(w, "保存截图失败: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "annotated.png"), annotated, 0o644); err != nil {
		http.Error(w, "保存标注截图失败: "+err.Error(), http.StatusInternalServerError)
		return
	}

	report := map[string]any{
		"id":          id,
		"created_at":  time.Now().Format(time.RFC3339),
		"title":       cleaned.Title,
		"severity":    cleaned.Severity,
		"category":    cleaned.Category,
		"page_url":    cleaned.PageURL,
		"user_agent":  cleaned.UserAgent,
		"remote_addr": r.RemoteAddr,
		"boxes":       cleaned.Boxes,
	}
	raw, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		http.Error(w, "生成反馈 JSON 失败: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "report.json"), raw, 0o644); err != nil {
		http.Error(w, "保存反馈 JSON 失败: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "report.md"), []byte(feedbackMarkdown(id, cleaned)), 0o644); err != nil {
		http.Error(w, "保存反馈摘要失败: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "id": id, "dir": dir})
}

func sanitizeFeedback(req stationFeedbackReq) (stationFeedbackReq, error) {
	req.Title = strings.TrimSpace(req.Title)
	if req.Title == "" {
		return req, fmt.Errorf("反馈标题不能为空")
	}
	if len([]rune(req.Title)) > 120 {
		return req, fmt.Errorf("反馈标题过长")
	}
	req.Severity = enumOr(req.Severity, "medium", "high", "medium", "low")
	req.Category = enumOr(req.Category, "function", "function", "data", "ui", "perf", "other")
	req.PageURL = trimRunes(req.PageURL, 600)
	req.UserAgent = trimRunes(req.UserAgent, 600)
	if req.ImageDataURL == "" {
		return req, fmt.Errorf("缺少截图")
	}
	if len(req.Boxes) == 0 {
		return req, fmt.Errorf("请至少标注一个问题区域")
	}
	if len(req.Boxes) > 50 {
		return req, fmt.Errorf("标注数量过多")
	}
	for i := range req.Boxes {
		b := &req.Boxes[i]
		if b.X < 0 || b.Y < 0 || b.W <= 0 || b.H <= 0 || b.X+b.W > 1.01 || b.Y+b.H > 1.01 {
			return req, fmt.Errorf("第 %d 个标注框坐标无效", i+1)
		}
		b.X = clamp01(b.X)
		b.Y = clamp01(b.Y)
		b.W = clamp01(b.W)
		b.H = clamp01(b.H)
		b.Note = trimRunes(strings.TrimSpace(b.Note), 500)
	}
	return req, nil
}

func decodePNGDataURL(dataURL string, maxBytes int) ([]byte, error) {
	const prefix = "data:image/png;base64,"
	if !strings.HasPrefix(dataURL, prefix) {
		return nil, fmt.Errorf("只接受 PNG data URL")
	}
	encoded := dataURL[len(prefix):]
	if len(encoded) > maxBytes*4/3+1024 {
		return nil, fmt.Errorf("图片超过大小限制")
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	if len(raw) > maxBytes {
		return nil, fmt.Errorf("图片超过大小限制")
	}
	if len(raw) < 8 || string(raw[:8]) != "\x89PNG\r\n\x1a\n" {
		return nil, fmt.Errorf("不是有效 PNG")
	}
	return raw, nil
}

func feedbackMarkdown(id string, req stationFeedbackReq) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# %s\n\n", req.Title)
	fmt.Fprintf(&b, "- id: `%s`\n", id)
	fmt.Fprintf(&b, "- severity: `%s`\n", req.Severity)
	fmt.Fprintf(&b, "- category: `%s`\n", req.Category)
	fmt.Fprintf(&b, "- page: `%s`\n", req.PageURL)
	fmt.Fprintf(&b, "- user_agent: `%s`\n\n", req.UserAgent)
	b.WriteString("## 标注\n\n")
	for i, box := range req.Boxes {
		fmt.Fprintf(&b, "%d. x=%.4f y=%.4f w=%.4f h=%.4f\n\n   %s\n", i+1, box.X, box.Y, box.W, box.H, box.Note)
	}
	return b.String()
}

func enumOr(v string, def string, allowed ...string) string {
	v = strings.TrimSpace(v)
	for _, item := range allowed {
		if v == item {
			return v
		}
	}
	return def
}

func trimRunes(v string, max int) string {
	r := []rune(strings.TrimSpace(v))
	if len(r) <= max {
		return string(r)
	}
	return string(r[:max])
}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

var unsafeFileChars = regexp.MustCompile(`[^a-zA-Z0-9._\-\p{Han}]+`)

func safeFilePart(raw string) string {
	s := unsafeFileChars.ReplaceAllString(strings.TrimSpace(raw), "_")
	s = strings.Trim(s, "._-")
	if len([]rune(s)) > 48 {
		s = string([]rune(s)[:48])
	}
	if s == "" {
		return "feedback"
	}
	return s
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
	expires := s.now().Add(s.sessionTTL)
	expiresUnix := strconv.FormatInt(expires.Unix(), 10)
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    expiresUnix + "." + s.sign(expiresUnix),
		Path:     "/",
		Expires:  expires,
		MaxAge:   int(s.sessionTTL.Seconds()),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

func (s *webServer) validSession(r *http.Request) bool {
	c, err := r.Cookie(sessionCookieName)
	if err != nil {
		return false
	}
	expiresUnix, sig, ok := strings.Cut(c.Value, ".")
	if !ok {
		return false
	}
	expires, err := strconv.ParseInt(expiresUnix, 10, 64)
	if err != nil || !s.now().Before(time.Unix(expires, 0)) {
		return false
	}
	want := s.sign(expiresUnix)
	return subtle.ConstantTimeCompare([]byte(sig), []byte(want)) == 1
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
	if input == "" || s.password == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(input), []byte(s.password)) == 1
}

func sessionTTL() (time.Duration, error) {
	raw := os.Getenv("GOMOB_LASER_STATION_SESSION_DAYS")
	if raw == "" {
		return time.Duration(defaultSessionDays) * 24 * time.Hour, nil
	}
	days, err := strconv.Atoi(raw)
	if err != nil || days < 1 || days > maxSessionDays {
		return 0, fmt.Errorf("GOMOB_LASER_STATION_SESSION_DAYS 必须在 1-%d 天之间", maxSessionDays)
	}
	return time.Duration(days) * 24 * time.Hour, nil
}

func (s *webServer) renderLogin(w http.ResponseWriter, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	data := struct {
		Message     string
		SessionDays int
	}{
		Message:     message,
		SessionDays: int(s.sessionTTL.Hours() / 24),
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
    <meta name="theme-color" content="#f6f7f9">
    <title>3D 扫描工位登录</title>
    <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230e8a75'/%3E%3Cpath d='M7 10l9-5 9 5v12l-9 5-9-5zM7 10l9 5 9-5M16 15v12' fill='none' stroke='white' stroke-width='2' stroke-linejoin='round'/%3E%3C/svg%3E">
    <style>
      :root {
        color-scheme: light;
        --bg0: #f6f7f9;
        --fg0: rgba(11, 15, 22, .96);
        --fg1: rgba(42, 50, 63, .78);
        --fg2: rgba(61, 70, 84, .88);
        --fg3: rgba(73, 83, 99, .84);
        --line: rgba(11, 15, 22, .08);
        --accent: #0e8a75;
        --accent-strong: #0a6e5c;
        font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
      }
      * { box-sizing: border-box; }
      body {
        position: relative;
        min-height: 100vh;
        margin: 0;
        padding: 16px 0;
        display: grid;
        place-items: center;
        overflow-x: hidden;
        overflow-y: auto;
        background:
          radial-gradient(circle at 12% 8%, rgba(14, 138, 117, .14), transparent 34%),
          radial-gradient(circle at 88% 90%, rgba(10, 110, 92, .07), transparent 38%),
          linear-gradient(135deg, #f8fafb, #f2f5f6 54%, #f7f8fa);
        color: var(--fg0);
      }
      body::before {
        position: fixed;
        inset: 24px;
        border: 1px solid rgba(11, 15, 22, .04);
        border-radius: 12px;
        content: "";
        pointer-events: none;
      }
      main {
        width: min(430px, calc(100vw - 32px));
        padding: 24px;
        border: 1px solid rgba(11, 15, 22, .07);
        border-radius: 8px;
        background: rgba(255, 255, 255, .76);
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, .86), 0 24px 70px rgba(20, 32, 42, .14);
        -webkit-backdrop-filter: blur(20px) saturate(1.1);
        backdrop-filter: blur(20px) saturate(1.1);
      }
      .brand { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
      .brand-icon {
        display: grid;
        place-items: center;
        width: 44px;
        height: 44px;
        flex: 0 0 auto;
        border: 1px solid rgba(14, 138, 117, .28);
        border-radius: 8px;
        background: rgba(14, 138, 117, .1);
        color: var(--accent);
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, .82);
      }
      .brand-icon svg { width: 24px; height: 24px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
      .brand-copy span { display: block; margin-bottom: 2px; color: var(--fg3); font-size: 10px; font-weight: 700; letter-spacing: .13em; }
      h1 { margin: 0; color: var(--fg0); font-size: 21px; font-weight: 650; line-height: 1.2; letter-spacing: -.02em; }
      p { margin: 0 0 20px; color: var(--fg2); font-size: 13px; line-height: 1.65; }
      label { display: grid; gap: 7px; color: var(--fg2); font-size: 12px; font-weight: 550; }
      input {
        width: 100%;
        min-height: 44px;
        border: 1px solid var(--line);
        border-radius: 6px;
        padding: 10px 12px;
        outline: none;
        background: rgba(255, 255, 255, .72);
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, .8);
        color: var(--fg0);
        font: inherit;
      }
      input:focus { border-color: rgba(14, 138, 117, .38); box-shadow: 0 0 0 3px rgba(14, 138, 117, .09); }
      button {
        width: 100%;
        min-height: 44px;
        margin-top: 14px;
        border: 1px solid var(--accent);
        border-radius: 6px;
        padding: 10px 12px;
        background: linear-gradient(180deg, #15977f, var(--accent));
        box-shadow: inset 0 1px 0 rgba(255, 255, 255, .22), 0 7px 18px rgba(14, 138, 117, .18);
        color: #fff;
        font: inherit;
        font-size: 14px;
        font-weight: 600;
        cursor: pointer;
      }
      button:hover { background: linear-gradient(180deg, var(--accent), var(--accent-strong)); }
      .message { margin: 0 0 14px; padding: 9px 10px; border: 1px solid rgba(176, 64, 48, .18); border-radius: 6px; background: rgba(176, 64, 48, .1); color: #b04030; font-size: 12px; }
      .security-note { display: flex; align-items: center; gap: 7px; margin-top: 14px; color: var(--fg3); font-size: 10px; }
      .security-note::before { width: 6px; height: 6px; border-radius: 50%; background: #2c7a6a; box-shadow: 0 0 0 3px rgba(44, 122, 106, .1); content: ""; }
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) { main { background: rgba(255, 255, 255, .96); } }
      @media (max-width: 520px) { body::before { inset: 8px; } main { padding: 20px; } }
    </style>
  </head>
  <body>
    <main>
      <div class="brand">
        <span class="brand-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4.5 7.25 12 3l7.5 4.25v9.5L12 21l-7.5-4.25z"></path><path d="m4.8 7.4 7.2 4.1 7.2-4.1M12 11.5V21"></path></svg>
        </span>
        <div class="brand-copy"><span>车辆外廓测量</span><h1>3D 扫描工位</h1></div>
      </div>
      <p>请输入部署时配置的高熵管理口令；本浏览器会记住 {{.SessionDays}} 天。</p>
      {{if .Message}}<div class="message">{{.Message}}</div>{{end}}
      <form method="post" action="/login">
        <label>
          管理口令
          <input name="password" type="password" autocomplete="current-password" autofocus>
        </label>
        <button type="submit">登录</button>
      </form>
      <div class="security-note">登录态仅保存在当前浏览器</div>
    </main>
  </body>
</html>`))
