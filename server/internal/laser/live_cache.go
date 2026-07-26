package laser

import "sync"

const livePointCacheMaxPointsPerUnit = 262_144

// livePointCache 保存当前活动扫描的确定性有界预览样本，供客户端刷新后恢复。
// 融合权威数据仍由 Runner 的完整 A/B 云负责，预览缓存不得随扫描时长无界增长。
type livePointCache struct {
	mu        sync.RWMutex
	maxPoints int
	xyz       [2][]float32
	seen      [2]uint64
}

type livePointSnapshot struct {
	XYZ          []float32
	SourcePoints int
}

func newLivePointCache() *livePointCache {
	return newLivePointCacheWithLimit(livePointCacheMaxPointsPerUnit)
}

func newLivePointCacheWithLimit(maxPoints int) *livePointCache {
	if maxPoints < 0 {
		maxPoints = 0
	}
	cache := &livePointCache{maxPoints: maxPoints}
	cache.xyz[0] = make([]float32, 0, maxPoints*3)
	cache.xyz[1] = make([]float32, 0, maxPoints*3)
	return cache
}

func (c *livePointCache) append(f PointFrame) {
	if c == nil || (f.Unit != 0 && f.Unit != 1) || len(f.XYZmm) == 0 {
		return
	}
	c.mu.Lock()
	unit := f.Unit
	for offset := 0; offset+2 < len(f.XYZmm); offset += 3 {
		c.seen[unit]++
		sourceIndex := c.seen[unit]
		cachedPoints := len(c.xyz[unit]) / 3
		if cachedPoints < c.maxPoints {
			c.xyz[unit] = append(c.xyz[unit], f.XYZmm[offset:offset+3]...)
			continue
		}
		if c.maxPoints == 0 {
			continue
		}
		slot := deterministicReservoirSlot(sourceIndex, uint64(unit+1))
		if slot >= uint64(c.maxPoints) {
			continue
		}
		dst := int(slot) * 3
		copy(c.xyz[unit][dst:dst+3], f.XYZmm[offset:offset+3])
	}
	c.mu.Unlock()
}

func deterministicReservoirSlot(sourcePoints, seed uint64) uint64 {
	value := sourcePoints ^ (seed * 0x9e3779b97f4a7c15)
	value += 0x9e3779b97f4a7c15
	value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9
	value = (value ^ (value >> 27)) * 0x94d049bb133111eb
	value ^= value >> 31
	return value % sourcePoints
}

func (c *livePointCache) snapshot(unit, maxPoints int) livePointSnapshot {
	if c == nil || unit < 0 || unit > 1 {
		return livePointSnapshot{}
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	cachedPoints := len(c.xyz[unit]) / 3
	renderPoints := cachedPoints
	if maxPoints > 0 && renderPoints > maxPoints {
		renderPoints = maxPoints
	}
	out := make([]float32, renderPoints*3)
	if renderPoints == cachedPoints {
		copy(out, c.xyz[unit])
	} else {
		for index := 0; index < renderPoints; index++ {
			sourceIndex := stratifiedSampleIndex(index, renderPoints, cachedPoints)
			copy(out[index*3:index*3+3], c.xyz[unit][sourceIndex*3:sourceIndex*3+3])
		}
	}
	return livePointSnapshot{XYZ: out, SourcePoints: uint64ToInt(c.seen[unit])}
}

func (c *livePointCache) counts() [2]int {
	var out [2]int
	if c == nil {
		return out
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	out[0] = uint64ToInt(c.seen[0])
	out[1] = uint64ToInt(c.seen[1])
	return out
}

func uint64ToInt(value uint64) int {
	maxInt := uint64(^uint(0) >> 1)
	if value > maxInt {
		return int(maxInt)
	}
	return int(value)
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
