package util

import (
	"errors"
	"fmt"
)

const (
	ErrOK = 0

	ErrBadRequest     = 1000 // 输入参数不正确, 包括 url 参数和 http-body-json 参数
	ErrInternalServer = 1001 // 内部服务器出错
	ErrNotSupported   = 1002 // 不支持
	ErrUnknown        = 1999 // 未知错误

	ErrTokenMissing        = 2000 // token 缺失
	ErrTokenEncodeFailed   = 2001 // token 编码出错
	ErrTokenDecodeFailed   = 2002 // token 解码出错
	ErrTokenAccessExpired  = 2003 // token access 过期
	ErrTokenRefreshExpired = 2004 // token refresh 过期
	ErrTokenShouldNotGuest = 2005 // token 不能是 guest 认证的
	ErrTokenInvalid        = 2006 // token 失效, 应该重新认证

	ErrAuthFailed         = 2010 // 认证失败
	ErrOAuthStateMismatch = 2011 // oauth state 不匹配
)

var ErrNone = NewError(ErrOK, "success")
var ErrNotFound = errors.New("no data")
var ErrInternal = errors.New("server internal error")

type Error struct {
	Code int    `json:"err_code"`
	Msg  string `json:"err_msg"`
}

func NewError(code int, msg string) *Error {
	return &Error{
		Code: code,
		Msg:  msg,
	}
}

func (e *Error) Error() string {
	return fmt.Sprintf("err_code: %d, err_msg: %s", e.Code, e.Msg)
}
