package metric

import (
	"sync"
	"testing"
)

func TestCounter(t *testing.T) {
	r := NewInMemory()
	c := r.Counter("requests_total")
	c.Inc()
	c.Inc()
	c.Add(3)
	if v := r.CounterValue("requests_total"); v != 5 {
		t.Fatalf("counter=%v, want 5", v)
	}
}

func TestHistogramAndGauge(t *testing.T) {
	r := NewInMemory()
	h := r.Histogram("latency_ms")
	for _, v := range []float64{1, 2, 3, 4, 5} {
		h.Observe(v)
	}
	if got := r.HistogramSamples("latency_ms"); len(got) != 5 {
		t.Fatalf("samples=%d, want 5", len(got))
	}
	g := r.Gauge("online")
	g.Set(7)
	g.Add(-2)
	if v := r.GaugeValue("online"); v != 5 {
		t.Fatalf("gauge=%v, want 5", v)
	}
}

func TestConcurrentCounter(t *testing.T) {
	r := NewInMemory()
	c := r.Counter("hits")
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.Inc()
		}()
	}
	wg.Wait()
	if v := r.CounterValue("hits"); v != 100 {
		t.Fatalf("concurrent counter=%v, want 100", v)
	}
}
