package conv

import (
	"strconv"
	"strings"
)

func Float32ToStr(v float32, prec int) string {
	return strconv.FormatFloat(float64(v), 'f', prec, 32)
}

func Float64ToStr(v float64, prec int) string {
	return strconv.FormatFloat(v, 'f', prec, 64)
}

func StrToFloat32(s string) float32 {
	v, err := strconv.ParseFloat(s, 32)
	if err == nil {
		return float32(v)
	}
	return 0
}

func StrToFloat64(s string) float64 {
	v, err := strconv.ParseFloat(s, 64)
	if err == nil {
		return v
	}
	return 0
}

func Str2ArrayFloat32(s string) ([]float32, error) {
	ss := strings.Split(s, ",")
	aa := []float32{}
	for _, a := range ss {
		v, err := strconv.ParseFloat(a, 32)
		if err != nil {
			return nil, err
		}

		aa = append(aa, float32(v))
	}
	return aa, nil
}

func ArrayFloat32Mean(vv []float32) (mean float32) {
	for _, v := range vv {
		mean += v
	}
	mean /= float32(len(vv))
	return
}
