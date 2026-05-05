// Mock provider — 无外网 / 无 API key 时使用。
//
// 行为：
//   - 不调任何外部 HTTP；本地按规则生成回答
//   - 流式：把回答按字符切 chunk，每 chunk 间 sleep 一小段（默认 5 ms / chunk）模拟流式
//   - usage：token_in 按 user prompt 长度估算；token_out 按生成字符数估算
//
// 让 harness / 单测可重复运行，且能验证客户端断开 → 上游取消逻辑。
package llmgateway

import (
	"context"
	"fmt"
	"strings"
	"time"
)

type MockProvider struct {
	chunkDelay time.Duration
	model      string
}

func NewMockProvider() *MockProvider {
	return &MockProvider{
		chunkDelay: 5 * time.Millisecond,
		model:      "mock-llm-v1",
	}
}

// SetChunkDelay 调流式间隔（测试用）。
func (m *MockProvider) SetChunkDelay(d time.Duration) { m.chunkDelay = d }

func (m *MockProvider) Name() string { return "mock" }

func (m *MockProvider) generate(req ChatRequest) string {
	// 以"已收到 system+user"为前缀，便于 harness 断言。后端无关的稳定输出。
	prefix := "[mock-llm 答复]"
	if strings.Contains(req.User, "VIN") {
		return prefix + " 该 VIN 字形比对置信度 0.93，建议人工复核车架号刻打区域。"
	}
	if strings.TrimSpace(req.User) == "" {
		return prefix + " 请提供具体问题。"
	}
	return prefix + " 已根据 system+user 生成回答（user prompt 长度 " +
		fmt.Sprint(len(req.User)) + " 字符）。"
}

func (m *MockProvider) Chat(ctx context.Context, req ChatRequest) (string, Usage, error) {
	t0 := time.Now()
	// 仍然尊重 ctx 取消（即便不调外网）
	select {
	case <-ctx.Done():
		return "", Usage{}, ctx.Err()
	case <-time.After(20 * time.Millisecond):
	}
	out := m.generate(req)
	return out, Usage{
		TokenIn:   estimateTokens(req.System) + estimateTokens(req.User),
		TokenOut:  estimateTokens(out),
		LatencyMS: int(time.Since(t0).Milliseconds()),
		Model:     m.model,
	}, nil
}

func (m *MockProvider) ChatStream(ctx context.Context, req ChatRequest, onChunk func(Chunk) bool) (Usage, error) {
	t0 := time.Now()
	full := m.generate(req)

	// 按 rune 切，模拟逐字流式（中文友好）。
	runes := []rune(full)
	chunkSize := 4 // 每 4 个 rune 一片
	emitted := 0
	for i := 0; i < len(runes); i += chunkSize {
		end := i + chunkSize
		if end > len(runes) {
			end = len(runes)
		}
		piece := string(runes[i:end])

		select {
		case <-ctx.Done():
			return Usage{
				TokenIn:   estimateTokens(req.System) + estimateTokens(req.User),
				TokenOut:  estimateTokens(string(runes[:emitted])),
				LatencyMS: int(time.Since(t0).Milliseconds()),
				Model:     m.model,
			}, ctx.Err()
		case <-time.After(m.chunkDelay):
		}

		if !onChunk(Chunk{Content: piece}) {
			// 上层不再要数据
			return Usage{
				TokenIn:   estimateTokens(req.System) + estimateTokens(req.User),
				TokenOut:  estimateTokens(string(runes[:emitted])),
				LatencyMS: int(time.Since(t0).Milliseconds()),
				Model:     m.model,
			}, nil
		}
		emitted = end
	}

	return Usage{
		TokenIn:   estimateTokens(req.System) + estimateTokens(req.User),
		TokenOut:  estimateTokens(full),
		LatencyMS: int(time.Since(t0).Milliseconds()),
		Model:     m.model,
	}, nil
}

// estimateTokens 粗略 token 估算（mock 用）。中文 1 字 ≈ 1 token，英文每 4 字符 ≈ 1 token。
func estimateTokens(s string) int {
	if s == "" {
		return 0
	}
	tokens := 0
	for _, r := range s {
		if r > 127 {
			tokens++
		} else {
			tokens++ // 简化：所有字符都按 1 token；这是 mock 不必精确
		}
	}
	return (tokens + 3) / 4
}
