// FusionBridge —— 把 NATS scan.fusion_done 事件桥接到 ws,实时通知端侧 gallery。
//
// 拓扑:fusionworker(独立进程)完成多视角融合后发 NATS scan.fusion_done;
// signaling 进程订阅该 topic,按事件里的 owner_user_id 把事件推给该用户的所有在线 ws 连接。
// 端侧 gallery 收到后据 result_object_key 拉 GLB 回看(M3.15)。
//
// 解耦:signaling 是实时传输层,不 import fusion(领域 worker)包;只解出路由所需的
// owner_user_id,其余 payload 原样转发。topic 名以
// docs/architecture/registry/server-dependencies.yaml nats_topics 段为真理源。
//
// 信任边界:与本系统所有内部 NATS 事件一致,本桥信任 scan.fusion_done 来自可信发布者
// (fusionworker)。事件里的 owner_user_id 由 fusionworker 从 DB(asset 上传完成时按已鉴权
// 上传者写入,带 users FK 约束)读出回发,非客户端可控;能伪造该事件的前提是已具备 NATS 写权限,
// 即已越过内部信任边界。生产 NATS 必须配 ACL/认证限制发布者(基础设施层职责,非本 topic 单独加签)。
package signaling

import (
	"encoding/json"
	"log/slog"
	"sync"

	"github.com/nats-io/nats.go"
)

// topicFusionDone 与 fusion.TopicFusionDone 同名;故意不跨包引用,避免传输层耦合领域层。
const topicFusionDone = "scan.fusion_done"

// EnvelopeTypeFusionDone 推给端侧的事件帧类型(S→C)。
const EnvelopeTypeFusionDone = "scan.fusion_done"

// fusionDoneRoute 仅解路由与日志所需字段;payload 其余字段(result_object_key/vertices/...)原样转发。
type fusionDoneRoute struct {
	OwnerUserID *int64 `json:"owner_user_id"`
	SessionKey  string `json:"session_key"` // 仅用于日志链路追踪
}

// FusionBridge 持有订阅句柄,Close() 退订。
type FusionBridge struct {
	hub       *Hub
	log       *slog.Logger
	sub       *nats.Subscription
	closeOnce sync.Once
}

// StartFusionBridge 在给定 nats 连接上订阅 scan.fusion_done 并启动桥接。
func StartFusionBridge(nc *nats.Conn, hub *Hub, log *slog.Logger) (*FusionBridge, error) {
	if log == nil {
		log = slog.Default()
	}
	b := &FusionBridge{hub: hub, log: log}
	sub, err := nc.Subscribe(topicFusionDone, func(msg *nats.Msg) {
		b.handle(msg.Data)
	})
	if err != nil {
		return nil, err
	}
	b.sub = sub
	log.Info("scan.fusion_done → ws 桥接已启动")
	return b, nil
}

// handle 解析事件并推给 owner;返回投递到的连接数。
// owner 为空(harness / inspection-less 直接入队的任务)只记日志不推,端侧仍可轮询。
// 导出供单测直接喂原始 JSON,绕过 NATS。
func (b *FusionBridge) handle(raw []byte) int {
	var route fusionDoneRoute
	if err := json.Unmarshal(raw, &route); err != nil {
		b.log.Warn("scan.fusion_done 解析失败", "err", err)
		return 0
	}
	if route.OwnerUserID == nil {
		// inspection-less / harness 直接入队的任务无鉴权用户,合法场景,只跳过实时推送(端侧可轮询)。
		b.log.Debug("scan.fusion_done 无 owner,跳过实时推送(端侧可轮询)", "session", route.SessionKey)
		return 0
	}
	// CRITICAL:防御性拷贝不可删除/延迟。nats 回调的 msg.Data 缓冲在回调返回后被库复用;
	// Envelope 走异步 send chan,writeLoop 读取时若仍引用原 raw 会读到被后续消息覆写的脏数据。
	payload := append(json.RawMessage(nil), raw...)
	delivered := b.hub.Push(*route.OwnerUserID, Envelope{
		Type:    EnvelopeTypeFusionDone,
		Payload: payload,
	})
	b.log.Info("scan.fusion_done 已推送", "owner", *route.OwnerUserID, "session", route.SessionKey, "delivered", delivered)
	return delivered
}

// Close 退订(幂等;sync.Once 防多次 Unsubscribe)。
func (b *FusionBridge) Close() {
	if b == nil {
		return
	}
	b.closeOnce.Do(func() {
		if b.sub != nil {
			_ = b.sub.Unsubscribe()
		}
	})
}
