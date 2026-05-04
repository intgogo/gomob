// auth 服务的 HTTP 处理器。绑定到 /v1/auth/...
package auth

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
	"io.gomob/server/pkg/token"
)

type Handler struct {
	users *repo.UserRepo
	pool  *pgxpool.Pool
	// devAutoActivate=true 时注册立即激活（便于本地开发跳过审核环节）。
	devAutoActivate bool
}

func NewHandler(pool *pgxpool.Pool, devAutoActivate bool) *Handler {
	return &Handler{
		users:           repo.NewUserRepo(pool),
		pool:            pool,
		devAutoActivate: devAutoActivate,
	}
}

func (h *Handler) Mount(mux interface {
	HandleFunc(string, http.HandlerFunc)
}) {
	mux.HandleFunc("POST /v1/auth/register", h.Register)
	mux.HandleFunc("POST /v1/auth/login", h.Login)
	mux.HandleFunc("POST /v1/auth/refresh", h.Refresh)
	mux.HandleFunc("POST /v1/auth/password", h.ChangePassword)
	mux.HandleFunc("GET /v1/me", h.Me)
}

// ---------- Register ----------

type registerReq struct {
	Username        string `json:"username"`
	Password        string `json:"password"`
	RealName        string `json:"real_name"`
	EmployeeID      string `json:"employee_id"`
	StationNameHint string `json:"station_name_hint"`
	Note            string `json:"note"`
}

type registerResp struct {
	UserID  string `json:"user_id"`
	Status  string `json:"status"`
	Message string `json:"message"`
}

func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req registerReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	req.RealName = strings.TrimSpace(req.RealName)
	req.EmployeeID = strings.TrimSpace(req.EmployeeID)
	if req.Username == "" || req.Password == "" || req.RealName == "" || req.EmployeeID == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if len(req.Password) < 6 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), 12)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	status := "pending"
	if h.devAutoActivate {
		status = "active"
	}

	var notePtr *string
	if req.Note != "" {
		notePtr = &req.Note
	}
	u := &repo.User{
		Username:     req.Username,
		RealName:     req.RealName,
		EmployeeID:   req.EmployeeID,
		PasswordHash: string(hash),
		Role:         "inspector",
		Status:       status,
		Note:         notePtr,
	}

	if err := h.users.Create(r.Context(), u); err != nil {
		if errors.Is(err, repo.ErrConflict) {
			if strings.Contains(err.Error(), "username") {
				httpx.WriteError(w, httpx.ErrUserExists)
				return
			}
			if strings.Contains(err.Error(), "employee_id") {
				httpx.WriteError(w, httpx.ErrEmployeeExists)
				return
			}
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	if h.devAutoActivate {
		// 立即激活（下面 SQL 已 set 'active'，再补 activated_at）
		_, _ = h.pool.Exec(r.Context(),
			`UPDATE users SET activated_at=now() WHERE id=$1`, u.ID)
	}

	msg := "提交成功，请等待后台审核通过"
	if h.devAutoActivate {
		msg = "注册成功，可立即登录（DEV 模式自动激活）"
	}
	httpx.OK(w, registerResp{
		UserID:  strconv.FormatInt(u.ID, 10),
		Status:  status,
		Message: msg,
	})
}

// ---------- Login ----------

type loginReq struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type stationDTO struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type userDTO struct {
	ID         string      `json:"id"`
	Username   string      `json:"username"`
	RealName   string      `json:"real_name"`
	EmployeeID string      `json:"employee_id"`
	Role       string      `json:"role"`
	Station    *stationDTO `json:"station,omitempty"`
}

type loginResp struct {
	AccessToken  string  `json:"access_token"`
	RefreshToken string  `json:"refresh_token"`
	ExpiresIn    int64   `json:"expires_in"`
	User         userDTO `json:"user"`
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Username == "" || req.Password == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	u, err := h.users.FindByUsername(r.Context(), req.Username)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrLoginFailed)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(req.Password)); err != nil {
		httpx.WriteError(w, httpx.ErrLoginFailed)
		return
	}
	if u.Status != "active" {
		httpx.WriteError(w, httpx.ErrAccountInactive)
		return
	}

	access, err := token.IssueAccess(u.ID, u.Role)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	refresh, err := token.IssueRefresh(u.ID, u.Role)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, loginResp{
		AccessToken:  access,
		RefreshToken: refresh,
		ExpiresIn:    int64(token.AccessTTL.Seconds()),
		User:         userToDTO(r.Context(), h.users, u),
	})
}

// ---------- Refresh ----------

type refreshReq struct {
	RefreshToken string `json:"refresh_token"`
}

type refreshResp struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
}

func (h *Handler) Refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	c, err := token.Parse(req.RefreshToken)
	if err != nil || c.Kind != "refresh" {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	access, _ := token.IssueAccess(c.UserID, c.Role)
	refresh, _ := token.IssueRefresh(c.UserID, c.Role)
	httpx.OK(w, refreshResp{
		AccessToken:  access,
		RefreshToken: refresh,
		ExpiresIn:    int64(token.AccessTTL.Seconds()),
	})
}

// ---------- ChangePassword ----------

type changePasswordReq struct {
	OldPassword string `json:"old_password"`
	NewPassword string `json:"new_password"`
}

func (h *Handler) ChangePassword(w http.ResponseWriter, r *http.Request) {
	uid, ok := UserIDFromCtx(r.Context())
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req changePasswordReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if len(req.NewPassword) < 6 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}

	u, err := h.users.FindByID(r.Context(), uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(req.OldPassword)); err != nil {
		httpx.WriteError(w, httpx.ErrLoginFailed)
		return
	}
	hash, _ := bcrypt.GenerateFromPassword([]byte(req.NewPassword), 12)
	if err := h.users.UpdatePassword(r.Context(), uid, string(hash)); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, nil)
}

// ---------- Me ----------

func (h *Handler) Me(w http.ResponseWriter, r *http.Request) {
	uid, ok := UserIDFromCtx(r.Context())
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	u, err := h.users.FindByID(r.Context(), uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}
	httpx.OK(w, userToDTO(r.Context(), h.users, u))
}

// ---------- 工具 ----------

func userToDTO(ctx context.Context, users *repo.UserRepo, u *repo.User) userDTO {
	dto := userDTO{
		ID:         strconv.FormatInt(u.ID, 10),
		Username:   u.Username,
		RealName:   u.RealName,
		EmployeeID: u.EmployeeID,
		Role:       u.Role,
	}
	if u.StationID != nil {
		s, err := users.FindStationByID(ctx, *u.StationID)
		if err == nil {
			dto.Station = &stationDTO{
				ID:   strconv.FormatInt(s.ID, 10),
				Name: s.Name,
			}
		}
	}
	return dto
}
