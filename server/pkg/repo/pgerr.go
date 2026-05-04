package repo

import (
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgconn"
)

func isPgError(err error, code string) (*pgconn.PgError, bool) {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == code {
		return pgErr, true
	}
	return nil, false
}

// wrapConflict 把 PG 唯一约束错误的 constraint 名翻译成可识别的 sentinel。
// users 表里两个唯一约束：users_username_key / users_employee_id_key
func wrapConflict(constraint string) error {
	switch constraint {
	case "users_username_key":
		return fmt.Errorf("%w: username", ErrConflict)
	case "users_employee_id_key":
		return fmt.Errorf("%w: employee_id", ErrConflict)
	default:
		return fmt.Errorf("%w: %s", ErrConflict, constraint)
	}
}
