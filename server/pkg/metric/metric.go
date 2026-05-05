// 指标抽象 — 不绑定具体后端（Prometheus / OTLP），让各服务在自己的 main 里选择实现。
//
// 设计目标：
//   - 公共代码（pkg/audit / pkg/repo 等）以 metric.Counter / metric.Histogram 为参数，
//     避免被 prometheus client 的具体类型污染
//   - 测试用 InMemory 实现验证业务侧"是否调用了正确的 metric"
//   - 生产实现（Prometheus）放各服务的 internal/<svc>/metric.go，引入 prometheus/client_golang
package metric

import "sync"

// Counter 单调递增计数器。
type Counter interface {
	Inc()
	Add(delta float64)
}

// Histogram 分布观测（如 latency_ms）。
type Histogram interface {
	Observe(value float64)
}

// Gauge 可升可降的瞬时值（如 online_users）。
type Gauge interface {
	Set(v float64)
	Add(delta float64)
}

// Registry 工厂；服务自己实现并注入业务代码。
type Registry interface {
	Counter(name string, labels ...string) Counter
	Histogram(name string, labels ...string) Histogram
	Gauge(name string, labels ...string) Gauge
}

// —— InMemory 实现（测试用） ——

type memCounter struct {
	mu sync.Mutex
	v  float64
}

func (c *memCounter) Inc()                { c.Add(1) }
func (c *memCounter) Add(d float64)       { c.mu.Lock(); c.v += d; c.mu.Unlock() }
func (c *memCounter) Value() float64      { c.mu.Lock(); defer c.mu.Unlock(); return c.v }

type memHistogram struct {
	mu      sync.Mutex
	samples []float64
}

func (h *memHistogram) Observe(v float64)   { h.mu.Lock(); h.samples = append(h.samples, v); h.mu.Unlock() }
func (h *memHistogram) Samples() []float64  { h.mu.Lock(); defer h.mu.Unlock(); return append([]float64(nil), h.samples...) }

type memGauge struct {
	mu sync.Mutex
	v  float64
}

func (g *memGauge) Set(v float64)    { g.mu.Lock(); g.v = v; g.mu.Unlock() }
func (g *memGauge) Add(d float64)    { g.mu.Lock(); g.v += d; g.mu.Unlock() }
func (g *memGauge) Value() float64   { g.mu.Lock(); defer g.mu.Unlock(); return g.v }

// InMemory 单进程内存 Registry；适合单测断言。
type InMemory struct {
	mu        sync.Mutex
	counters  map[string]*memCounter
	histos    map[string]*memHistogram
	gauges    map[string]*memGauge
}

func NewInMemory() *InMemory {
	return &InMemory{
		counters: map[string]*memCounter{},
		histos:   map[string]*memHistogram{},
		gauges:   map[string]*memGauge{},
	}
}

func (r *InMemory) Counter(name string, _ ...string) Counter {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.counters[name]
	if !ok {
		c = &memCounter{}
		r.counters[name] = c
	}
	return c
}

func (r *InMemory) Histogram(name string, _ ...string) Histogram {
	r.mu.Lock()
	defer r.mu.Unlock()
	h, ok := r.histos[name]
	if !ok {
		h = &memHistogram{}
		r.histos[name] = h
	}
	return h
}

func (r *InMemory) Gauge(name string, _ ...string) Gauge {
	r.mu.Lock()
	defer r.mu.Unlock()
	g, ok := r.gauges[name]
	if !ok {
		g = &memGauge{}
		r.gauges[name] = g
	}
	return g
}

// CounterValue / HistogramSamples / GaugeValue 是测试辅助函数；产品代码不应使用。
func (r *InMemory) CounterValue(name string) float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if c, ok := r.counters[name]; ok {
		return c.Value()
	}
	return 0
}

func (r *InMemory) HistogramSamples(name string) []float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, ok := r.histos[name]; ok {
		return h.Samples()
	}
	return nil
}

func (r *InMemory) GaugeValue(name string) float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if g, ok := r.gauges[name]; ok {
		return g.Value()
	}
	return 0
}
