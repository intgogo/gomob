package laser

import "sync"

// livePointCache 保存当前活动扫描的分镜原始点，供网页刷新后恢复已扫到的部分。
type livePointCache struct {
	mu  sync.RWMutex
	xyz [2][]float32
}

func newLivePointCache() *livePointCache { return &livePointCache{} }

func (c *livePointCache) append(f PointFrame) {
	if c == nil || (f.Unit != 0 && f.Unit != 1) || len(f.XYZmm) == 0 {
		return
	}
	c.mu.Lock()
	c.xyz[f.Unit] = append(c.xyz[f.Unit], f.XYZmm...)
	c.mu.Unlock()
}

func (c *livePointCache) snapshot(unit int) []float32 {
	if c == nil || unit < 0 || unit > 1 {
		return nil
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	out := make([]float32, len(c.xyz[unit]))
	copy(out, c.xyz[unit])
	return out
}

func (c *livePointCache) counts() [2]int {
	var out [2]int
	if c == nil {
		return out
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	out[0] = len(c.xyz[0]) / 3
	out[1] = len(c.xyz[1]) / 3
	return out
}

type liveSessionSink struct {
	active  *activeSession
	primary Sink
}

func (s liveSessionSink) Points(f PointFrame) {
	if s.primary != nil {
		s.primary.Points(f)
	}
	if s.active != nil {
		s.active.appendPoints(f)
	}
}

func (s liveSessionSink) Status(state string, framesA, framesB int) {
	if s.active != nil {
		s.active.setStatus(state, framesA, framesB)
	}
	if s.primary != nil {
		s.primary.Status(state, framesA, framesB)
	}
}

// Image 直接转发给 primary（相机 RGB 预览帧是瞬时流，无需进会话缓存）。
func (s liveSessionSink) Image(f ImageFrame) {
	if s.primary != nil {
		s.primary.Image(f)
	}
}

func (as *activeSession) appendPoints(f PointFrame) {
	if as == nil || as.cache == nil {
		return
	}
	as.cache.append(f)
}

func (as *activeSession) setStatus(state string, framesA, framesB int) {
	if as == nil {
		return
	}
	as.mu.Lock()
	as.state = state
	as.framesA = framesA
	as.framesB = framesB
	as.mu.Unlock()
}

func (as *activeSession) liveStatus() (string, int, int) {
	if as == nil {
		return "", 0, 0
	}
	as.mu.RLock()
	defer as.mu.RUnlock()
	return as.state, as.framesA, as.framesB
}

func (as *activeSession) matches(ipA, ipB string) bool {
	if as == nil {
		return false
	}
	as.mu.RLock()
	defer as.mu.RUnlock()
	if ipA != "" && as.unitAIP != ipA {
		return false
	}
	if ipB != "" && as.unitBIP != ipB {
		return false
	}
	return true
}

func (s *sessionRegistry) find(ipA, ipB string) *activeSession {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, as := range s.active {
		if as.matches(ipA, ipB) {
			return as
		}
	}
	return nil
}
