package laser

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"testing"
)

// fake CropBoxStore（内存）。
type fakeCropBoxStore struct {
	mu  sync.Mutex
	box map[string]CropBox
}

func newFakeCropBoxStore() *fakeCropBoxStore { return &fakeCropBoxStore{box: map[string]CropBox{}} }

func (s *fakeCropBoxStore) GetCropBox(_ context.Context, k, unit string) (CropBox, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, ok := s.box[k+"/"+unit]
	return b, ok, nil
}
func (s *fakeCropBoxStore) SaveCropBox(_ context.Context, k, unit string, b CropBox) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.box[k+"/"+unit] = b
	return nil
}

// fake CloudReader：按 key 回固定 PCD 字节。
type fakeReader struct{ data map[string][]byte }

func (r fakeReader) GetObject(_ context.Context, key string) (io.ReadCloser, int64, error) {
	b, ok := r.data[key]
	if !ok {
		return nil, 0, context.Canceled
	}
	return io.NopCloser(bytes.NewReader(b)), int64(len(b)), nil
}

func validBox() CropBox {
	return CropBox{Center: [3]float32{1000, 0, 500}, Up: [3]float32{0, 0, 1}, YawDeg: 0,
		Half: [3]float32{500, 800, 400}}
}

// GET 未设置 → set=false；PUT 后 GET → set=true 且回原框。
func TestCropBoxGetPutRoundtrip(t *testing.T) {
	h, _, _, _ := newTestHandler(t, false)
	h.SetCropBoxStore(newFakeCropBoxStore())

	rec := do(h, "GET", "/v1/scans/laser/crop-box", "", "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("GET 应 200，得 %d", rec.Code)
	}
	var g map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &g)
	if g["set"] != false {
		t.Errorf("未设置应 set=false，得 %v", g["set"])
	}

	body, _ := json.Marshal(validBox())
	if rec := do(h, "PUT", "/v1/scans/laser/crop-box", string(body), "7"); rec.Code != http.StatusOK {
		t.Fatalf("PUT 应 200，得 %d (%s)", rec.Code, rec.Body)
	}
	rec = do(h, "GET", "/v1/scans/laser/crop-box", "", "7")
	_ = json.Unmarshal(rec.Body.Bytes(), &g)
	if g["set"] != true {
		t.Fatalf("PUT 后应 set=true，得 %v", g["set"])
	}
	bx, _ := json.Marshal(g["box"])
	var got CropBox
	_ = json.Unmarshal(bx, &got)
	if got.Half != validBox().Half || got.Center != validBox().Center {
		t.Errorf("回读框不符: %+v", got)
	}
}

// PUT 退化框（零半尺）→ 400。
func TestCropBoxPutDegenerate(t *testing.T) {
	h, _, _, _ := newTestHandler(t, false)
	h.SetCropBoxStore(newFakeCropBoxStore())
	bad := CropBox{Up: [3]float32{0, 0, 1}, Half: [3]float32{0, 1, 1}}
	body, _ := json.Marshal(bad)
	if rec := do(h, "PUT", "/v1/scans/laser/crop-box", string(body), "7"); rec.Code != http.StatusBadRequest {
		t.Errorf("退化框应 400，得 %d", rec.Code)
	}
}

// 无鉴权 → 401；未配 store → 501。
func TestCropBoxAuthAndUnconfigured(t *testing.T) {
	h, _, _, _ := newTestHandler(t, false) // 未 SetCropBoxStore
	if rec := do(h, "GET", "/v1/scans/laser/crop-box", "", ""); rec.Code != http.StatusUnauthorized {
		t.Errorf("无鉴权应 401，得 %d", rec.Code)
	}
	if rec := do(h, "GET", "/v1/scans/laser/crop-box", "", "7"); rec.Code != http.StatusNotImplemented {
		t.Errorf("未配 store 应 501，得 %d", rec.Code)
	}
}

// crop-preview：对已完成扫描的融合云用候选框裁剪 + 测量，回点数。
func TestCropPreview(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	h.SetCropBoxStore(newFakeCropBoxStore())

	// 造一个 done 扫描 + 一朵融合 PCD（框内一簇 + 框外远点）。
	job, _ := fr.Create(context.Background(), "sk", "a", "b", "icp", 1.0, nil, ptr64(7))
	key := "laser-scans/sk/fused.pcd"
	job.FusedObjectKey = &key
	fr.jobs[job.ID].FusedObjectKey = &key
	// 框内密簇(中心附近) + 框外远点(应被裁掉)。
	var xyz []float32
	for i := 0; i < 200; i++ {
		fx := float32(i%10) * 20
		xyz = append(xyz, 1000+fx, float32(i/10)*20-100, 500) // 框内一片
	}
	xyz = append(xyz, 9000, 9000, 9000) // 远点
	pcd, _ := EncodePCDBinary(xyz)
	h.reader = fakeReader{data: map[string][]byte{key: pcd}}

	body, _ := json.Marshal(validBox())
	rec := do(h, "POST", "/v1/scans/laser/"+itoa(job.ID)+"/crop-preview", string(body), "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("preview 应 200，得 %d (%s)", rec.Code, rec.Body)
	}
	var resp struct {
		TotalPoints int        `json:"total_points"`
		InPoints    int        `json:"in_points"`
		Measurement Dimensions `json:"measurement"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.TotalPoints != 201 {
		t.Errorf("总点应 201，得 %d", resp.TotalPoints)
	}
	if resp.InPoints != 200 {
		t.Errorf("框内应 200（裁掉远点），得 %d", resp.InPoints)
	}
}

// 按单元独立：PUT unit=b 不影响 unit=a；各自回读各自框。
func TestCropBoxPerUnit(t *testing.T) {
	h, _, _, _ := newTestHandler(t, false)
	h.SetCropBoxStore(newFakeCropBoxStore())

	bBox := CropBox{Center: [3]float32{-500, 0, 600}, Up: [3]float32{0, 0, 1}, YawDeg: 30, Half: [3]float32{400, 700, 350}}
	body, _ := json.Marshal(bBox)
	if rec := do(h, "PUT", "/v1/scans/laser/crop-box?unit=b", string(body), "7"); rec.Code != http.StatusOK {
		t.Fatalf("PUT unit=b 应 200，得 %d (%s)", rec.Code, rec.Body)
	}
	// a 单元仍未设置（与 b 独立）。
	var g map[string]any
	rec := do(h, "GET", "/v1/scans/laser/crop-box?unit=a", "", "7")
	_ = json.Unmarshal(rec.Body.Bytes(), &g)
	if g["set"] != false {
		t.Errorf("unit=a 应仍 set=false（与 b 独立），得 %v", g["set"])
	}
	if g["unit"] != "a" {
		t.Errorf("回包 unit 应为 a，得 %v", g["unit"])
	}
	// b 单元回读到框。
	rec = do(h, "GET", "/v1/scans/laser/crop-box?unit=b", "", "7")
	_ = json.Unmarshal(rec.Body.Bytes(), &g)
	if g["set"] != true {
		t.Fatalf("unit=b 应 set=true，得 %v", g["set"])
	}
	bx, _ := json.Marshal(g["box"])
	var got CropBox
	_ = json.Unmarshal(bx, &got)
	if got.Half != bBox.Half || got.YawDeg != bBox.YawDeg {
		t.Errorf("b 框回读不符: %+v", got)
	}
}

// crop-preview ?unit=b：对 unitB 云（非融合）裁剪测量。
func TestCropPreviewUnitB(t *testing.T) {
	h, fr, _, _ := newTestHandler(t, true)
	h.SetCropBoxStore(newFakeCropBoxStore())

	job, _ := fr.Create(context.Background(), "sk", "a", "b", "icp", 1.0, nil, ptr64(7))
	bKey := "laser-scans/sk/unit_b.pcd"
	fr.jobs[job.ID].UnitBObjectKey = &bKey
	// unitB 系：框内一簇（中心 -500,0,600 附近）+ 框外远点。
	var xyz []float32
	for i := 0; i < 150; i++ {
		fx := float32(i%10) * 20
		xyz = append(xyz, -500+fx-90, float32(i/10)*20-140, 600)
	}
	xyz = append(xyz, 9000, 9000, 9000)
	pcd, _ := EncodePCDBinary(xyz)
	h.reader = fakeReader{data: map[string][]byte{bKey: pcd}}

	bBox := CropBox{Center: [3]float32{-500, 0, 600}, Up: [3]float32{0, 0, 1}, YawDeg: 0, Half: [3]float32{500, 800, 400}}
	body, _ := json.Marshal(bBox)
	rec := do(h, "POST", "/v1/scans/laser/"+itoa(job.ID)+"/crop-preview?unit=b", string(body), "7")
	if rec.Code != http.StatusOK {
		t.Fatalf("preview unit=b 应 200，得 %d (%s)", rec.Code, rec.Body)
	}
	var resp struct {
		TotalPoints int `json:"total_points"`
		InPoints    int `json:"in_points"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.TotalPoints != 151 {
		t.Errorf("总点应 151，得 %d", resp.TotalPoints)
	}
	if resp.InPoints != 150 {
		t.Errorf("框内应 150（裁掉远点），得 %d", resp.InPoints)
	}
}

func ptr64(v int64) *int64 { return &v }
