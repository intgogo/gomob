package gocv

type Error struct {
	Code int
	Msg  string
}

func NewError(code int, msg string) *Error {
	return &Error{Code: code, Msg: msg}
}

func (err *Error) Error() string {
	return err.Msg
}
