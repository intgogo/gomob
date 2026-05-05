package repo

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// LLMTemplate — 模板，版本化。
//
// 状态机（详见 02-api-contract.md §15）：
//
//	draft  ── activate ──▶ active
//	   │                     │
//	   └─ archive ───────▶ archived ◀── archive ───┘
type LLMTemplate struct {
	ID                int64
	Name              string
	Version           int32
	PreferredProvider string
	PreferredModel    *string
	SystemPrompt      string
	UserTemplate      string
	VarsSchema        json.RawMessage
	Status            string
	CreatedAt         time.Time
	UpdatedAt         time.Time
}

var llmTransitions = map[string][]string{
	"draft":    {"active", "archived"},
	"active":   {"archived"},
	"archived": nil,
}

func IsLLMTransitionAllowed(from, to string) bool {
	for _, t := range llmTransitions[from] {
		if t == to {
			return true
		}
	}
	return false
}

type LLMTemplateRepo struct {
	pool *pgxpool.Pool
}

func NewLLMTemplateRepo(pool *pgxpool.Pool) *LLMTemplateRepo {
	return &LLMTemplateRepo{pool: pool}
}

// Create 写入新版本（status=draft）。同 (name,version) 冲突 → ErrConflict。
//
// PreferredProvider 留空表示"由 registry 默认决定"——这样开了 fallback chain 时
// 模板会自动享受 fallback；显式指定的 provider 名仍 honor 优先（用 Pick 单走）。
func (r *LLMTemplateRepo) Create(ctx context.Context, t *LLMTemplate) error {
	if len(t.VarsSchema) == 0 {
		t.VarsSchema = []byte("{}")
	}
	const q = `
		INSERT INTO llm_templates (name, version, preferred_provider, preferred_model,
		                           system_prompt, user_template, vars_schema, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'draft')
		RETURNING id, status, created_at, updated_at`
	err := r.pool.QueryRow(ctx, q,
		t.Name, t.Version, t.PreferredProvider, t.PreferredModel,
		t.SystemPrompt, t.UserTemplate, t.VarsSchema,
	).Scan(&t.ID, &t.Status, &t.CreatedAt, &t.UpdatedAt)
	if err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok && strings.Contains(pgErr.ConstraintName, "llm_templates_name_version") {
			return ErrConflict
		}
		return err
	}
	return nil
}

func (r *LLMTemplateRepo) FindByID(ctx context.Context, id int64) (*LLMTemplate, error) {
	const q = `
		SELECT id, name, version, preferred_provider, preferred_model, system_prompt,
		       user_template, vars_schema, status, created_at, updated_at
		FROM llm_templates WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	t := &LLMTemplate{}
	if err := row.Scan(&t.ID, &t.Name, &t.Version, &t.PreferredProvider, &t.PreferredModel,
		&t.SystemPrompt, &t.UserTemplate, &t.VarsSchema, &t.Status, &t.CreatedAt, &t.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return t, nil
}

// FindActive 按 name 拿当前 active 版本（应只有 1 条；多条时取 version 最大）。
func (r *LLMTemplateRepo) FindActive(ctx context.Context, name string) (*LLMTemplate, error) {
	const q = `
		SELECT id, name, version, preferred_provider, preferred_model, system_prompt,
		       user_template, vars_schema, status, created_at, updated_at
		FROM llm_templates
		WHERE name = $1 AND status = 'active'
		ORDER BY version DESC LIMIT 1`
	row := r.pool.QueryRow(ctx, q, name)
	t := &LLMTemplate{}
	if err := row.Scan(&t.ID, &t.Name, &t.Version, &t.PreferredProvider, &t.PreferredModel,
		&t.SystemPrompt, &t.UserTemplate, &t.VarsSchema, &t.Status, &t.CreatedAt, &t.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return t, nil
}

// ListActive 列出所有 active 模板（每个 name 取 version 最大）。
func (r *LLMTemplateRepo) ListActive(ctx context.Context) ([]LLMTemplate, error) {
	const q = `
		SELECT DISTINCT ON (name) id, name, version, preferred_provider, preferred_model,
		       system_prompt, user_template, vars_schema, status, created_at, updated_at
		FROM llm_templates WHERE status = 'active'
		ORDER BY name, version DESC`
	rows, err := r.pool.Query(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []LLMTemplate
	for rows.Next() {
		var t LLMTemplate
		if err := rows.Scan(&t.ID, &t.Name, &t.Version, &t.PreferredProvider, &t.PreferredModel,
			&t.SystemPrompt, &t.UserTemplate, &t.VarsSchema, &t.Status, &t.CreatedAt, &t.UpdatedAt); err != nil {
			return nil, err
		}
		items = append(items, t)
	}
	return items, rows.Err()
}

// Activate：把指定 (name, version) 设 active；同 name 的其它 active 自动归档（"上架即下架旧版"）。
//
// 用事务保证原子。
func (r *LLMTemplateRepo) Activate(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var name string
	var status string
	if err := tx.QueryRow(ctx, `SELECT name, status FROM llm_templates WHERE id=$1`, id).Scan(&name, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" && status != "active" {
		// archived 不能 activate；active 重复 activate 视为 OK
		return ErrStateConflict
	}
	// 同 name 其它 active → archived
	if _, err := tx.Exec(ctx,
		`UPDATE llm_templates SET status='archived' WHERE name=$1 AND status='active' AND id<>$2`,
		name, id); err != nil {
		return err
	}
	// 当前 → active
	if _, err := tx.Exec(ctx,
		`UPDATE llm_templates SET status='active' WHERE id=$1 AND status IN ('draft','active')`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r *LLMTemplateRepo) Archive(ctx context.Context, id int64) error {
	const q = `UPDATE llm_templates SET status='archived' WHERE id=$1 AND status IN ('draft','active')`
	tag, err := r.pool.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM llm_templates WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// LLMCallLog — 调用审计。
type LLMCallLog struct {
	ID           int64
	UserID       *int64
	TemplateID   *int64
	TemplateName *string
	TemplateVer  *int32
	Provider     string
	Model        *string
	TokenIn      int32
	TokenOut     int32
	LatencyMS    int32
	Status       string // ok / error / cancelled
	Error        *string
	RequestID    *string
	CreatedAt    time.Time
}

type LLMCallLogRepo struct {
	pool *pgxpool.Pool
}

func NewLLMCallLogRepo(pool *pgxpool.Pool) *LLMCallLogRepo {
	return &LLMCallLogRepo{pool: pool}
}

func (r *LLMCallLogRepo) Insert(ctx context.Context, l *LLMCallLog) error {
	const q = `
		INSERT INTO llm_call_logs (user_id, template_id, template_name, template_ver,
		                           provider, model, token_in, token_out, latency_ms,
		                           status, error, request_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id, created_at`
	return r.pool.QueryRow(ctx, q,
		l.UserID, l.TemplateID, l.TemplateName, l.TemplateVer,
		l.Provider, l.Model, l.TokenIn, l.TokenOut, l.LatencyMS,
		l.Status, l.Error, l.RequestID,
	).Scan(&l.ID, &l.CreatedAt)
}
