// JWT 签发与校验。HS256，密钥从 GOMOB_JWT_SECRET 读；缺省走开发密钥。
package token

import (
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	AccessTTL  = 2 * time.Hour
	RefreshTTL = 7 * 24 * time.Hour
)

type Claims struct {
	UserID int64  `json:"uid"`
	Role   string `json:"role"`
	Kind   string `json:"kind"` // access / refresh
	jwt.RegisteredClaims
}

func secret() []byte {
	s := os.Getenv("GOMOB_JWT_SECRET")
	if s == "" {
		s = "dev-only-secret-change-me-in-prod"
	}
	return []byte(s)
}

func sign(c Claims) (string, error) {
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, c)
	return t.SignedString(secret())
}

func IssueAccess(userID int64, role string) (string, error) {
	now := time.Now()
	return sign(Claims{
		UserID: userID,
		Role:   role,
		Kind:   "access",
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(AccessTTL)),
		},
	})
}

func IssueRefresh(userID int64, role string) (string, error) {
	now := time.Now()
	return sign(Claims{
		UserID: userID,
		Role:   role,
		Kind:   "refresh",
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(RefreshTTL)),
		},
	})
}

func Parse(raw string) (*Claims, error) {
	tok, err := jwt.ParseWithClaims(raw, &Claims{}, func(t *jwt.Token) (any, error) {
		if t.Method.Alg() != jwt.SigningMethodHS256.Alg() {
			return nil, fmt.Errorf("unexpected alg %s", t.Method.Alg())
		}
		return secret(), nil
	})
	if err != nil {
		return nil, err
	}
	c, ok := tok.Claims.(*Claims)
	if !ok || !tok.Valid {
		return nil, errors.New("invalid token")
	}
	return c, nil
}
