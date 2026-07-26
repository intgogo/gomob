package laser

import (
	"reflect"
	"testing"
)

func TestLivePointCacheIsBoundedDeterministicAndChunkInvariant(t *testing.T) {
	const (
		limit  = 32
		points = 10_000
	)
	all := make([]float32, 0, points*3)
	for index := 0; index < points; index++ {
		all = append(all, float32(index), float32(index)+0.25, -float32(index))
	}

	oneFrame := newLivePointCacheWithLimit(limit)
	oneFrame.append(PointFrame{Unit: 0, XYZmm: all})
	chunked := newLivePointCacheWithLimit(limit)
	for offset := 0; offset < points; {
		count := 137
		if remaining := points - offset; count > remaining {
			count = remaining
		}
		chunked.append(PointFrame{Unit: 0, XYZmm: all[offset*3 : (offset+count)*3]})
		offset += count
	}

	got := oneFrame.snapshot(0, 0)
	chunkedGot := chunked.snapshot(0, 0)
	if got.SourcePoints != points || len(got.XYZ) != limit*3 {
		t.Fatalf("缓存上界/源点数错误: source=%d render=%d", got.SourcePoints, len(got.XYZ)/3)
	}
	if !reflect.DeepEqual(got.XYZ, chunkedGot.XYZ) {
		t.Fatal("同一点流按不同帧分块后样本不一致")
	}
	if cap(oneFrame.xyz[0]) > limit*3 {
		t.Fatalf("底层容量越界: cap=%d limit=%d", cap(oneFrame.xyz[0]), limit*3)
	}
	if counts := oneFrame.counts(); counts != [2]int{points, 0} {
		t.Fatalf("活动点数必须报告源点数，得 %+v", counts)
	}
}

func TestLivePointCacheSnapshotAppliesSmallerRenderBudget(t *testing.T) {
	cache := newLivePointCacheWithLimit(8)
	for index := 0; index < 100; index++ {
		cache.append(PointFrame{Unit: 1, XYZmm: []float32{float32(index), 1, 2}})
	}
	full := cache.snapshot(1, 0)
	sampled := cache.snapshot(1, 3)
	if full.SourcePoints != 100 || len(full.XYZ) != 8*3 {
		t.Fatalf("完整缓存错误: source=%d render=%d", full.SourcePoints, len(full.XYZ)/3)
	}
	if sampled.SourcePoints != 100 || len(sampled.XYZ) != 3*3 {
		t.Fatalf("二次渲染预算错误: source=%d render=%d", sampled.SourcePoints, len(sampled.XYZ)/3)
	}
	if !reflect.DeepEqual(sampled.XYZ, cache.snapshot(1, 3).XYZ) {
		t.Fatal("相同缓存与预算必须得到确定性样本")
	}
}
