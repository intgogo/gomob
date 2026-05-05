package util

import (
	"math"
	"reflect"
	"strconv"
)

func Max(x, y int) int {
	if x < y {
		return y
	}
	return x
}

func Min(x, y int) int {
	if x > y {
		return y
	}
	return x
}

func MaxFloat32(x, y float32) float32 {
	if x < y {
		return y
	}
	return x
}

func MinFloat32(x, y float32) float32 {
	if x > y {
		return y
	}
	return x
}

func AbsInt(x int) int {
	if x > 0 {
		return x
	}
	return -x
}

func MaxIndexFloat32(vv []float32) int {
	maxVal := vv[0]
	maxIdx := 0

	for i, val := range vv {
		if val > maxVal || (math.IsInf(float64(val), 1) && !math.IsInf(float64(maxVal), 1)) {
			maxVal = val
			maxIdx = i
		}
	}

	return maxIdx
}

func SearchSlice(array interface{}, value interface{}) (ret []int) {
	switch reflect.TypeOf(array).Kind() {
	case reflect.Slice:
		s := reflect.ValueOf(array)
		for i := 0; i < s.Len(); i++ {
			if reflect.DeepEqual(value, s.Index(i).Interface()) {
				ret = append(ret, i)
			}
		}
	}
	return
}

func GetTextSimilarity(first, second string) (float64, int) {
	var similarText func([]rune, []rune, int, int) int
	similarText = func(str1, str2 []rune, len1, len2 int) int {
		var sum, max int
		pos1, pos2 := 0, 0

		// Find the longest segment of the same section in two strings
		for i := 0; i < len1; i++ {
			for j := 0; j < len2; j++ {
				for l := 0; (i+l < len1) && (j+l < len2) && (str1[i+l] == str2[j+l]); l++ {
					if l+1 > max {
						max = l + 1
						pos1 = i
						pos2 = j
					}
				}
			}
		}

		if sum = max; sum > 0 {
			if pos1 > 0 && pos2 > 0 {
				sum += similarText(str1, str2, pos1, pos2)
			}
			if (pos1+max < len1) && (pos2+max < len2) {
				s1 := []rune(str1)
				s2 := []rune(str2)
				sum += similarText(s1[pos1+max:], s2[pos2+max:], len1-pos1-max, len2-pos2-max)
			}
		}

		return sum
	}

	f1 := []rune(first)
	f2 := []rune(second)
	l1, l2 := len(f1), len(f2)
	if l1+l2 == 0 {
		return 0.0, 0
	}
	sim := similarText(f1, f2, l1, l2)
	percent := float64(sim*200) / float64(l1+l2)
	return percent / 100, sim
}

func MergeMaps[K comparable, V any](map1, map2 map[K]V) map[K]V {
	result := make(map[K]V, len(map1)+len(map2))
	for k, v := range map1 {
		result[k] = v
	}
	for k, v := range map2 {
		result[k] = v
	}

	return result
}

func IntToBinaryStr(n int) string {
	if n < 0 {
		panic("IntToBinaryStr negtive n!!!")
	}
	return strconv.FormatInt(int64(n), 2)
}

func CombineNumbers(nums []int) int {
	return nums[0]
	// var combinedBin string
	// for _, num := range nums {
	// 	binStr := IntToBinaryStr(num)
	// 	combinedBin += binStr
	// }
	// combinedDec, _ := strconv.ParseInt(combinedBin, 2, 64)
	// return int(combinedDec)
}
