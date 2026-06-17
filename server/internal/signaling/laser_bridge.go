// LaserBridge —— 把 NATS laser.points / laser.status 桥接到 ws，实时给端侧激光扫描预览（M8'）。
//
// 拓扑：laserworker（独立进程，请求驱动）采集双单元激光时，逐帧把增量点（unit 0/1）发 NATS
// laser.points、状态机变更发 laser.status；signaling 订阅这两个，按 owner_user_id 推给该用户
// 在线 ws 连接。端侧据 laser.points 增量喂两镜头点云、据 laser.status 显示阶段；扫描完成的最终
// 三朵 PCD 走既有 scan.fusion_done(kind:laser) 桥（FusionBridge）。
//
// 与 FusionBridge 同范式、同信任边界：本桥信任事件来自可信发布者（laserworker），owner_user_id
// 由 laserworker 从 DB（POST 时按已鉴权用户写入）回发，非客户端可控。生产 NATS 须配 ACL。
// 解耦：signaling 不 import laser（领域包），只解路由所需 owner_user_id，其余 payload 原样转发。
package signaling

import (
	"encoding/json"
	"log/slog"
	"sync"

	"github.com/nats-io/nats.go"
)

// 与 laser.TopicLaserPoints / TopicLaserStatus 同名；故意不跨包引用，避免传输层耦合领域层。
const (
	topicLaserPoints = "laser.points"
	topicLaserStatus = "laser.status"
	topicLaserFrame  = "laser.frame" // 实时取景标定：相机 RGB 预览帧 + ArUco 检测
)

// 推给端侧的事件帧类型（S→C）。
const (
	EnvelopeTypeLaserPoints = "laser.points"
	EnvelopeTypeLaserStatus = "laser.status"
	EnvelopeTypeLaserFrame  = "laser.frame"
)

// laserRoute 仅解路由所需字段；payload 其余（unit/points/state/...）原样转发。
type laserRoute struct {
	OwnerUserID *int64 `json:"owner_user_id"`
	SessionKey  string `json:"session_key"` // 仅日志链路追踪
}

// LaserBridge 持有两个订阅句柄，Close() 全部退订。
type LaserBridge struct {
	hub       *Hub
	log       *slog.Logger
	subs      []*nats.Subscription
	closeOnce sync.Once
}

// StartLaserBridge 订阅 laser.points + laser.status 并启动桥接。
func StartLaserBridge(nc *nats.Conn, hub *Hub, log *slog.Logger) (*LaserBridge, error) {
	if log == nil {
		log = slog.Default()
	}
	b := &LaserBridge{hub: hub, log: log}
	for _, s := range []struct {
		subject string
		envType string
	}{
		{topicLaserPoints, EnvelopeTypeLaserPoints},
		{topicLaserStatus, EnvelopeTypeLaserStatus},
		{topicLaserFrame, EnvelopeTypeLaserFrame},
	} {
		envType := s.envType
		sub, err := nc.Subscribe(s.subject, func(msg *nats.Msg) {
			b.handle(envType, msg.Data)
		})
		if err != nil {
			b.Close()
			return nil, err
		}
		b.subs = append(b.subs, sub)
	}
	log.Info("laser.points/status → ws 桥接已启动")
	return b, nil
}

// handle 解析事件并推给 owner；返回投递到的连接数。owner 为空只记日志不推。
// 导出供单测直接喂原始 JSON，绕过 NATS。
func (b *LaserBridge) handle(envType string, raw []byte) int {
	var route laserRoute
	if err := json.Unmarshal(raw, &route); err != nil {
		b.log.Warn("laser 事件解析失败", "type", envType, "err", err)
		return 0
	}
	if route.OwnerUserID == nil {
		// 无 owner（harness / 无鉴权直发）：合法，跳过实时推送。
		return 0
	}
	// 防御性拷贝：nats 回调的 msg.Data 在回调返回后被库复用；Envelope 走异步 send chan。
	payload := append(json.RawMessage(nil), raw...)
	return b.hub.Push(*route.OwnerUserID, Envelope{Type: envType, Payload: payload})
}

// Close 退订（幂等）。
func (b *LaserBridge) Close() {
	if b == nil {
		return
	}
	b.closeOnce.Do(func() {
		for _, s := range b.subs {
			if s != nil {
				_ = s.Unsubscribe()
			}
		}
	})
}
