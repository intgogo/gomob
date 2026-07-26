package laser

import (
	"bytes"
	"encoding/binary"
	"math"
	"testing"
)

func TestPCDRoundTrip(t *testing.T) {
	in := []float32{1, 2, 3, -4.5, 1000.25, -20000, 0, 0, 0}
	enc, err := EncodePCDBinary(in)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if !bytes.Contains(enc, []byte("DATA binary\n")) {
		t.Error("头缺 DATA binary")
	}
	if !bytes.Contains(enc, []byte("POINTS 3\n")) {
		t.Error("头缺 POINTS 3")
	}
	out, err := DecodePCDBinary(enc)
	if err != nil {
		t.Fatalf("解码失败: %v", err)
	}
	if len(out) != len(in) {
		t.Fatalf("长度不符: got %d want %d", len(out), len(in))
	}
	for i := range in {
		if math.Abs(float64(out[i]-in[i])) > 1e-4 {
			t.Errorf("第 %d 值 %.4f != %.4f", i, out[i], in[i])
		}
	}
}

func TestPCDEmpty(t *testing.T) {
	enc, err := EncodePCDBinary(nil)
	if err != nil {
		t.Fatalf("空云编码应成功: %v", err)
	}
	if !bytes.Contains(enc, []byte("POINTS 0\n")) {
		t.Error("空云应 POINTS 0")
	}
	out, err := DecodePCDBinary(enc)
	if err != nil {
		t.Fatalf("空云解码失败: %v", err)
	}
	if len(out) != 0 {
		t.Errorf("空云应解出 0 点，得 %d", len(out))
	}
}

func TestPCDBadLength(t *testing.T) {
	if _, err := EncodePCDBinary([]float32{1, 2}); err == nil {
		t.Error("非 3 倍数长度应报错")
	}
}

func TestPCDXYZRGBI(t *testing.T) {
	enc, err := EncodePCDBinaryXYZRGBI(
		[]float32{1, 2, 3, 4, 5, 6},
		[]uint32{0x00ff3300, 0x000077ff},
		[]float32{10, 20},
	)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if !bytes.Contains(enc, []byte("FIELDS x y z rgb intensity\n")) {
		t.Fatal("头缺 XYZRGBI FIELDS")
	}
	body := enc[bytes.Index(enc, []byte("DATA binary\n"))+len("DATA binary\n"):]
	if len(body) != 2*5*4 {
		t.Fatalf("主体长度=%d want %d", len(body), 2*5*4)
	}
	if got := binary.LittleEndian.Uint32(body[12:16]); got != 0x00ff3300 {
		t.Fatalf("rgb0=0x%08x", got)
	}
	if got := math.Float32frombits(binary.LittleEndian.Uint32(body[16:20])); got != 10 {
		t.Fatalf("intensity0=%f", got)
	}
}

func TestPCDXYZRGB(t *testing.T) {
	in := []float32{1, 2, 3, 4, 5, 6}
	enc, err := EncodePCDBinaryXYZRGB(
		in,
		[]uint32{0x00123456, 0x00abcdef},
	)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if !bytes.Contains(enc, []byte("FIELDS x y z rgb\n")) {
		t.Fatal("头缺 XYZRGB FIELDS")
	}
	body := enc[bytes.Index(enc, []byte("DATA binary\n"))+len("DATA binary\n"):]
	if len(body) != 2*4*4 {
		t.Fatalf("主体长度=%d want %d", len(body), 2*4*4)
	}
	if got := binary.LittleEndian.Uint32(body[12:16]); got != 0x00123456 {
		t.Fatalf("rgb0=0x%08x", got)
	}
	out, err := DecodePCDBinary(enc)
	if err != nil {
		t.Fatalf("XYZRGB 应能解出 XYZ: %v", err)
	}
	for i := range in {
		if out[i] != in[i] {
			t.Fatalf("xyz[%d]=%v want %v", i, out[i], in[i])
		}
	}
}

func TestPCDBinarySampleStreamPreservesRecordsAndSourceCount(t *testing.T) {
	const sourcePoints = 101
	xyz := make([]float32, sourcePoints*3)
	rgb := make([]uint32, sourcePoints)
	angles := make([]float32, sourcePoints)
	for i := 0; i < sourcePoints; i++ {
		xyz[3*i] = float32(i)
		xyz[3*i+1] = float32(i + 1000)
		xyz[3*i+2] = float32(-i)
		rgb[i] = uint32(i)
		angles[i] = float32(i) + 0.25
	}
	full, err := EncodePCDBinaryXYZRGBI(xyz, rgb, angles)
	if err != nil {
		t.Fatal(err)
	}
	stream, err := PreparePCDBinarySample(bytes.NewReader(full), int64(len(full)), 7)
	if err != nil {
		t.Fatalf("准备采样失败: %v", err)
	}
	if stream.SourcePoints() != sourcePoints || stream.SamplePoints() != 7 {
		t.Fatalf("点数 source/sample=%d/%d", stream.SourcePoints(), stream.SamplePoints())
	}
	var sampled bytes.Buffer
	if err := stream.WriteSampleTo(&sampled); err != nil {
		t.Fatalf("写采样 PCD 失败: %v", err)
	}
	if int64(sampled.Len()) != stream.ContentLength() {
		t.Fatalf("长度=%d want %d", sampled.Len(), stream.ContentLength())
	}
	if !bytes.Contains(sampled.Bytes(), []byte("# GOMOB_SOURCE_POINTS 101\n")) {
		t.Fatal("缺权威源点数注释")
	}
	if !bytes.Contains(sampled.Bytes(), []byte("POINTS 7\n")) {
		t.Fatal("采样头未改写 POINTS")
	}
	decoded, err := DecodePCDBinary(sampled.Bytes())
	if err != nil {
		t.Fatalf("采样 PCD 不可解码: %v", err)
	}
	if len(decoded) != 7*3 {
		t.Fatalf("采样 xyz 长度=%d", len(decoded))
	}
	last := float32(-1)
	for i := 0; i < 7; i++ {
		x := decoded[3*i]
		if x <= last || x < 0 || x >= sourcePoints {
			t.Fatalf("分层样本序号非法: %v", decoded)
		}
		last = x
	}

	stream2, _ := PreparePCDBinarySample(bytes.NewReader(full), int64(len(full)), 7)
	var sampled2 bytes.Buffer
	_ = stream2.WriteSampleTo(&sampled2)
	if !bytes.Equal(sampled.Bytes(), sampled2.Bytes()) {
		t.Fatal("相同输入的派生 PCD 必须确定性一致")
	}
}

func TestPCDBinarySampleRejectsTruncatedObjectBeforeWriting(t *testing.T) {
	full, _ := EncodePCDBinary([]float32{1, 2, 3, 4, 5, 6})
	if _, err := PreparePCDBinarySample(bytes.NewReader(full), int64(len(full)-1), 1); err == nil {
		t.Fatal("对象尺寸不足应在响应开始前拒绝")
	}
	if _, err := PreparePCDBinarySample(bytes.NewReader(append(full, 0)), int64(len(full)+1), 1); err == nil {
		t.Fatal("对象尾部多余字节也应拒绝")
	}
}

func TestMeasuredPCDArtifactAndSourceChecksum(t *testing.T) {
	xyz := []float32{1, 2, 3, 4, 5, 6, 7, 8, 9}
	artifact := newMeasuredCloudArtifact(xyz, identity16(), "site-1", "region-1", 9)
	full, err := EncodeMeasuredPCDBinary(xyz, artifact)
	if err != nil {
		t.Fatal(err)
	}
	stream, err := PreparePCDBinarySample(bytes.NewReader(full), int64(len(full)), 2)
	if err != nil {
		t.Fatal(err)
	}
	if !stream.CanonicalXYZRecords() || stream.CoordinateSchema() != artifact.CoordinateSchema ||
		stream.XYZSHA256() != artifact.XYZSHA256 || stream.FinalBToASHA256() != artifact.FinalBToASHA256 {
		t.Fatalf("PCD 内容身份未完整保留: schema=%q xyz=%q btoa=%q canonical=%v",
			stream.CoordinateSchema(), stream.XYZSHA256(), stream.FinalBToASHA256(), stream.CanonicalXYZRecords())
	}
	sampled, err := stream.ReadSampleVerified(artifact.XYZSHA256)
	if err != nil {
		t.Fatalf("源校验失败: %v", err)
	}
	if !bytes.Contains(sampled, []byte("POINTS 2\n")) ||
		!bytes.Contains(sampled, []byte(pcdXYZSHA256Comment+artifact.XYZSHA256+"\n")) {
		t.Fatal("派生样本必须保留源内容身份")
	}
	decoded, err := DecodePCDBinary(sampled)
	if err != nil || len(decoded) != 6 {
		t.Fatalf("派生 measured PCD 非法: points=%d err=%v", len(decoded)/3, err)
	}

	corrupt := append([]byte(nil), full...)
	corrupt[len(corrupt)-1] ^= 0x01
	broken, err := PreparePCDBinarySample(bytes.NewReader(corrupt), int64(len(corrupt)), 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := broken.ReadSampleVerified(artifact.XYZSHA256); err == nil {
		t.Fatal("主体坐标损坏必须被源 SHA-256 拒绝")
	}
}

func TestMeasuredArtifactRequiresSiteAndRegionRevision(t *testing.T) {
	xyz := []float32{1, 2, 3}
	artifact := newMeasuredCloudArtifact(xyz, identity16(), "", "region-1", 0)
	if artifact.validContentIdentity() {
		t.Fatal("canonical measured 不得缺 site revision")
	}
	artifact = newMeasuredCloudArtifact(xyz, identity16(), "site-1", "", 0)
	if artifact.validContentIdentity() {
		t.Fatal("canonical measured 不得缺 region revision")
	}
}

func TestLaserObjectKey(t *testing.T) {
	if got := LaserObjectKey("sess-abc", "fused"); got != "laser-scans/sess-abc/fused.pcd" {
		t.Errorf("对象键 = %q", got)
	}
}
