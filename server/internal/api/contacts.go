package api

import (
	"database/sql"
	"net/http"
	"strconv"
	"strings"

	"io.gomob/server/pkg/httpx"
)

// 通讯录 DTO — 暴露给客户端的"站内可联系人"基本信息。
// 不返回 password_hash / status 等敏感字段；station_name 是 LEFT JOIN 后的展示名。
type contactDTO struct {
	UserID      string `json:"user_id"`
	Name        string `json:"name"`
	EmployeeID  string `json:"employee_id"`
	Username    string `json:"username,omitempty"`
	Role        string `json:"role"`
	StationID   string `json:"station_id,omitempty"`
	StationName string `json:"station_name,omitempty"`
}

// ListContacts 返回当前用户可见的通讯录：
//   - 默认：同 station_id 的活跃用户（不含自己）；自己没绑定 station 时退化为全站所有活跃用户。
//   - ?role=inspector/supervisor/reviewer/admin 过滤角色；?q=xxx 模糊匹配 real_name / employee_id / username。
//   - 上限 200 条，超过截断（产品体量 MVP，后续按需做分页）。
//
// 角色权限：任何登录用户都能调；列表只暴露活跃成员，不会泄露 pending / disabled。
func (h *Handler) ListContacts(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	roleFilter := strings.TrimSpace(r.URL.Query().Get("role"))

	var callerStation sql.NullInt64
	if err := h.pool.QueryRow(r.Context(),
		`SELECT station_id FROM users WHERE id=$1`, uid).Scan(&callerStation); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	args := []any{uid}
	conds := []string{"u.id <> $1", "u.status = 'active'"}
	if callerStation.Valid {
		args = append(args, callerStation.Int64)
		conds = append(conds, "u.station_id = $"+strconv.Itoa(len(args)))
	}
	if roleFilter != "" {
		args = append(args, roleFilter)
		conds = append(conds, "u.role = $"+strconv.Itoa(len(args)))
	}
	if q != "" {
		args = append(args, "%"+q+"%")
		idx := strconv.Itoa(len(args))
		conds = append(conds, "(u.real_name ILIKE $"+idx+" OR u.employee_id ILIKE $"+idx+" OR u.username ILIKE $"+idx+")")
	}

	sqlQ := `
		SELECT u.id, u.real_name, u.employee_id, u.username, u.role, u.station_id, s.name
		FROM users u
		LEFT JOIN stations s ON s.id = u.station_id
		WHERE ` + strings.Join(conds, " AND ") + `
		ORDER BY u.role, u.real_name, u.id
		LIMIT 200`
	rows, err := h.pool.Query(r.Context(), sqlQ, args...)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	defer rows.Close()

	out := make([]contactDTO, 0, 32)
	for rows.Next() {
		var (
			id          int64
			realName    string
			employeeID  string
			username    string
			role        string
			stationID   sql.NullInt64
			stationName sql.NullString
		)
		if err := rows.Scan(&id, &realName, &employeeID, &username, &role, &stationID, &stationName); err != nil {
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		dto := contactDTO{
			UserID:     strconv.FormatInt(id, 10),
			Name:       realName,
			EmployeeID: employeeID,
			Username:   username,
			Role:       role,
		}
		if stationID.Valid {
			dto.StationID = strconv.FormatInt(stationID.Int64, 10)
		}
		if stationName.Valid {
			dto.StationName = stationName.String
		}
		out = append(out, dto)
	}
	if err := rows.Err(); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{"items": out})
}
