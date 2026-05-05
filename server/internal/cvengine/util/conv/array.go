package conv

import "strconv"

func ArrIntToStr(arr []int) (string) {
	var str string
	for _, l := range arr {
		if str == "" {
			str = strconv.Itoa(l)
		} else {
			str += "," + strconv.Itoa(l)
		}
	}
	return str
}
