package crypt

import (
	"math/rand"
	"time"
)

func init() {
	rand.Seed(time.Now().UnixNano())
}

func Ylzl(token []byte) string {
	i := len(token) / 2
	j := i + 250
	if len(token) < 512 {
		i = 0
		j = len(token)
	}

	k := []byte("google.com")
	b := append(k, token[i:j]...)
	result := MD5b(b)
	return result
}
