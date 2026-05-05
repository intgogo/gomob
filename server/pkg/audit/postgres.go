package audit

import (
	"context"
	"net"

	"github.com/jackc/pgx/v5/pgxpool"
)

// PG 把 Entry 写到 audit_log 表（migration 0001）。
type PG struct {
	pool *pgxpool.Pool
}

func NewPG(pool *pgxpool.Pool) *PG { return &PG{pool: pool} }

func (p *PG) Record(ctx context.Context, e Entry) error {
	const q = `
		INSERT INTO audit_log (user_id, action, target, before, after, ip)
		VALUES ($1, $2, $3, NULLIF($4,'')::jsonb, NULLIF($5,'')::jsonb, $6)`
	var ip *net.IP
	if e.IP != "" {
		// 处理 "host:port" 形式
		host := e.IP
		if idx := lastColon(host); idx > 0 && idx < len(host)-1 {
			host = host[:idx]
		}
		parsed := net.ParseIP(host)
		if parsed != nil {
			ip = &parsed
		}
	}
	_, err := p.pool.Exec(ctx, q,
		nilIfZero(e.UserID), e.Action, e.Target, e.BeforeRaw, e.AfterRaw, ip,
	)
	return err
}

func nilIfZero(id int64) any {
	if id == 0 {
		return nil
	}
	return id
}

// lastColon 取字符串最后一个冒号位置（处理 IPv6 时只看最后一个 :）。
func lastColon(s string) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}
