// LLM 调用配额（M-S11.6）。
//
// 当前阶段提供"按用户 / 按模板"的日级预算（key 含日期；UTC 时区）：
//
//	llm:quota:user:{uid}:{YYYYMMDD}   一个用户当日累计调用次数
//	llm:quota:tpl:{tplid}:{YYYYMMDD}  一个模板当日累计调用次数
//
// 阈值来源：env GOMOB_LLM_USER_DAILY_BUDGET / GOMOB_LLM_TPL_DAILY_BUDGET（默认 0=不限）。
// 后续可加"模板自带 budget 列"覆盖全局；本接口契约不变。
//
// 决策语义：CheckAndIncr 先 INCR 再判定。INCR 后值 > budget 即拒绝（自减回滚）。
// 这样并发场景下不会"放过 budget+1 个"（INCR 是原子的）。
package llmgateway

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrQuotaExceeded 当前调用使配额超出。handler 转 40602。
var ErrQuotaExceeded = errors.New("llm: quota exceeded")

// QuotaChecker Redis 计数器；budget=0 表示该维度不限。
type QuotaChecker struct {
	rdb         *redis.Client
	userBudget  int
	tplBudget   int
	keyPrefix   string
	keyTTL      time.Duration
	log         *slog.Logger
}

// NewQuotaChecker rdb 为 nil 时所有调用直接 allow（dev / 单机无 redis 时降级）。
func NewQuotaChecker(rdb *redis.Client, userBudget, tplBudget int, log *slog.Logger) *QuotaChecker {
	if log == nil {
		log = slog.Default()
	}
	return &QuotaChecker{
		rdb:        rdb,
		userBudget: userBudget,
		tplBudget:  tplBudget,
		keyPrefix:  "llm:quota",
		keyTTL:     48 * time.Hour, // 跨日宽容 1 天，过期自动清
		log:        log,
	}
}

// Disabled 当 rdb 为 nil 或所有 budget 都为 0 时算关闭。
func (q *QuotaChecker) Disabled() bool {
	return q == nil || q.rdb == nil || (q.userBudget <= 0 && q.tplBudget <= 0)
}

// Counts 已计数（用于 audit log / 响应 header）。
type Counts struct {
	UserCurrent int // INCR 后用户当日累计
	TplCurrent  int // INCR 后模板当日累计
	UserBudget  int
	TplBudget   int
}

// CheckAndIncr 决策 + 计数。
//
//	rules:
//	- userBudget > 0：incr user key；若 INCR 后值 > budget → 自减 + ErrQuotaExceeded
//	- tplBudget > 0：incr tpl key；同上
//	- 任一维度超限 → 拒；先返用户超限，再返模板超限
//	- 两维度均不超 → allow，返回当前 counts
//
// 任意一边 INCR 失败（redis 故障）时按"宁宽勿严"放行 + 日志告警（避免 redis 故障阻塞业务）。
func (q *QuotaChecker) CheckAndIncr(ctx context.Context, uid int64, tplID int64) (Counts, error) {
	c := Counts{UserBudget: q.userBudget, TplBudget: q.tplBudget}
	if q.Disabled() {
		return c, nil
	}
	day := time.Now().UTC().Format("20060102")

	// 1. user budget
	if q.userBudget > 0 && uid > 0 {
		ukey := fmt.Sprintf("%s:user:%d:%s", q.keyPrefix, uid, day)
		v, err := q.rdb.Incr(ctx, ukey).Result()
		if err != nil {
			q.log.Warn("redis INCR user 失败，按放行处理", "err", err, "key", ukey)
		} else {
			c.UserCurrent = int(v)
			if v == 1 {
				_ = q.rdb.Expire(ctx, ukey, q.keyTTL).Err()
			}
			if int(v) > q.userBudget {
				_ = q.rdb.Decr(ctx, ukey).Err()
				c.UserCurrent--
				return c, fmt.Errorf("%w: user uid=%d daily=%d budget=%d",
					ErrQuotaExceeded, uid, c.UserCurrent, q.userBudget)
			}
		}
	}

	// 2. tpl budget
	if q.tplBudget > 0 && tplID > 0 {
		tkey := fmt.Sprintf("%s:tpl:%d:%s", q.keyPrefix, tplID, day)
		v, err := q.rdb.Incr(ctx, tkey).Result()
		if err != nil {
			q.log.Warn("redis INCR tpl 失败，按放行处理", "err", err, "key", tkey)
		} else {
			c.TplCurrent = int(v)
			if v == 1 {
				_ = q.rdb.Expire(ctx, tkey, q.keyTTL).Err()
			}
			if int(v) > q.tplBudget {
				_ = q.rdb.Decr(ctx, tkey).Err()
				c.TplCurrent--
				// 用户那边已 incr 成功，回滚一下保持账面一致
				if q.userBudget > 0 && uid > 0 {
					ukey := fmt.Sprintf("%s:user:%d:%s", q.keyPrefix, uid, day)
					_ = q.rdb.Decr(ctx, ukey).Err()
					c.UserCurrent--
				}
				return c, fmt.Errorf("%w: tpl id=%d daily=%d budget=%d",
					ErrQuotaExceeded, tplID, c.TplCurrent, q.tplBudget)
			}
		}
	}

	return c, nil
}
