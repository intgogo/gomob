package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
)

type helpExpertDefinition struct {
	EmployeeID string
	RoleTitle  string
	Specialty  string
}

var fixedHelpExperts = []helpExpertDefinition{
	{
		EmployeeID: "EXP-VIN-0001",
		RoleTitle:  "VIN 拓印专家",
		Specialty:  "VIN 字符复核、拓印异常、铭牌疑难",
	},
	{
		EmployeeID: "EXP-3D-0002",
		RoleTitle:  "三维外廓专家",
		Specialty:  "3D 外廓、尺寸复核、点云质量判断",
	},
	{
		EmployeeID: "EXP-DEV-0003",
		RoleTitle:  "设备链路专家",
		Specialty:  "深度相机、采集链路、现场设备排障",
	},
	{
		EmployeeID: "EXP-REG-0004",
		RoleTitle:  "监管会审专家",
		Specialty:  "异常查验、监管流程、复核会审",
	},
}

type helpExpertDTO struct {
	UserID       string `json:"user_id"`
	Name         string `json:"name"`
	EmployeeID   string `json:"employee_id"`
	RoleTitle    string `json:"role_title"`
	Specialty    string `json:"specialty"`
	Availability string `json:"availability"`
}

type helpExpertCaseDTO struct {
	ID          string `json:"id"`
	AuthorID    string `json:"author_id"`
	Title       string `json:"title"`
	Summary     string `json:"summary"`
	Category    string `json:"category"`
	PublishedAt string `json:"published_at"`
}

func (h *Handler) ListHelpExperts(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}

	out, _, err := h.activeHelpExperts(r.Context())
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, map[string]any{"items": out})
}

func (h *Handler) ListHelpExpertCases(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	expertID, err := parsePathID(r, "id")
	if err != nil || expertID <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	active, err := h.isActiveHelpExpert(r.Context(), expertID)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !active {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}

	rows, err := h.pool.Query(r.Context(), `
		SELECT id, author_id, title, summary, category, published_at
		FROM help_expert_cases
		WHERE author_id = $1 AND status = 'published'
		ORDER BY published_at DESC, id DESC
		LIMIT 20`,
		expertID,
	)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	defer rows.Close()

	out := make([]helpExpertCaseDTO, 0, 8)
	for rows.Next() {
		var item helpExpertCaseDTO
		var id, authorID int64
		var publishedAt time.Time
		if err := rows.Scan(&id, &authorID, &item.Title, &item.Summary, &item.Category, &publishedAt); err != nil {
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		item.ID = strconv.FormatInt(id, 10)
		item.AuthorID = strconv.FormatInt(authorID, 10)
		item.PublishedAt = formatTime(publishedAt)
		out = append(out, item)
	}
	if err := rows.Err(); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, map[string]any{"items": out})
}

func (h *Handler) activeHelpExperts(ctx context.Context) ([]helpExpertDTO, []int64, error) {
	out := make([]helpExpertDTO, 0, len(fixedHelpExperts))
	ids := make([]int64, 0, len(fixedHelpExperts))
	for _, def := range fixedHelpExperts {
		var userID int64
		var name, employeeID string
		err := h.pool.QueryRow(ctx, `
			SELECT id, real_name, employee_id
			FROM users
			WHERE employee_id = $1 AND status = 'active'
			LIMIT 1`,
			def.EmployeeID,
		).Scan(&userID, &name, &employeeID)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				continue
			}
			return nil, nil, err
		}
		ids = append(ids, userID)
		out = append(out, helpExpertDTO{
			UserID:       strconv.FormatInt(userID, 10),
			Name:         name,
			EmployeeID:   employeeID,
			RoleTitle:    def.RoleTitle,
			Specialty:    def.Specialty,
			Availability: "message_ready",
		})
	}
	return out, ids, nil
}

func (h *Handler) isActiveHelpExpert(ctx context.Context, userID int64) (bool, error) {
	_, ids, err := h.activeHelpExperts(ctx)
	if err != nil {
		return false, err
	}
	for _, id := range ids {
		if id == userID {
			return true, nil
		}
	}
	return false, nil
}

type openP2PConversationReq struct {
	PeerUserID     string `json:"peer_user_id"`
	PeerEmployeeID string `json:"peer_employee_id"`
}

func (h *Handler) OpenP2PConversation(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if !h.enforcer.Allow(callerRole(r), "message", "send") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}

	var req openP2PConversationReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	peerID, err := h.resolvePeerUserID(r.Context(), req.PeerUserID, req.PeerEmployeeID)
	if err != nil || peerID <= 0 || peerID == uid {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	var peerActive bool
	if err := h.pool.QueryRow(r.Context(),
		`SELECT EXISTS(SELECT 1 FROM users WHERE id = $1 AND status = 'active')`,
		peerID,
	).Scan(&peerActive); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !peerActive {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}

	conv, err := h.conversations.GetOrCreateP2P(r.Context(), uid, peerID)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	summary, err := h.conversations.FindForUser(r.Context(), uid, conv.ID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, toConversationDTO(summary))
}

func (h *Handler) resolvePeerUserID(ctx context.Context, rawUserID, rawEmployeeID string) (int64, error) {
	if trimmed := strings.TrimSpace(rawUserID); trimmed != "" {
		return strconv.ParseInt(trimmed, 10, 64)
	}
	employeeID := strings.TrimSpace(rawEmployeeID)
	if employeeID == "" {
		return 0, httpx.ErrBadParam
	}
	var peerID int64
	err := h.pool.QueryRow(ctx,
		`SELECT id FROM users WHERE employee_id = $1 AND status = 'active' LIMIT 1`,
		employeeID,
	).Scan(&peerID)
	if err != nil {
		return 0, err
	}
	return peerID, nil
}

func (h *Handler) OpenHelpRoom(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if !h.enforcer.Allow(callerRole(r), "message", "send") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	// 求助群语义：inspector 求助、expert 应答。expert 自己调没意义且会按 subject_id=uid 建独立群。
	if callerRole(r) != "inspector" {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}

	_, expertIDs, err := h.activeHelpExperts(r.Context())
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if len(expertIDs) == 0 {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}

	members := append([]int64{uid}, expertIDs...)
	conv, err := h.conversations.GetOrCreateSubjectGroup(
		r.Context(),
		"在线求助",
		"online_help",
		uid,
		members,
	)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	summary, err := h.conversations.FindForUser(r.Context(), uid, conv.ID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, toConversationDTO(summary))
}
