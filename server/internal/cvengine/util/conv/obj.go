package conv

import (
	"encoding/json"
	"fmt"
	"errors"
	"strconv"
)

func ObjToStr(i interface{}) string {
	if i == nil {
		return ""
	}
	return i.(string)
}

//obj 反序列化为字符串
func ObjToJson(v interface{}) (string, error) {
	str, err := json.Marshal(v)
	if err != nil {
		fmt.Println("序列化失败:", err)
		return "", errors.New("序列化失败:" + err.Error())
	}
	return string(str), nil
}

// 函　数：Obj2map
// 概　要：
// 参　数：
//      obj: 传入Obj
// 返回值：
//      mapObj: map对象
//      err: 错误
func ObjToMap(obj interface{}) (mapObj map[string]interface{}, err error) {
	// 结构体转json
	b, err := json.Marshal(obj)
	if err != nil {
		return nil, err
	}

	var result map[string]interface{}
	if err := json.Unmarshal(b, &result); err != nil {
		return nil, err
	}
	return result, nil
}

//onj变成数字
func ObjToInt(i interface{}) (int, error) {
	n := 0
	switch i.(type) {
	case int:
		n = i.(int)
	case int32:
		n = int(i.(int32))
	case int64:
		n = int(i.(int64))
	case float32:
		n = int(i.(float32))
	case float64:
		n = int(i.(float64))
	case string:
		var err error
		n, err = strconv.Atoi(i.(string))
		if err != nil {
			return 0, err
		}
	}
	return n, nil
}
