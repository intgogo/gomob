// Package hmacauth —— 内部服务间 HMAC-SHA256 验签（M-S10.2c）。
//
// 解决的问题：cv-engine / vinref / shaperef 等内网服务暴露在 :18810 / :18058 等内部
// 端口，正确的入口是 gateway → 反代过来；但只要攻击者拿到内网访问，就能跳过 gateway
// 直接调内网服务，绕开 JWT + RBAC + 限流。HMAC 中间件给所有内部 RPC 加一道"必须由
// gateway / 受信内部服务"才算数的硬验证：每个调用必须带正确签名 + timestamp + nonce，
// 缺一不可。
//
// 签名串：
//
//	ts + "\n" + method + "\n" + request_uri + "\n" + sha256_hex(body)
//
// 三个头（缺一即拒）：
//
//	X-Gomob-Hmac-Ts        Unix epoch 秒（10 位整数）
//	X-Gomob-Hmac-Nonce     随机串（16+ 字符；同 secret 5min 内 SETNX 去重）
//	X-Gomob-Hmac-Sig       hex(hmac_sha256(secret, signing_string))
//
// 错误码：
//
//	40110  缺签名头
//	40111  ts 过期 / 不可解析（误差 > 5min）
//	40112  nonce 重放（5min 滑动窗口内已见）
//	40113  签名不匹配
//
// 启用条件：caller 提供非空 secret；secret 为空则中间件 noop（dev 默认）。
//
// 客户端：调用 NewSigningTransport(base, secret) 得到一个 http.RoundTripper，
// 它会在每个 Outbound Request 上自动注入三头。无需调用方手工签。
package hmacauth

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"io.gomob/server/pkg/httpx"
)

// HMAC 错误码（避免与 httpx 已编码冲突，从 40110 起步）。
var (
	ErrMissingHeaders   = httpx.NewError(40110, http.StatusUnauthorized, "缺 HMAC 签名头")
	ErrTimestampExpired = httpx.NewError(40111, http.StatusUnauthorized, "HMAC 时间戳过期或非法")
	ErrNonceReplay      = httpx.NewError(40112, http.StatusUnauthorized, "HMAC nonce 重放")
	ErrSigMismatch      = httpx.NewError(40113, http.StatusUnauthorized, "HMAC 签名不匹配")
)

const (
	HeaderTs    = "X-Gomob-Hmac-Ts"
	HeaderNonce = "X-Gomob-Hmac-Nonce"
	HeaderSig   = "X-Gomob-Hmac-Sig"

	// MaxClockSkew ts 与 server now 最大允许偏差。超过即拒（防重放）。
	MaxClockSkew = 5 * time.Minute
	// MaxBodyForSig sign body 时最多读取的字节（防巨包恶意拖时间）。
	MaxBodyForSig = 64 << 20 // 64MB
)

// NonceStore 防重放：5min 滑动窗口内同 nonce 出现过即返 false。
//
// 默认 InMemoryNonceStore 适合单进程；多副本部署需用 Redis 实现（同 GOMOB_REDIS_ADDR）。
type NonceStore interface {
	// SeenAndRemember 看到 nonce → 返 true（重放），否则记录 + 返 false。
	// ttl 至少 ≥ MaxClockSkew，避免窗口外漏放行。
	SeenAndRemember(ctx context.Context, nonce string, ttl time.Duration) (bool, error)
}

// InMemoryNonceStore 单进程版（map + 定时清理）。
type InMemoryNonceStore struct {
	mu     sync.Mutex
	seen   map[string]time.Time
	stopCh chan struct{}
}

// NewInMemoryNonceStore 默认 5min 清理周期。
func NewInMemoryNonceStore() *InMemoryNonceStore {
	s := &InMemoryNonceStore{seen: make(map[string]time.Time), stopCh: make(chan struct{})}
	go s.loop()
	return s
}

func (s *InMemoryNonceStore) loop() {
	t := time.NewTicker(2 * MaxClockSkew)
	defer t.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-t.C:
			now := time.Now()
			s.mu.Lock()
			for k, exp := range s.seen {
				if now.After(exp) {
					delete(s.seen, k)
				}
			}
			s.mu.Unlock()
		}
	}
}

// Close 停后台清理 goroutine。
func (s *InMemoryNonceStore) Close() { close(s.stopCh) }

func (s *InMemoryNonceStore) SeenAndRemember(_ context.Context, nonce string, ttl time.Duration) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	if exp, ok := s.seen[nonce]; ok && exp.After(now) {
		return true, nil
	}
	s.seen[nonce] = now.Add(ttl)
	return false, nil
}

// Verifier 校验入站 HMAC 头。secret 为空时 Disabled=true，所有请求放行。
type Verifier struct {
	secret   []byte
	store    NonceStore
	required bool // true: 缺签名头直接拒；false: 缺签名头放行（兼容现有 dev 调用）
}

// NewVerifier 创建。secret="" 时 Disabled。required=true 强制每个请求都带签名。
func NewVerifier(secret string, required bool, store NonceStore) *Verifier {
	if store == nil {
		store = NewInMemoryNonceStore()
	}
	return &Verifier{
		secret:   []byte(secret),
		store:    store,
		required: required,
	}
}

// Disabled secret 为空 → 中间件 noop。
func (v *Verifier) Disabled() bool { return len(v.secret) == 0 }

// Middleware 包装一个 handler。
//
// 行为：
//   - Disabled (secret 空) → 直放
//   - 缺签名头 + required=false → 放行（兼容 dev）
//   - 缺签名头 + required=true → 40110
//   - ts 缺失 / 过期 → 40111
//   - nonce 重放 → 40112
//   - 签名不匹配 → 40113
//   - 全过 → next.ServeHTTP
//
// body 校验时会读取并替换 r.Body（用 bytes.NewReader 重新包装），handler 仍能正常 ParseMultipart。
func (v *Verifier) Middleware(next http.Handler) http.Handler {
	if v.Disabled() {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ts := r.Header.Get(HeaderTs)
		nonce := r.Header.Get(HeaderNonce)
		sig := r.Header.Get(HeaderSig)

		if ts == "" && nonce == "" && sig == "" {
			if v.required {
				httpx.WriteError(w, ErrMissingHeaders)
				return
			}
			next.ServeHTTP(w, r)
			return
		}
		if ts == "" || nonce == "" || sig == "" {
			httpx.WriteError(w, ErrMissingHeaders)
			return
		}

		// 1. ts 校验
		tsInt, err := strconv.ParseInt(ts, 10, 64)
		if err != nil {
			httpx.WriteError(w, ErrTimestampExpired)
			return
		}
		now := time.Now().Unix()
		if absInt64(now-tsInt) > int64(MaxClockSkew/time.Second) {
			httpx.WriteError(w, ErrTimestampExpired)
			return
		}

		// 2. body 读取（替换原 Body 让 handler 仍可读）
		var bodyBytes []byte
		if r.Body != nil {
			bb, rerr := io.ReadAll(io.LimitReader(r.Body, MaxBodyForSig))
			if rerr != nil {
				httpx.WriteError(w, ErrSigMismatch)
				return
			}
			bodyBytes = bb
			r.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		}

		// 3. 重算签名
		expectedSig := computeSig(v.secret, ts, r.Method, r.URL.RequestURI(), bodyBytes)
		// 不可用 ==（非常量时间）— 用 hmac.Equal
		if !hmac.Equal([]byte(expectedSig), []byte(sig)) {
			httpx.WriteError(w, ErrSigMismatch)
			return
		}

		// 4. nonce 反重放
		seen, serr := v.store.SeenAndRemember(r.Context(), nonce, MaxClockSkew*2)
		if serr != nil {
			// store 故障保守拒
			httpx.WriteError(w, ErrSigMismatch)
			return
		}
		if seen {
			httpx.WriteError(w, ErrNonceReplay)
			return
		}

		next.ServeHTTP(w, r)
	})
}

// computeSig 计算 HMAC-SHA256 签名串（hex 输出）。
//
//	signing_string = ts + "\n" + method + "\n" + request_uri + "\n" + sha256_hex(body)
func computeSig(secret []byte, ts, method, requestURI string, body []byte) string {
	bodyHash := sha256.Sum256(body)
	bodyHashHex := hex.EncodeToString(bodyHash[:])

	mac := hmac.New(sha256.New, secret)
	_, _ = io.WriteString(mac, ts)
	_, _ = mac.Write([]byte{'\n'})
	_, _ = io.WriteString(mac, method)
	_, _ = mac.Write([]byte{'\n'})
	_, _ = io.WriteString(mac, requestURI)
	_, _ = mac.Write([]byte{'\n'})
	_, _ = io.WriteString(mac, bodyHashHex)
	return hex.EncodeToString(mac.Sum(nil))
}

func absInt64(x int64) int64 {
	if x < 0 {
		return -x
	}
	return x
}

// SigningTransport 客户端：自动给每个 Outbound Request 注入 HMAC 三头。
//
// 用法：
//
//	hc := &http.Client{Transport: hmacauth.NewSigningTransport(http.DefaultTransport, os.Getenv("GOMOB_HMAC_SECRET"))}
//	hc.Get("http://cvengine:18810/cv/v1/version")
//
// secret 为空时返回 base 不动（noop）。
func NewSigningTransport(base http.RoundTripper, secret string) http.RoundTripper {
	if base == nil {
		base = http.DefaultTransport
	}
	if secret == "" {
		return base
	}
	return &signingTransport{base: base, secret: []byte(secret)}
}

type signingTransport struct {
	base   http.RoundTripper
	secret []byte
}

func (t *signingTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	// 注：req 的 Body 必须可重放（ReadCloser 单读）— 我们读完后用 bytes.Reader 替换。
	var body []byte
	if r.Body != nil {
		bb, err := io.ReadAll(io.LimitReader(r.Body, MaxBodyForSig))
		if err != nil {
			return nil, fmt.Errorf("hmacauth: 读 body: %w", err)
		}
		_ = r.Body.Close()
		body = bb
		r.Body = io.NopCloser(bytes.NewReader(body))
		// 设 GetBody 让 net/http 在 redirect / retry 时还能再读
		buf := body
		r.GetBody = func() (io.ReadCloser, error) {
			return io.NopCloser(bytes.NewReader(buf)), nil
		}
		r.ContentLength = int64(len(body))
	}

	ts := strconv.FormatInt(time.Now().Unix(), 10)
	nonce, err := genNonce()
	if err != nil {
		return nil, fmt.Errorf("hmacauth: 生成 nonce: %w", err)
	}
	sig := computeSig(t.secret, ts, r.Method, r.URL.RequestURI(), body)

	r.Header.Set(HeaderTs, ts)
	r.Header.Set(HeaderNonce, nonce)
	r.Header.Set(HeaderSig, sig)

	return t.base.RoundTrip(r)
}

func genNonce() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// 暴露 ComputeSig 供 caller 在非 net/http 路径下手工签（如 nats RPC）。
func ComputeSig(secret []byte, ts, method, requestURI string, body []byte) string {
	return computeSig(secret, ts, method, requestURI, body)
}

// 保留 errors.Is 兼容
var _ = errors.New
