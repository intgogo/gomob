package vinalgo

import (
	"math"
	"sort"
	"testing"
)

// 下面两段是 2026-07-26 在 160 上打真实 gosmart 服务、用 4160×832 真机采集
// (.dev/vin_factory_bf301208/vin_captures/cap_003) 抓到的原样响应，
// 作为 /cv/veh/v1/detect 的契约回归基线。
const realVMASKResponse = `{"error_code":0,"error_msg":"success","total_time":195.078556,` +
	`"log_id":"97147336768721","uri":"/cv/veh/v1/detect",` +
	`"result":{"text":[{"score":0.8697146,` +
	`"xyxy":[1060,322,3221,350,3217,594,1057,566],"xywh":[1057,322,2164,272]}]}}`

const realVINSResponse = `{"error_code":0,"error_msg":"success","total_time":25.560014,` +
	`"log_id":"97147546984921","uri":"/cv/veh/v1/detect","result":{` +
	`"-":[{"score":0.94853723,"xyxy":[2366,23,2550,231],"xywh":[2366,23,184,208]},` +
	`{"score":0.9390248,"xyxy":[3,33,199,230],"xywh":[3,33,196,197]}],` +
	`"0":[{"score":0.94853723,"xyxy":[1524,56,1641,227],"xywh":[1524,56,117,171]},` +
	`{"score":0.94122344,"xyxy":[2014,54,2134,229],"xywh":[2014,54,120,175]}],` +
	`"1":[{"score":0.89912134,"xyxy":[2157,57,2214,228],"xywh":[2157,57,57,171]}],` +
	`"2":[{"score":0.94417685,"xyxy":[1261,52,1391,228],"xywh":[1261,52,130,176]}]}}`

func TestParseDetectResponseVMASKKeepsRotatedCorners(t *testing.T) {
	objects, err := ParseDetectResponse(MethodVMASK, []byte(realVMASKResponse))
	if err != nil {
		t.Fatalf("解析 VMASK 响应失败: %v", err)
	}
	if len(objects) != 1 {
		t.Fatalf("期望 1 个区域，实际 %d", len(objects))
	}
	obj := objects[0]
	if obj.Class != "text" {
		t.Fatalf("VMASK 类别应为 text，实际 %q", obj.Class)
	}
	if !obj.Rotated {
		t.Fatal("8 数 xyxy 必须标记为真实旋转框，否则调用方会误当轴对齐框用")
	}
	want := [4][2]float64{{1060, 322}, {3221, 350}, {3217, 594}, {1057, 566}}
	if obj.Corners != want {
		t.Fatalf("四角点错位\n实际 %v\n期望 %v", obj.Corners, want)
	}
	// 旋转框不能退化成轴对齐：上沿 y 从 322 到 350，说明框本身带 0.7° 倾角。
	if obj.Corners[0][1] == obj.Corners[1][1] {
		t.Fatal("旋转框上沿被压平，倾角信息丢失")
	}
}

func TestParseDetectResponseVINSExpandsAxisAlignedBoxes(t *testing.T) {
	objects, err := ParseDetectResponse(MethodVINS, []byte(realVINSResponse))
	if err != nil {
		t.Fatalf("解析 VINS 响应失败: %v", err)
	}
	if len(objects) != 6 {
		t.Fatalf("期望 6 个字符观测，实际 %d", len(objects))
	}
	for _, obj := range objects {
		if obj.Rotated {
			t.Fatalf("VINS 是轴对齐框，不应标记 Rotated：%v", obj)
		}
		// 展开后必须是规范矩形：左上、右上、右下、左下。
		if obj.Corners[0][1] != obj.Corners[1][1] || obj.Corners[2][1] != obj.Corners[3][1] {
			t.Fatalf("轴对齐框展开后上下沿不水平：%v", obj.Corners)
		}
		if obj.Corners[0][0] != obj.Corners[3][0] || obj.Corners[1][0] != obj.Corners[2][0] {
			t.Fatalf("轴对齐框展开后左右沿不竖直：%v", obj.Corners)
		}
	}

	// 星号 ☆ 被 VINS 识别成 "-" 类且分数很高（实测 0.948/0.939），
	// 调用方必须能靠 Class 把它滤掉，否则会污染 17 字符格架。
	var dashCount int
	for _, obj := range objects {
		if obj.Class == "-" {
			dashCount++
		}
	}
	if dashCount != 2 {
		t.Fatalf("期望保留 2 个 \"-\" 类供调用方过滤，实际 %d", dashCount)
	}

	var xs []float64
	for _, obj := range objects {
		if obj.Class == "0" {
			xs = append(xs, obj.Corners[0][0])
		}
	}
	sort.Float64s(xs)
	if len(xs) != 2 || math.Abs(xs[0]-1524) > 0.5 || math.Abs(xs[1]-2014) > 0.5 {
		t.Fatalf("同类多实例被吞掉，实际 x=%v", xs)
	}
}

func TestParseDetectResponseRejectsFailureAndBadGeometry(t *testing.T) {
	cases := []struct {
		name string
		body string
	}{
		{
			name: "服务端报错",
			body: `{"error_code":400,"error_msg":"***illegal access***","result":{}}`,
		},
		{
			name: "error_code 为 0 但 msg 非 success",
			body: `{"error_code":0,"error_msg":"no targets","result":{}}`,
		},
		{
			name: "xyxy 长度非法",
			body: `{"error_code":0,"error_msg":"success","result":{"text":[{"score":0.9,"xyxy":[1,2,3]}]}}`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := ParseDetectResponse(MethodVMASK, []byte(tc.body)); err == nil {
				t.Fatal("非法响应必须报错，不能静默返回空结果")
			}
		})
	}
}

func TestParseDetectResponseEmptyResultIsNotError(t *testing.T) {
	// 无检出是业务结果不是故障：模型没找到 VIN 区域时 gosmart 返回 success + 空 result，
	// 调用方按 0 个观测走"可重拍判废"，不能当成系统错误。
	objects, err := ParseDetectResponse(MethodVMASK,
		[]byte(`{"error_code":0,"error_msg":"success","result":{}}`))
	if err != nil {
		t.Fatalf("空结果不应报错: %v", err)
	}
	if len(objects) != 0 {
		t.Fatalf("期望 0 个观测，实际 %d", len(objects))
	}
}
