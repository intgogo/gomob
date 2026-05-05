// 异步消息总线抽象（NATS）。
//
// 提供 Publisher 接口，让业务代码以 ctx + topic + payload 发事件，不绑定具体 broker。
// NATS 实现见 nats.go；测试用 InMemory 实现。
//
// topic 命名按 "<domain>.<event>"：例 model.version.activated / inspection.created。
// 详见 docs/architecture/registry/server-dependencies.yaml nats_topics 段。
package pubsub

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/nats-io/nats.go"
)

// Publisher 发布事件。Publish 失败不应阻塞业务（调用方可吞 err 但要监控）。
type Publisher interface {
	Publish(ctx context.Context, topic string, payload any) error
	Close() error
}

// ----- NATS 实现 -----

type NATSPublisher struct {
	conn *nats.Conn
}

// NewNATS 连接到 NATS 服务器（dev 默认 nats://127.0.0.1:4222）。
func NewNATS(url string) (*NATSPublisher, error) {
	if url == "" {
		url = nats.DefaultURL
	}
	conn, err := nats.Connect(url,
		nats.Name("gomob"),
		nats.Timeout(2*time.Second),
		nats.MaxReconnects(-1),                  // 无限重连
		nats.ReconnectWait(time.Second),
	)
	if err != nil {
		return nil, err
	}
	return &NATSPublisher{conn: conn}, nil
}

// NewNATSPublisher 复用调用方已建立的 *nats.Conn（多路服务共享连接，不重复建连）。
//
// 调用方仍负责 Conn.Close() — Publisher.Close() 不会关连接。
func NewNATSPublisher(conn *nats.Conn) *NATSPublisher {
	return &NATSPublisher{conn: conn}
}

func (p *NATSPublisher) Publish(_ context.Context, topic string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return p.conn.Publish(topic, body)
}

func (p *NATSPublisher) Close() error {
	if p.conn == nil {
		return nil
	}
	p.conn.Close()
	return nil
}

// Conn 返回底层 nats.Conn，给需要订阅 / 高级用法的服务使用。
func (p *NATSPublisher) Conn() *nats.Conn { return p.conn }

// ----- InMemory 实现（测试用） -----

type Event struct {
	Topic   string
	Payload []byte
}

type InMemory struct {
	mu     sync.Mutex
	Events []Event
}

func (m *InMemory) Publish(_ context.Context, topic string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	m.mu.Lock()
	m.Events = append(m.Events, Event{Topic: topic, Payload: body})
	m.mu.Unlock()
	return nil
}

func (m *InMemory) Close() error { return nil }
