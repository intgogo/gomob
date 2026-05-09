package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

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

func (h *Handler) ListHelpExperts(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}

	out := make([]helpExpertDTO, 0, len(fixedHelpExperts))
	for _, def := range fixedHelpExperts {
		var userID int64
		var name, employeeID string
		err := h.pool.QueryRow(r.Context(), `
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
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		out = append(out, helpExpertDTO{
			UserID:       strconv.FormatInt(userID, 10),
			Name:         name,
			EmployeeID:   employeeID,
			RoleTitle:    def.RoleTitle,
			Specialty:    def.Specialty,
			Availability: "message_ready",
		})
	}

	httpx.OK(w, map[string]any{"items": out})
}

type openP2PConversationReq struct {
	PeerUserID string `json:"peer_user_id"`
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
	peerID, err := strconv.ParseInt(req.PeerUserID, 10, 64)
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
