// LLM provider 多提供商 failover（M-S11.7）。
//
// 当主 provider（如 DeepSeek）不可达 / 返限流 / 鉴权失败时，自动切到下一个 provider
// 继续服务。流式场景下：
//
//	- 在第一个 chunk 抵达**之前**任何错误（连接失败 / 鉴权 / 首字超时）→ fallback
//	- 已经吐出过 chunk 后再失败 → 不 fallback（返已收到内容 + 错误，由 caller 决定如何处理）
//
// 这避免"流到一半切到另一个 model 接续"导致的回答风格突变。
package llmgateway

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"
)

// FallbackProvider 把多个 Provider 串成"主-备-备"链。
//
// Name() 返链名（不返 primary 单名）便于审计区分。
type FallbackProvider struct {
	chain []Provider
	log   *slog.Logger
}

// NewFallbackProvider chain[0] 是主；后面依次 fallback。
//
// chain 至少含 1 个 Provider；空时 panic（明显的编码错误）。
func NewFallbackProvider(log *slog.Logger, chain ...Provider) *FallbackProvider {
	if len(chain) == 0 {
		panic("FallbackProvider: chain 至少 1 个 Provider")
	}
	if log == nil {
		log = slog.Default()
	}
	return &FallbackProvider{chain: chain, log: log}
}

// Name 返 "fallback(<name1>>><name2>...)" 便于审计。
func (f *FallbackProvider) Name() string {
	names := make([]string, 0, len(f.chain))
	for _, p := range f.chain {
		names = append(names, p.Name())
	}
	return "fallback(" + strings.Join(names, ">") + ")"
}

// Chat 顺序尝试链上每个 provider，返第一个成功的 (text, usage)；
// usage.Model 会被 provider 自己设；调用方按需观察 usage 看实际命中谁。
//
// ctx 取消（如 client cancel）→ 立即返 ctx.Err()，不再尝试 fallback。
func (f *FallbackProvider) Chat(ctx context.Context, req ChatRequest) (string, Usage, error) {
	var lastErr error
	for i, p := range f.chain {
		if ctx.Err() != nil {
			return "", Usage{}, ctx.Err()
		}
		t0 := time.Now()
		text, usage, err := p.Chat(ctx, req)
		if err == nil {
			if i > 0 {
				f.log.Info("fallback 命中后续 provider",
					"provider", p.Name(), "rank", i, "latency_ms", time.Since(t0).Milliseconds())
			}
			return text, usage, nil
		}
		// ctx 类错误（client cancel / timeout）不切；上游已经放弃
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return "", Usage{}, err
		}
		f.log.Warn("provider 失败，尝试下一个",
			"provider", p.Name(), "rank", i, "err", err)
		lastErr = err
	}
	return "", Usage{}, fmt.Errorf("所有 fallback provider 均失败: %w", lastErr)
}

// ChatStream 流式 fallback：
//
//	- 首 chunk 之前任何 provider 错误 → 切下一个
//	- 已吐过 chunk 之后再错 → 返当前 provider 错误（不切，避免风格突变）
//	- onChunk 返 false（caller 取消）→ 立即返 nil（视为正常结束）
func (f *FallbackProvider) ChatStream(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
	var lastErr error
	for i, p := range f.chain {
		if ctx.Err() != nil {
			return Usage{}, ctx.Err()
		}

		// 包一层：捕获"已吐过 chunk"标记。一旦 emitted=true，后续错误不切。
		emitted := false
		wrappedOnChunk := func(c Chunk) bool {
			if c.Content != "" {
				emitted = true
			}
			return onChunk(c)
		}

		t0 := time.Now()
		usage, err := p.ChatStream(ctx, req, wrappedOnChunk)
		if err == nil {
			if i > 0 {
				f.log.Info("stream fallback 命中后续 provider",
					"provider", p.Name(), "rank", i, "latency_ms", time.Since(t0).Milliseconds())
			}
			return usage, nil
		}
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return usage, err
		}
		// 已吐过 chunk → 不能切，必须把错误抛给调用方
		if emitted {
			f.log.Warn("provider 流到一半失败，不切（保持上下文一致）",
				"provider", p.Name(), "rank", i, "err", err)
			return usage, err
		}
		f.log.Warn("stream provider 失败（尚未吐 chunk），尝试下一个",
			"provider", p.Name(), "rank", i, "err", err)
		lastErr = err
	}
	return Usage{}, fmt.Errorf("所有 fallback provider 均失败: %w", lastErr)
}

// BuildFallbackChain 按名字列表（["deepseek","mock"]）从 registry 找 provider 拼链。
//
// 找不到的名字会被跳过 + 日志告警；空链时返 nil。
func BuildFallbackChain(reg *Registry, names []string, log *slog.Logger) Provider {
	if reg == nil || len(names) == 0 {
		return nil
	}
	if log == nil {
		log = slog.Default()
	}
	chain := make([]Provider, 0, len(names))
	for _, n := range names {
		n = strings.TrimSpace(n)
		if n == "" {
			continue
		}
		// Pick 在找不到时返 fallback；用更严的 lookup
		if p, ok := reg.providers[n]; ok {
			chain = append(chain, p)
		} else {
			log.Warn("fallback chain 名字未注册，跳过", "name", n, "available", reg.Names())
		}
	}
	if len(chain) == 0 {
		return nil
	}
	if len(chain) == 1 {
		// 只有一个时不值得包装
		return chain[0]
	}
	return NewFallbackProvider(log, chain...)
}
