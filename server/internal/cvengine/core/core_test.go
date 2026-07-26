package core

import "testing"

func TestOrtDeviceID(t *testing.T) {
	tests := []struct {
		name    string
		raw     string
		want    int
		wantErr bool
	}{
		{name: "默认使用多线程 CPU", raw: "", want: -1},
		{name: "显式多线程 CPU", raw: "-1", want: -1},
		{name: "CUDA 设备零", raw: "0", want: 0},
		{name: "TensorRT 设备三", raw: "103", want: 103},
		{name: "小于负一非法", raw: "-2", wantErr: true},
		{name: "非整数非法", raw: "cpu", wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Setenv(ortDeviceIDEnv, test.raw)
			got, err := ortDeviceID()
			if test.wantErr {
				if err == nil {
					t.Fatalf("ortDeviceID()=%d，期望失败", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("ortDeviceID() 失败: %v", err)
			}
			if got != test.want {
				t.Fatalf("ortDeviceID()=%d，期望 %d", got, test.want)
			}
		})
	}
}

func TestRegisterYoloONNXRejectsInvalidGeometryBeforeLoading(t *testing.T) {
	tests := []struct {
		name string
		opts YoloOptions
	}{
		{
			name: "anchors 数量不匹配",
			opts: YoloOptions{Classes: []string{"0"}, Strides: []int{8, 16}, Anchors: []int{1, 2}},
		},
		{
			name: "stride 非正数",
			opts: YoloOptions{Classes: []string{"0"}, Strides: []int{0}, Anchors: []int{1, 2, 3, 4, 5, 6}},
		},
		{
			name: "anchor 非正数",
			opts: YoloOptions{Classes: []string{"0"}, Strides: []int{8}, Anchors: []int{1, 2, 3, 4, 5, 0}},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := New().RegisterYoloONNX("VINCHAR", "/不存在.onnx", test.opts); err == nil {
				t.Fatal("非法 yolo 几何配置必须在加载模型前失败")
			}
		})
	}
}
