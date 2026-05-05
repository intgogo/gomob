// 审计日志接口（详见 02-api-contract.md §11 / 00-server-overview.md §11）。
//
// 所有数据修改类操作（PATCH / POST 写）必须通过 Recorder.Record 记录 who/when/what/before/after。
// 默认走 PostgreSQL `audit_log` 表；测试用 InMemory 实现。
package audit

import (
	"context"
	"encoding/json"
	"sync"
	"time"
)

// Entry 一条审计记录。Before/After 已序列化为 JSON 字符串，便于跨服务一致存储。
type Entry struct {
	UserID    int64
	Action    string    // 例: inspection.update_result
	Target    string    // 例: inspection:123
	BeforeRaw string    // JSON
	AfterRaw  string    // JSON
	IP        string
	CreatedAt time.Time
}

// Recorder 审计写入抽象。
type Recorder interface {
	Record(ctx context.Context, e Entry) error
}

// Encode 把任意值序列化成 JSON 字符串（写库前的便捷工具）。
// nil 值得 ""，便于区分"未变更"和"清空"。
func Encode(v any) (string, error) {
	if v == nil {
		return "", nil
	}
	b, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// InMemory 测试用，线程安全；按 Record 顺序保留。
type InMemory struct {
	mu      sync.Mutex
	Entries []Entry
}

func (m *InMemory) Record(_ context.Context, e Entry) error {
	if e.CreatedAt.IsZero() {
		e.CreatedAt = time.Now()
	}
	m.mu.Lock()
	m.Entries = append(m.Entries, e)
	m.mu.Unlock()
	return nil
}
