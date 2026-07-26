package laser

import (
	"context"
	"errors"
	"testing"
)

type pointMessagePublisher struct {
	messages       []LaserPointsMsg
	statusMessages []LaserStatusMsg
	failNext       bool
}

func (p *pointMessagePublisher) Publish(_ context.Context, topic string, payload any) error {
	if topic == TopicLaserStatus {
		p.statusMessages = append(p.statusMessages, payload.(LaserStatusMsg))
		return nil
	}
	if topic != TopicLaserPoints {
		return nil
	}
	if p.failNext {
		p.failNext = false
		return errors.New("模拟传输失败")
	}
	p.messages = append(p.messages, payload.(LaserPointsMsg))
	return nil
}

func TestNATSSinkStatusCarriesReliableFinalSourceCounts(t *testing.T) {
	pub := &pointMessagePublisher{}
	sink := NewNATSSink(pub, "session-status", nil, nil)
	sink.Points(PointFrame{Unit: 0, XYZmm: make([]float32, 11*3)})
	sink.Points(PointFrame{Unit: 1, XYZmm: make([]float32, 7*3)})
	sink.Status("fusing", 3, 2)

	if len(pub.statusMessages) != 1 {
		t.Fatalf("状态消息数应为 1，得 %d", len(pub.statusMessages))
	}
	got := pub.statusMessages[0]
	if got.SourcePointsA != 11 || got.SourcePointsB != 7 {
		t.Fatalf("状态累计点数错误: A=%d B=%d", got.SourcePointsA, got.SourcePointsB)
	}
}

func TestNATSSinkPublishesCumulativeRegionPointCountAcrossChunks(t *testing.T) {
	pub := &pointMessagePublisher{}
	sink := NewNATSSink(pub, "session-1", nil, nil)
	sink.Points(PointFrame{Unit: 0, XYZmm: make([]float32, 9_000*3), HAngleDeg: 12})
	sink.Points(PointFrame{Unit: 0, XYZmm: make([]float32, 3*3), HAngleDeg: 13})
	sink.Points(PointFrame{Unit: 1, XYZmm: make([]float32, 5*3), HAngleDeg: 14})

	if len(pub.messages) != 4 {
		t.Fatalf("消息数应为 4，得 %d", len(pub.messages))
	}
	want := []struct {
		unit   int
		points int
		total  int
	}{
		{unit: 0, points: maxPointsPerMsg, total: maxPointsPerMsg},
		{unit: 0, points: 9_000 - maxPointsPerMsg, total: 9_000},
		{unit: 0, points: 3, total: 9_003},
		{unit: 1, points: 5, total: 5},
	}
	for i, expected := range want {
		got := pub.messages[i]
		if got.Unit != expected.unit || len(got.Points)/3 != expected.points || got.SourcePoints != expected.total {
			t.Fatalf("消息 %d 错误: unit=%d points=%d source=%d", i, got.Unit, len(got.Points)/3, got.SourcePoints)
		}
	}
}

func TestNATSSinkCumulativeCountSurvivesDroppedPublish(t *testing.T) {
	pub := &pointMessagePublisher{failNext: true}
	sink := NewNATSSink(pub, "session-2", nil, nil)
	sink.Points(PointFrame{Unit: 0, XYZmm: make([]float32, 100*3)})
	sink.Points(PointFrame{Unit: 0, XYZmm: make([]float32, 7*3)})

	if len(pub.messages) != 1 {
		t.Fatalf("失败后的下一帧应成功发布，得 %d 条", len(pub.messages))
	}
	if got := pub.messages[0].SourcePoints; got != 107 {
		t.Fatalf("累计源点数应包含传输失败的 100 点，得 %d", got)
	}
}
