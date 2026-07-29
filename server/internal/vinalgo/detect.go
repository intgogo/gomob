// detect.go —— 外部算法通用模型检测端点 /cv/veh/v1/detect 的客户端。
//
// 该端点按 method（模型 tag）直跑单个模型并回原始观测，坐标严格等于入参图像素坐标系，
// 服务端不做裁剪、方向翻转或透视矫正。VIN 还原链靠它取代两个本地模型：
//   - MethodVMASK 取代逆向来的 yolo-obb，给出限定深度平面拟合的 VIN 区域四角点
//   - MethodVINS  取代 2020 年的 vins0 本地副本，给出 17 字符几何锚点
//
// 与 /cv/ocr/v1/vin_detect 的分工：那个跑完整识别链（内部会矫正，坐标不可逆），只用于
// 对已还原的 4425×600 成品图出 VIN 串；本端点只回可反算的原子观测。
package vinalgo

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
)

// DetectMethod 是外部算法的模型 tag。
type DetectMethod string

const (
	// MethodVMASK 实例分割（NET=mask），检出 VIN 区域，xyxy 为旋转框四角点。
	MethodVMASK DetectMethod = "VMASK"
	// MethodVINS 字符检测（NET=yolo），检出逐字符轴对齐框，xyxy 为 x1,y1,x2,y2。
	MethodVINS DetectMethod = "VINS"
)

// DetectedObject 是一条检测观测，坐标位于入参图像素坐标系。
type DetectedObject struct {
	// Class 是模型类别：VMASK 恒为 "text"，VINS 为识别出的字符。
	Class string
	Score float64
	// Corners 是四角点。mask 类直接来自旋转框；yolo 类由轴对齐框展开，
	// 两者统一成同一形状，调用方不必区分模型类型。
	Corners [4][2]float64
	// Rotated 标记 Corners 是否来自真实旋转框（mask 类为 true）。
	Rotated bool
}

type detectResponse struct {
	ErrorCode int                           `json:"error_code"`
	ErrorMsg  string                        `json:"error_msg"`
	TotalTime float64                       `json:"total_time"`
	LogID     string                        `json:"log_id"`
	Result    map[string][]detectResultItem `json:"result"`
}

type detectResultItem struct {
	Score float64 `json:"score"`
	XYXY  []int   `json:"xyxy"`
	XYWH  []int   `json:"xywh"`
}

// Detect 用指定模型 tag 跑一次检测。fileName 只用于 multipart 的文件名，
// 服务端按内容解码，扩展名不参与判定。
func (c *Client) Detect(
	ctx context.Context,
	method DetectMethod,
	image []byte,
	fileName string,
) ([]DetectedObject, error) {
	if len(image) == 0 {
		return nil, errors.New("检测入参图为空")
	}
	if method == "" {
		return nil, errors.New("检测 method 为空")
	}

	nanos := c.now().UnixNano()
	sign, err := c.signer.Sign(nanos)
	if err != nil {
		return nil, fmt.Errorf("生成外部 VIN 算法签名: %w", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err := writer.WriteField("nanos", strconv.FormatInt(nanos, 10)); err != nil {
		return nil, err
	}
	if err := writer.WriteField("sign", sign); err != nil {
		return nil, err
	}
	if err := writer.WriteField("method", string(method)); err != nil {
		return nil, err
	}
	part, err := writer.CreateFormFile("image_binary", fileName)
	if err != nil {
		return nil, err
	}
	if _, err := part.Write(image); err != nil {
		return nil, err
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}

	reqCtx := ctx
	var cancel context.CancelFunc
	if _, hasDeadline := ctx.Deadline(); !hasDeadline {
		reqCtx, cancel = context.WithTimeout(ctx, c.timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(
		reqCtx,
		http.MethodPost,
		c.baseURL+"/cv/veh/v1/detect",
		&body,
	)
	if err != nil {
		return nil, fmt.Errorf("构造外部检测请求: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("User-Agent", "gomob-cvengine/vin-detect")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调用外部检测(%s): %w", method, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		return nil, fmt.Errorf("外部检测(%s) 返回 HTTP %d", method, resp.StatusCode)
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if err != nil {
		return nil, fmt.Errorf("读取外部检测响应: %w", err)
	}
	if len(raw) > maxResponseBytes {
		return nil, errors.New("外部检测响应过大")
	}
	return ParseDetectResponse(method, raw)
}

// ParseDetectResponse 解析 /cv/veh/v1/detect 响应。
// 独立导出便于 harness 回放录制的响应，无需真实网络。
func ParseDetectResponse(method DetectMethod, raw []byte) ([]DetectedObject, error) {
	var decoded detectResponse
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return nil, fmt.Errorf("解析外部检测响应: %w", err)
	}
	if decoded.ErrorCode != 0 || decoded.ErrorMsg != "success" {
		return nil, fmt.Errorf(
			"外部检测(%s)失败: error_code=%d error_msg=%s",
			method, decoded.ErrorCode, strings.TrimSpace(decoded.ErrorMsg),
		)
	}

	objects := make([]DetectedObject, 0, 24)
	for class, items := range decoded.Result {
		for _, item := range items {
			obj, err := toDetectedObject(class, item)
			if err != nil {
				return nil, fmt.Errorf("外部检测(%s) 类别 %q: %w", method, class, err)
			}
			objects = append(objects, obj)
		}
	}
	return objects, nil
}

// toDetectedObject 把服务端 xyxy 归一成四角点。
// gosmart Item 的序列化契约（item.go）：有旋转框时 xyxy 是 8 个数（四角点），
// 只有轴对齐框时是 4 个数（x1,y1,x2,y2）；两种长度都必须支持。
func toDetectedObject(class string, item detectResultItem) (DetectedObject, error) {
	obj := DetectedObject{Class: class, Score: item.Score}
	switch len(item.XYXY) {
	case 8:
		for i := 0; i < 4; i++ {
			obj.Corners[i] = [2]float64{
				float64(item.XYXY[i*2]),
				float64(item.XYXY[i*2+1]),
			}
		}
		obj.Rotated = true
	case 4:
		x1 := float64(item.XYXY[0])
		y1 := float64(item.XYXY[1])
		x2 := float64(item.XYXY[2])
		y2 := float64(item.XYXY[3])
		obj.Corners = [4][2]float64{{x1, y1}, {x2, y1}, {x2, y2}, {x1, y2}}
	default:
		return DetectedObject{}, fmt.Errorf("xyxy 长度非法: %d", len(item.XYXY))
	}
	return obj, nil
}
