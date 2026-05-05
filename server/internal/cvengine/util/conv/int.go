package conv

import (
	"encoding/binary"
	"strconv"
	"strings"
)

func IntToStr(i int) string {
	return strconv.Itoa(i)
}
func StrToInt(s string) int {
	v, _ := strconv.Atoi(s)
	return v
}
func StrToInt64(s string) int64 {
	v, _ := strconv.ParseInt(s, 10, 64)
	return v
}

func Int32ToStr(i int32) string {
	return strconv.FormatInt(int64(i), 10)
}

func Int64ToStr(i int64) string {
	return strconv.FormatInt(i, 10)
}

func Int64ToBytes(i int64) []byte {
	var buf = make([]byte, 8)
	binary.BigEndian.PutUint64(buf, uint64(i))
	return buf
}

func BytesToInt64(buf []byte) int64 {
	return int64(binary.BigEndian.Uint64(buf))
}

func Str2Ints(s string) []int {
	ss := strings.Split(s, ",")
	aa := []int{}
	for _, a := range ss {
		a = strings.ReplaceAll(a, " ", "")
		a = strings.ReplaceAll(a, "\n", "")
		v, err := strconv.Atoi(a)
		if err != nil {
			return nil
		}

		aa = append(aa, v)
	}
	return aa
}
