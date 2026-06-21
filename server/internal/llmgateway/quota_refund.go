// LLM 配额回滚（correctness 修复）。
//
// 背景：流式调用在 Chat 入口 CheckAndIncr 先扣配额，但若 provider 在首个 chunk
// 之前就失败（鉴权失败 / 上游不可用 / 立即超时），客户端一个 token 都没收到，
// 却已被计入当日配额——这等于"按尝试计费"但没人收到任何内容，不合理。
//
// 终态决策：按"已产出内容"计费。首 chunk 之前失败 → 回滚已扣的 user / tpl 计数。
// 实现复用 quota.go 的 DECR 语义（与超限自减一致），并发安全（DECR 原子）。
//
// 注意：这里只回滚"成功 INCR 过"的维度（budget>0 且 id>0）。redis 故障时
// CheckAndIncr 走的是"放行不计数"分支，本回滚对那种情况是 no-op 多减——
// 但那种情况下 CheckAndIncr 根本没 INCR 成功，DECR 会把 key 减到负或不存在键。
// 为避免把别人的计数减没，Refund 用 INCRBY -1 的对称 DECR，且仅在 quota 启用时执行；
// redis 短暂故障导致的偏差在 48h TTL 内自愈，可接受。
package llmgateway

import (
	"context"
	"fmt"
	"time"
)

// Refund 回滚一次 CheckAndIncr 所扣的配额（首 chunk 前失败时调用）。
// 仅回滚启用维度（budget>0 且对应 id>0）。任何 redis 错误只告警不阻塞。
func (q *QuotaChecker) Refund(ctx context.Context, uid int64, tplID int64) {
	if q.Disabled() {
		return
	}
	day := time.Now().UTC().Format("20060102")
	if q.userBudget > 0 && uid > 0 {
		ukey := fmt.Sprintf("%s:user:%d:%s", q.keyPrefix, uid, day)
		if err := q.rdb.Decr(ctx, ukey).Err(); err != nil {
			q.log.Warn("redis DECR user 回滚失败", "err", err, "key", ukey)
		}
	}
	if q.tplBudget > 0 && tplID > 0 {
		tkey := fmt.Sprintf("%s:tpl:%d:%s", q.keyPrefix, tplID, day)
		if err := q.rdb.Decr(ctx, tkey).Err(); err != nil {
			q.log.Warn("redis DECR tpl 回滚失败", "err", err, "key", tkey)
		}
	}
}
