// DeepSeek provider — 调用 https://api.deepseek.com/chat/completions（OpenAI 兼容协议）。
//
// 关键设计：
//   - 流式走 SSE（Accept: text/event-stream），逐 chunk 解析 `data: {...}`
//   - ctx.Done 取消上游：context 注入 http.Request，cancel 时 transport 关连接
//   - 错误分类：401→ErrProviderAuthFail / 429→ErrProviderRateLimit / 5xx→ErrProviderUnavailable
//   - usage：DeepSeek SSE 末尾会带 usage 字段（OpenAI 兼容）
package llmgateway

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	defaultDeepSeekEndpoint = "https://api.deepseek.com/chat/completions"
	defaultDeepSeekModel    = "deepseek-chat"
)

type DeepSeekProvider struct {
	endpoint string
	apiKey   string
	model    string
	client   *http.Client
}

type DeepSeekConfig struct {
	APIKey   string        // 必填
	Endpoint string        // 留空走默认
	Model    string        // 留空走 deepseek-chat
	Timeout  time.Duration // 0 → 60s
}

func NewDeepSeekProvider(cfg DeepSeekConfig) *DeepSeekProvider {
	if cfg.Endpoint == "" {
		cfg.Endpoint = defaultDeepSeekEndpoint
	}
	if cfg.Model == "" {
		cfg.Model = defaultDeepSeekModel
	}
	timeout := cfg.Timeout
	if timeout == 0 {
		timeout = 60 * time.Second
	}
	return &DeepSeekProvider{
		endpoint: cfg.Endpoint,
		apiKey:   cfg.APIKey,
		model:    cfg.Model,
		client:   &http.Client{Timeout: timeout},
	}
}

func (p *DeepSeekProvider) Name() string { return "deepseek" }

// OpenAI 兼容 schema
type chatMsg struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatReq struct {
	Model    string    `json:"model"`
	Messages []chatMsg `json:"messages"`
	Stream   bool      `json:"stream"`
}

type chatResp struct {
	Choices []struct {
		Message chatMsg `json:"message"`
	} `json:"choices"`
	Usage struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
	Model string `json:"model"`
}

type chatStreamChunk struct {
	Choices []struct {
		Delta        chatMsg `json:"delta"`
		FinishReason *string `json:"finish_reason"`
	} `json:"choices"`
	Usage *struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage,omitempty"`
	Model string `json:"model,omitempty"`
}

func (p *DeepSeekProvider) buildBody(req ChatRequest, stream bool) ([]byte, string) {
	model := req.Model
	if model == "" {
		model = p.model
	}
	msgs := make([]chatMsg, 0, 2)
	if req.System != "" {
		msgs = append(msgs, chatMsg{Role: "system", Content: req.System})
	}
	msgs = append(msgs, chatMsg{Role: "user", Content: req.User})
	body, _ := json.Marshal(chatReq{Model: model, Messages: msgs, Stream: stream})
	return body, model
}

func (p *DeepSeekProvider) classifyHTTPError(status int) error {
	switch {
	case status == http.StatusUnauthorized:
		return ErrProviderAuthFail
	case status == http.StatusTooManyRequests:
		return ErrProviderRateLimit
	case status >= 500:
		return ErrProviderUnavailable
	default:
		return fmt.Errorf("%w: http %d", ErrProviderBadResp, status)
	}
}

func (p *DeepSeekProvider) Chat(ctx context.Context, req ChatRequest) (string, Usage, error) {
	if p.apiKey == "" {
		return "", Usage{}, ErrProviderAuthFail
	}
	t0 := time.Now()
	body, model := p.buildBody(req, false)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, p.endpoint, bytes.NewReader(body))
	if err != nil {
		return "", Usage{}, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+p.apiKey)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := p.client.Do(httpReq)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return "", Usage{}, err
		}
		return "", Usage{}, fmt.Errorf("%w: %v", ErrProviderUnavailable, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", Usage{}, p.classifyHTTPError(resp.StatusCode)
	}
	var cr chatResp
	if err := json.NewDecoder(resp.Body).Decode(&cr); err != nil {
		return "", Usage{}, fmt.Errorf("%w: %v", ErrProviderBadResp, err)
	}
	if len(cr.Choices) == 0 {
		return "", Usage{}, ErrProviderBadResp
	}
	usedModel := cr.Model
	if usedModel == "" {
		usedModel = model
	}
	return cr.Choices[0].Message.Content, Usage{
		TokenIn:   cr.Usage.PromptTokens,
		TokenOut:  cr.Usage.CompletionTokens,
		LatencyMS: int(time.Since(t0).Milliseconds()),
		Model:     usedModel,
	}, nil
}

func (p *DeepSeekProvider) ChatStream(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
	if p.apiKey == "" {
		return Usage{}, ErrProviderAuthFail
	}
	t0 := time.Now()
	body, model := p.buildBody(req, true)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, p.endpoint, bytes.NewReader(body))
	if err != nil {
		return Usage{}, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+p.apiKey)
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Accept", "text/event-stream")

	resp, err := p.client.Do(httpReq)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return Usage{}, err
		}
		return Usage{}, fmt.Errorf("%w: %v", ErrProviderUnavailable, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Usage{}, p.classifyHTTPError(resp.StatusCode)
	}

	usage := Usage{Model: model}
	br := bufio.NewReader(resp.Body)
	for {
		line, err := br.ReadBytes('\n')
		if err != nil && err != io.EOF {
			return usage, err
		}
		trim := bytes.TrimSpace(line)
		if len(trim) == 0 {
			if err == io.EOF {
				break
			}
			continue
		}
		if !bytes.HasPrefix(trim, []byte("data:")) {
			continue
		}
		data := bytes.TrimSpace(trim[len("data:"):])
		if string(data) == "[DONE]" {
			break
		}
		var sc chatStreamChunk
		if err := json.Unmarshal(data, &sc); err != nil {
			continue // 容忍 SSE 中偶尔的 keep-alive 或非标 chunk
		}
		if sc.Usage != nil {
			usage.TokenIn = sc.Usage.PromptTokens
			usage.TokenOut = sc.Usage.CompletionTokens
		}
		if sc.Model != "" {
			usage.Model = sc.Model
		}
		for _, c := range sc.Choices {
			if c.Delta.Content == "" {
				continue
			}
			if !onChunk(Chunk{Content: c.Delta.Content}) {
				usage.LatencyMS = int(time.Since(t0).Milliseconds())
				return usage, nil
			}
		}
		if err == io.EOF {
			break
		}
	}
	usage.LatencyMS = int(time.Since(t0).Milliseconds())
	return usage, nil
}

// 不导出但便于诊断：判断 endpoint 是否标准 DeepSeek（用于日志）。
func (p *DeepSeekProvider) IsDefaultEndpoint() bool {
	return strings.HasPrefix(p.endpoint, defaultDeepSeekEndpoint)
}
