// LLM provider 抽象 — 业务代码只依赖本接口；具体实现（DeepSeek / Mock / 未来 OpenAI / Claude）在同包内。
//
// 设计目标：
//   - provider-agnostic — 调用方传 system+user prompt，拿到 chat reply 或 stream
//   - 流式 / 非流式两套接口；非流式是 stream 的便捷封装
//   - 客户端断开 → 通过 ctx.Done 取消上游（避免 token 浪费）
package llmgateway

import (
	"context"
	"errors"
)

// ChatRequest 一次会话请求。
type ChatRequest struct {
	System string // system prompt
	User   string // 渲染后的 user prompt
	Model  string // 留空走 provider 默认
}

// Chunk 一次流式输出片段。
type Chunk struct {
	Content string // 增量文本
}

// Usage 一次调用的 token / latency / cost 计量。
type Usage struct {
	TokenIn   int
	TokenOut  int
	LatencyMS int
	Model     string
}

// Provider 一个 LLM 后端。
type Provider interface {
	Name() string
	// Chat 非流式；返回完整内容 + usage。失败返 ErrProvider*。
	Chat(ctx context.Context, req ChatRequest) (string, Usage, error)
	// ChatStream 流式；每个 chunk 通过回调送出。回调返 false 时停止（取消上游）。
	// 完成后返回 final usage（与 Chat 保持一致）。
	ChatStream(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error)
}

// 错误集合。
var (
	ErrProviderUnavailable = errors.New("llm provider unavailable")
	ErrProviderBadResp     = errors.New("llm provider returned malformed response")
	ErrProviderRateLimit   = errors.New("llm provider rate limit")
	ErrProviderAuthFail    = errors.New("llm provider auth failed")
)

// Registry 把 provider name → Provider 的映射做集中。
type Registry struct {
	providers map[string]Provider
	// 默认 provider（当模板未指定 / 指定 provider 不存在时）
	fallback Provider
}

func NewRegistry(defaultProvider Provider, others ...Provider) *Registry {
	r := &Registry{providers: map[string]Provider{defaultProvider.Name(): defaultProvider}}
	r.fallback = defaultProvider
	for _, p := range others {
		r.providers[p.Name()] = p
	}
	return r
}

func (r *Registry) Pick(preferred string) Provider {
	if p, ok := r.providers[preferred]; ok {
		return p
	}
	return r.fallback
}

func (r *Registry) Names() []string {
	out := make([]string, 0, len(r.providers))
	for n := range r.providers {
		out = append(out, n)
	}
	return out
}

// AllProviders 返回所有已注册 provider（无序）。
//
// 用于上层在不知道名字时拿全集（如 main.go 重建 registry 时保留单 provider 入口）。
func (r *Registry) AllProviders() []Provider {
	out := make([]Provider, 0, len(r.providers))
	for _, p := range r.providers {
		out = append(out, p)
	}
	return out
}

// NewRegistryWithFallback 用一个明确的 fallback Provider（可能是 FallbackProvider 链）
// 替换默认 fallback 行为。其它 providers 仍按 Name() 注册（Pick 单走时用）。
func NewRegistryWithFallback(fallback Provider, others ...Provider) *Registry {
	r := &Registry{providers: map[string]Provider{}, fallback: fallback}
	// 不把 fallback 自己注册到 providers（它的 Name() 是 "fallback(...)" 不该被模板指名）
	for _, p := range others {
		r.providers[p.Name()] = p
	}
	return r
}
