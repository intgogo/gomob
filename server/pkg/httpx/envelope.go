// 统一响应信封 + 错误码处理（详见 docs/architecture/server/02-api-contract.md §1.3）。
package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
)

type Envelope struct {
	Code    int    `json:"code"`
	Data    any    `json:"data,omitempty"`
	Message string `json:"message,omitempty"`
	TraceID string `json:"trace_id,omitempty"`
}

func WriteJSON(w http.ResponseWriter, status int, env Envelope) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(env)
}

func OK(w http.ResponseWriter, data any) {
	WriteJSON(w, http.StatusOK, Envelope{Code: 0, Data: data})
}

// 业务错误码映射 → HTTP status。
type APIError struct {
	Code    int
	HTTP    int
	Message string
}

func (e *APIError) Error() string { return e.Message }

func NewError(code, httpStatus int, message string) *APIError {
	return &APIError{Code: code, HTTP: httpStatus, Message: message}
}

// 已编目错误
var (
	ErrBadParam        = NewError(10001, http.StatusBadRequest, "参数缺失或格式错误")
	ErrFieldRange      = NewError(10002, http.StatusBadRequest, "字段值越界")
	ErrRateLimited     = NewError(10003, http.StatusTooManyRequests, "请求太频繁，请稍后重试")
	ErrLoginFailed     = NewError(40101, http.StatusUnauthorized, "用户名或密码错误")
	ErrTokenInvalid    = NewError(40102, http.StatusUnauthorized, "登录已过期，请重新登录")
	ErrPermDenied      = NewError(40103, http.StatusForbidden, "权限不足")
	ErrAccountInactive = NewError(40104, http.StatusForbidden, "账号未激活，请等待审核或联系管理员")
	ErrUserExists      = NewError(40201, http.StatusConflict, "用户名已存在")
	ErrEmployeeExists  = NewError(40202, http.StatusConflict, "工号已存在")
	ErrNotFound        = NewError(40301, http.StatusNotFound, "资源不存在")
	ErrInternal        = NewError(50001, http.StatusInternalServerError, "服务端内部错误")
)

func WriteError(w http.ResponseWriter, err error) {
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		WriteJSON(w, apiErr.HTTP, Envelope{Code: apiErr.Code, Message: apiErr.Message})
		return
	}
	WriteJSON(w, ErrInternal.HTTP, Envelope{Code: ErrInternal.Code, Message: ErrInternal.Message})
}
