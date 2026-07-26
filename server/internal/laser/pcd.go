package laser

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"
)

const (
	pcdSourcePointsComment     = "# GOMOB_SOURCE_POINTS "
	pcdCoordinateSchemaComment = "# GOMOB_COORDINATE_SCHEMA "
	pcdXYZSHA256Comment        = "# GOMOB_XYZ_SHA256 "
	pcdFinalBToASHA256Comment  = "# GOMOB_FINAL_B_TO_A_SHA256 "
)

// PCDBinarySampleStream 是从权威 binary PCD 派生的有界渲染流。
// 它只保留原始记录的确定性分层样本，字段内容（XYZ/RGB/intensity）逐字节不改。
type PCDBinarySampleStream struct {
	reader              *bufio.Reader
	header              []byte
	recordBytes         int
	sourcePoints        int
	samplePoints        int
	coordinateSchema    string
	xyzSHA256           string
	finalBToASHA256     string
	canonicalXYZRecords bool
}

// PreparePCDBinarySample 只解析 PCD 头并校验对象尺寸，不读取全量主体。
// maxPoints 必须大于 0；采样按原始记录序号分层并在层内稳定抖动，避免固定 stride 与扫描线周期混叠。
func PreparePCDBinarySample(r io.Reader, objectSize int64, maxPoints int) (*PCDBinarySampleStream, error) {
	if maxPoints <= 0 {
		return nil, fmt.Errorf("maxPoints 必须大于 0")
	}
	br := bufio.NewReader(r)
	var rawLines []string
	var fields, types []string
	var sizes, counts []int
	points := -1
	headerBytes := 0
	dataMode := ""
	coordinateSchema := ""
	xyzSHA256 := ""
	finalBToASHA256 := ""
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("PCD 头未见 DATA: %w", err)
		}
		rawLines = append(rawLines, line)
		headerBytes += len(line)
		trimmed := strings.TrimRight(line, "\r\n")
		switch {
		case strings.HasPrefix(trimmed, pcdCoordinateSchemaComment):
			coordinateSchema = strings.TrimSpace(strings.TrimPrefix(trimmed, pcdCoordinateSchemaComment))
		case strings.HasPrefix(trimmed, pcdXYZSHA256Comment):
			xyzSHA256 = strings.TrimSpace(strings.TrimPrefix(trimmed, pcdXYZSHA256Comment))
		case strings.HasPrefix(trimmed, pcdFinalBToASHA256Comment):
			finalBToASHA256 = strings.TrimSpace(strings.TrimPrefix(trimmed, pcdFinalBToASHA256Comment))
		}
		parts := strings.Fields(trimmed)
		if len(parts) == 0 {
			continue
		}
		switch parts[0] {
		case "FIELDS":
			fields = append([]string(nil), parts[1:]...)
		case "SIZE":
			var parseErr error
			sizes, parseErr = parsePositiveInts(parts[1:], "SIZE")
			if parseErr != nil {
				return nil, parseErr
			}
		case "TYPE":
			types = append([]string(nil), parts[1:]...)
		case "COUNT":
			var parseErr error
			counts, parseErr = parsePositiveInts(parts[1:], "COUNT")
			if parseErr != nil {
				return nil, parseErr
			}
		case "POINTS":
			if len(parts) != 2 {
				return nil, fmt.Errorf("POINTS 格式错误")
			}
			points, err = strconv.Atoi(parts[1])
			if err != nil || points < 0 {
				return nil, fmt.Errorf("POINTS 解析失败: %q", parts[1])
			}
		case "DATA":
			if len(parts) != 2 {
				return nil, fmt.Errorf("DATA 格式错误")
			}
			dataMode = parts[1]
		}
		if dataMode != "" {
			break
		}
	}
	if dataMode != "binary" {
		return nil, fmt.Errorf("仅支持 DATA binary，得 %q", dataMode)
	}
	if points < 0 || len(fields) == 0 || len(sizes) != len(fields) || len(types) != len(fields) {
		return nil, fmt.Errorf("PCD 缺 POINTS/FIELDS/SIZE/TYPE 或字段数量不一致")
	}
	if len(counts) == 0 {
		counts = make([]int, len(fields))
		for i := range counts {
			counts[i] = 1
		}
	}
	if len(counts) != len(fields) {
		return nil, fmt.Errorf("COUNT 数量 %d 与 FIELDS %d 不一致", len(counts), len(fields))
	}
	recordBytes := 0
	for i := range sizes {
		recordBytes += sizes[i] * counts[i]
	}
	if recordBytes <= 0 {
		return nil, fmt.Errorf("PCD 单点记录长度非法: %d", recordBytes)
	}
	expectedSize := int64(headerBytes) + int64(points)*int64(recordBytes)
	if objectSize > 0 && objectSize != expectedSize {
		return nil, fmt.Errorf("PCD 对象长度不一致：期望 %d 字节，实际 %d", expectedSize, objectSize)
	}

	samplePoints := points
	if samplePoints > maxPoints {
		samplePoints = maxPoints
	}
	var header bytes.Buffer
	header.WriteString(pcdSourcePointsComment)
	header.WriteString(strconv.Itoa(points))
	header.WriteByte('\n')
	hasWidth, hasHeight, hasPoints := false, false, false
	for _, raw := range rawLines {
		trimmed := strings.TrimRight(raw, "\r\n")
		parts := strings.Fields(trimmed)
		if strings.HasPrefix(trimmed, pcdSourcePointsComment) {
			continue
		}
		key := ""
		if len(parts) > 0 {
			key = parts[0]
		}
		switch key {
		case "WIDTH":
			header.WriteString("WIDTH " + strconv.Itoa(samplePoints) + "\n")
			hasWidth = true
		case "HEIGHT":
			header.WriteString("HEIGHT 1\n")
			hasHeight = true
		case "POINTS":
			header.WriteString("POINTS " + strconv.Itoa(samplePoints) + "\n")
			hasPoints = true
		case "DATA":
			if !hasWidth {
				header.WriteString("WIDTH " + strconv.Itoa(samplePoints) + "\n")
			}
			if !hasHeight {
				header.WriteString("HEIGHT 1\n")
			}
			if !hasPoints {
				header.WriteString("POINTS " + strconv.Itoa(samplePoints) + "\n")
			}
			header.WriteString("DATA binary\n")
		default:
			header.WriteString(raw)
		}
	}
	return &PCDBinarySampleStream{
		reader:              br,
		header:              header.Bytes(),
		recordBytes:         recordBytes,
		sourcePoints:        points,
		samplePoints:        samplePoints,
		coordinateSchema:    coordinateSchema,
		xyzSHA256:           xyzSHA256,
		finalBToASHA256:     finalBToASHA256,
		canonicalXYZRecords: canonicalXYZRecordLayout(fields, sizes, types, counts),
	}, nil
}

func canonicalXYZRecordLayout(fields []string, sizes []int, types []string, counts []int) bool {
	if len(fields) != 3 || len(sizes) != 3 || len(types) != 3 || len(counts) != 3 {
		return false
	}
	return fields[0] == "x" && fields[1] == "y" && fields[2] == "z" &&
		sizes[0] == 4 && sizes[1] == 4 && sizes[2] == 4 &&
		strings.EqualFold(types[0], "F") && strings.EqualFold(types[1], "F") && strings.EqualFold(types[2], "F") &&
		counts[0] == 1 && counts[1] == 1 && counts[2] == 1
}

func parsePositiveInts(raw []string, label string) ([]int, error) {
	if len(raw) == 0 {
		return nil, fmt.Errorf("%s 缺字段", label)
	}
	out := make([]int, len(raw))
	for i, value := range raw {
		n, err := strconv.Atoi(value)
		if err != nil || n <= 0 {
			return nil, fmt.Errorf("%s 解析失败: %q", label, value)
		}
		out[i] = n
	}
	return out, nil
}

// ContentLength 返回派生 PCD 的确定长度，可直接写 HTTP Content-Length。
func (s *PCDBinarySampleStream) ContentLength() int64 {
	return int64(len(s.header)) + int64(s.samplePoints)*int64(s.recordBytes)
}

// SourcePoints 返回权威 PCD 原始点数。
func (s *PCDBinarySampleStream) SourcePoints() int { return s.sourcePoints }

// SamplePoints 返回派生渲染 PCD 实际记录数。
func (s *PCDBinarySampleStream) SamplePoints() int { return s.samplePoints }

func (s *PCDBinarySampleStream) CoordinateSchema() string { return s.coordinateSchema }

func (s *PCDBinarySampleStream) XYZSHA256() string { return s.xyzSHA256 }

func (s *PCDBinarySampleStream) FinalBToASHA256() string { return s.finalBToASHA256 }

func (s *PCDBinarySampleStream) CanonicalXYZRecords() bool { return s.canonicalXYZRecords }

// ReadSampleVerified 在响应开始前完整读取权威 PCD：一边计算源主体 SHA-256，一边只保留确定性样本。
// measured.pcd 的布局固定为连续 XYZ float32，因此主体 SHA-256 与 MeasuredCloudArtifact.XYZSHA256 相同。
func (s *PCDBinarySampleStream) ReadSampleVerified(expectedXYZSHA256 string) ([]byte, error) {
	if expectedXYZSHA256 != "" && !isSHA256Hex(expectedXYZSHA256) {
		return nil, fmt.Errorf("期望 XYZ SHA-256 非法")
	}
	var body bytes.Buffer
	body.Grow(s.samplePoints * s.recordBytes)
	hash := sha256.New()
	record := make([]byte, s.recordBytes)
	nextSample := -1
	sampleCursor := 0
	if s.samplePoints > 0 {
		nextSample = stratifiedSampleIndex(0, s.samplePoints, s.sourcePoints)
	}
	for sourceIndex := 0; sourceIndex < s.sourcePoints; sourceIndex++ {
		if _, err := io.ReadFull(s.reader, record); err != nil {
			return nil, fmt.Errorf("读取 PCD 记录 %d/%d 失败: %w", sourceIndex, s.sourcePoints, err)
		}
		_, _ = hash.Write(record)
		if sourceIndex != nextSample {
			continue
		}
		if _, err := body.Write(record); err != nil {
			return nil, err
		}
		sampleCursor++
		if sampleCursor < s.samplePoints {
			nextSample = stratifiedSampleIndex(sampleCursor, s.samplePoints, s.sourcePoints)
		} else {
			nextSample = -1
		}
	}
	if sampleCursor != s.samplePoints {
		return nil, fmt.Errorf("PCD 采样不完整：%d/%d", sampleCursor, s.samplePoints)
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if expectedXYZSHA256 != "" && actual != expectedXYZSHA256 {
		return nil, fmt.Errorf("PCD XYZ SHA-256 不一致：期望 %s，实际 %s", expectedXYZSHA256, actual)
	}
	out := make([]byte, 0, len(s.header)+body.Len())
	out = append(out, s.header...)
	out = append(out, body.Bytes()...)
	return out, nil
}

// WriteSampleTo 流式写出派生 PCD；内存上界仅一条原始点记录。
func (s *PCDBinarySampleStream) WriteSampleTo(w io.Writer) error {
	if _, err := w.Write(s.header); err != nil {
		return err
	}
	if s.samplePoints == 0 {
		return nil
	}
	if s.samplePoints == s.sourcePoints {
		_, err := io.CopyN(w, s.reader, int64(s.sourcePoints)*int64(s.recordBytes))
		return err
	}
	record := make([]byte, s.recordBytes)
	cursor := 0
	for i := 0; i < s.samplePoints; i++ {
		sourceIndex := stratifiedSampleIndex(i, s.samplePoints, s.sourcePoints)
		if skip := sourceIndex - cursor; skip > 0 {
			if _, err := io.CopyN(io.Discard, s.reader, int64(skip)*int64(s.recordBytes)); err != nil {
				return fmt.Errorf("跳过 PCD 记录失败: %w", err)
			}
		}
		if _, err := io.ReadFull(s.reader, record); err != nil {
			return fmt.Errorf("读取 PCD 记录失败: %w", err)
		}
		if _, err := w.Write(record); err != nil {
			return err
		}
		cursor = sourceIndex + 1
	}
	return nil
}

func stratifiedSampleIndex(i, samplePoints, sourcePoints int) int {
	start := int(int64(i) * int64(sourcePoints) / int64(samplePoints))
	end := int(int64(i+1) * int64(sourcePoints) / int64(samplePoints))
	width := end - start
	if width <= 1 {
		return start
	}
	seed := uint64(i+1) ^ (uint64(sourcePoints) << 32) ^ uint64(samplePoints)
	seed += 0x9e3779b97f4a7c15
	seed = (seed ^ (seed >> 30)) * 0xbf58476d1ce4e5b9
	seed = (seed ^ (seed >> 27)) * 0x94d049bb133111eb
	seed ^= seed >> 31
	return start + int(seed%uint64(width))
}

// pcd.go = 把累积的 xyz(mm) 点云编码为 PCD（Point Cloud Data v0.7, DATA binary）。
// 标准格式，端侧 Kotlin 用极简解析器（头 + 小端 float32）即可读回 FloatArray。
// 与 lidar io_pcd 同格式（FIELDS x y z / SIZE 4 / TYPE F / binary）。

// EncodePCDBinary 把 [x,y,z, x,y,z, ...] mm 编码为 binary PCD 字节。len 必须是 3 的倍数。
func EncodePCDBinary(xyzMM []float32) ([]byte, error) {
	return encodePCDBinary(xyzMM, -1, nil)
}

// EncodeMeasuredPCDBinary 把内容身份写入 PCD 注释。采样器会保留这些字段，客户端可将下载内容与
// WS/REST 的 MeasuredCloudArtifact 逐项核对。
func EncodeMeasuredPCDBinary(xyzMM []float32, artifact MeasuredCloudArtifact) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	if !artifact.validContentIdentity() {
		return nil, fmt.Errorf("measured artifact 内容身份无效")
	}
	if artifact.SourcePoints != len(xyzMM)/3 {
		return nil, fmt.Errorf("measured artifact 点数 %d != PCD 点数 %d", artifact.SourcePoints, len(xyzMM)/3)
	}
	if actual := cloudFloatSHA256(xyzMM); actual != artifact.XYZSHA256 {
		return nil, fmt.Errorf("measured artifact XYZ SHA-256 不匹配")
	}
	return encodePCDBinary(xyzMM, artifact.SourcePoints, &artifact)
}

func encodePCDBinary(xyzMM []float32, sourcePoints int, artifact *MeasuredCloudArtifact) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	var buf bytes.Buffer
	buf.Grow(int(pcdBinaryContentLength(len(xyzMM)/3, sourcePoints, artifact)))
	if err := writePCDBinary(&buf, xyzMM, sourcePoints, artifact); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func pcdBinaryHeader(points, sourcePoints int, artifact *MeasuredCloudArtifact) string {
	header := ""
	if sourcePoints >= 0 {
		header += pcdSourcePointsComment + strconv.Itoa(sourcePoints) + "\n"
	}
	if artifact != nil {
		header += pcdCoordinateSchemaComment + artifact.CoordinateSchema + "\n"
		header += pcdXYZSHA256Comment + artifact.XYZSHA256 + "\n"
		header += pcdFinalBToASHA256Comment + artifact.FinalBToASHA256 + "\n"
	}
	header += "# .PCD v0.7 - Point Cloud Data file format\n" +
		"VERSION 0.7\n" +
		"FIELDS x y z\n" +
		"SIZE 4 4 4\n" +
		"TYPE F F F\n" +
		"COUNT 1 1 1\n" +
		"WIDTH " + strconv.Itoa(points) + "\n" +
		"HEIGHT 1\n" +
		"VIEWPOINT 0 0 0 1 0 0 0\n" +
		"POINTS " + strconv.Itoa(points) + "\n" +
		"DATA binary\n"
	return header
}

func pcdBinaryContentLength(points, sourcePoints int, artifact *MeasuredCloudArtifact) int64 {
	return int64(len(pcdBinaryHeader(points, sourcePoints, artifact))) + int64(points)*3*4
}

func writePCDBinary(w io.Writer, xyzMM []float32, sourcePoints int, artifact *MeasuredCloudArtifact) error {
	if len(xyzMM)%3 != 0 {
		return fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	if _, err := io.WriteString(w, pcdBinaryHeader(len(xyzMM)/3, sourcePoints, artifact)); err != nil {
		return err
	}
	const floatsPerChunk = 16 * 1024
	chunk := make([]byte, floatsPerChunk*4)
	for offset := 0; offset < len(xyzMM); {
		count := len(xyzMM) - offset
		if count > floatsPerChunk {
			count = floatsPerChunk
		}
		for index := 0; index < count; index++ {
			binary.LittleEndian.PutUint32(chunk[index*4:index*4+4], math.Float32bits(xyzMM[offset+index]))
		}
		if _, err := w.Write(chunk[:count*4]); err != nil {
			return err
		}
		offset += count
	}
	return nil
}

// EncodePCDBinaryXYZI 把 xyz(mm) + 每点属性 attr（这里=采集 h_angle°）编码为
// FIELDS x y z intensity 的 binary PCD。len(xyzMM)==3n、len(attr)==n。
// 端侧据此读回点 + 角度，做"圈 ROI → 反算扫描角"（M9.11）。
func EncodePCDBinaryXYZI(xyzMM, attr []float32) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	n := len(xyzMM) / 3
	if len(attr) != n {
		return nil, fmt.Errorf("attr 长度 %d != 点数 %d", len(attr), n)
	}
	var buf bytes.Buffer
	header := "# .PCD v0.7 - Point Cloud Data file format\n" +
		"VERSION 0.7\n" +
		"FIELDS x y z intensity\n" +
		"SIZE 4 4 4 4\n" +
		"TYPE F F F F\n" +
		"COUNT 1 1 1 1\n" +
		"WIDTH " + strconv.Itoa(n) + "\n" +
		"HEIGHT 1\n" +
		"VIEWPOINT 0 0 0 1 0 0 0\n" +
		"POINTS " + strconv.Itoa(n) + "\n" +
		"DATA binary\n"
	buf.WriteString(header)
	tmp := make([]byte, 4)
	for i := 0; i < n; i++ {
		for _, v := range []float32{xyzMM[3*i], xyzMM[3*i+1], xyzMM[3*i+2], attr[i]} {
			binary.LittleEndian.PutUint32(tmp, math.Float32bits(v))
			buf.Write(tmp)
		}
	}
	return buf.Bytes(), nil
}

// EncodePCDBinaryXYZRGB 把 xyz(mm) + rgb(0xRRGGBB) 编码为 FIELDS x y z rgb。
// rgb 按 PCL 习惯写入 float32 槽位的原始位型；端侧按 24bit 颜色位读取。
func EncodePCDBinaryXYZRGB(xyzMM []float32, rgb []uint32) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	n := len(xyzMM) / 3
	if len(rgb) != n {
		return nil, fmt.Errorf("rgb 长度 %d != 点数 %d", len(rgb), n)
	}
	var buf bytes.Buffer
	header := "# .PCD v0.7 - Point Cloud Data file format\n" +
		"VERSION 0.7\n" +
		"FIELDS x y z rgb\n" +
		"SIZE 4 4 4 4\n" +
		"TYPE F F F F\n" +
		"COUNT 1 1 1 1\n" +
		"WIDTH " + strconv.Itoa(n) + "\n" +
		"HEIGHT 1\n" +
		"VIEWPOINT 0 0 0 1 0 0 0\n" +
		"POINTS " + strconv.Itoa(n) + "\n" +
		"DATA binary\n"
	buf.WriteString(header)
	tmp := make([]byte, 4)
	for i := 0; i < n; i++ {
		for _, v := range []float32{xyzMM[3*i], xyzMM[3*i+1], xyzMM[3*i+2]} {
			binary.LittleEndian.PutUint32(tmp, math.Float32bits(v))
			buf.Write(tmp)
		}
		binary.LittleEndian.PutUint32(tmp, rgb[i]&0x00ffffff)
		buf.Write(tmp)
	}
	return buf.Bytes(), nil
}

// EncodePCDBinaryXYZRGBI 把 xyz(mm) + rgb(0xRRGGBB) + 每点属性 attr 编码为
// FIELDS x y z rgb intensity。rgb 按 PCL 习惯写入 float32 位型，但保持原始 24bit 颜色位。
func EncodePCDBinaryXYZRGBI(xyzMM []float32, rgb []uint32, attr []float32) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	n := len(xyzMM) / 3
	if len(rgb) != n {
		return nil, fmt.Errorf("rgb 长度 %d != 点数 %d", len(rgb), n)
	}
	if len(attr) != n {
		return nil, fmt.Errorf("attr 长度 %d != 点数 %d", len(attr), n)
	}
	var buf bytes.Buffer
	header := "# .PCD v0.7 - Point Cloud Data file format\n" +
		"VERSION 0.7\n" +
		"FIELDS x y z rgb intensity\n" +
		"SIZE 4 4 4 4 4\n" +
		"TYPE F F F F F\n" +
		"COUNT 1 1 1 1 1\n" +
		"WIDTH " + strconv.Itoa(n) + "\n" +
		"HEIGHT 1\n" +
		"VIEWPOINT 0 0 0 1 0 0 0\n" +
		"POINTS " + strconv.Itoa(n) + "\n" +
		"DATA binary\n"
	buf.WriteString(header)
	tmp := make([]byte, 4)
	for i := 0; i < n; i++ {
		for _, v := range []float32{xyzMM[3*i], xyzMM[3*i+1], xyzMM[3*i+2]} {
			binary.LittleEndian.PutUint32(tmp, math.Float32bits(v))
			buf.Write(tmp)
		}
		binary.LittleEndian.PutUint32(tmp, rgb[i]&0x00ffffff)
		buf.Write(tmp)
		binary.LittleEndian.PutUint32(tmp, math.Float32bits(attr[i]))
		buf.Write(tmp)
	}
	return buf.Bytes(), nil
}

// DecodePCDBinaryXYZI 解析 EncodePCDBinaryXYZI 产物，回 (xyz[3n], attr[n])。
func DecodePCDBinaryXYZI(data []byte) (xyz, attr []float32, err error) {
	br := bufio.NewReader(bytes.NewReader(data))
	points := -1
	fields, dataMode := "", ""
	for {
		line, e := br.ReadString('\n')
		if e != nil {
			return nil, nil, fmt.Errorf("PCD 头未见 DATA: %w", e)
		}
		line = strings.TrimRight(line, "\r\n")
		switch {
		case strings.HasPrefix(line, "FIELDS"):
			fields = strings.TrimSpace(strings.TrimPrefix(line, "FIELDS"))
		case strings.HasPrefix(line, "POINTS"):
			points, _ = strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "POINTS")))
		case strings.HasPrefix(line, "DATA"):
			dataMode = strings.TrimSpace(strings.TrimPrefix(line, "DATA"))
		}
		if dataMode != "" {
			break
		}
	}
	if fields != "x y z intensity" {
		return nil, nil, fmt.Errorf("仅支持 FIELDS x y z intensity，得 %q", fields)
	}
	if dataMode != "binary" || points < 0 {
		return nil, nil, fmt.Errorf("仅支持 DATA binary 且需 POINTS，得 mode=%q points=%d", dataMode, points)
	}
	body := make([]byte, points*4*4)
	if _, e := io.ReadFull(br, body); e != nil {
		return nil, nil, fmt.Errorf("读二进制主体失败(期望 %d 点): %w", points, e)
	}
	xyz = make([]float32, points*3)
	attr = make([]float32, points)
	for i := 0; i < points; i++ {
		b := i * 16
		xyz[3*i] = math.Float32frombits(binary.LittleEndian.Uint32(body[b : b+4]))
		xyz[3*i+1] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+4 : b+8]))
		xyz[3*i+2] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+8 : b+12]))
		attr[i] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+12 : b+16]))
	}
	return xyz, attr, nil
}

// DecodePCDBinary 解析本系统写出的 binary PCD，回 [x,y,z,...]；允许 rgb/intensity 等附加字段并忽略。
func DecodePCDBinary(data []byte) ([]float32, error) {
	br := bufio.NewReader(bytes.NewReader(data))
	points := -1
	var fields []string
	var sizes []int
	dataMode := ""
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("PCD 头未见 DATA: %w", err)
		}
		line = strings.TrimRight(line, "\r\n")
		parts := strings.Fields(line)
		if len(parts) == 0 {
			continue
		}
		switch parts[0] {
		case "FIELDS":
			fields = parts[1:]
		case "SIZE":
			if len(parts) < 2 {
				return nil, fmt.Errorf("SIZE 缺字段")
			}
			for _, raw := range parts[1:] {
				n, perr := strconv.Atoi(raw)
				if perr != nil {
					return nil, fmt.Errorf("SIZE 解析失败: %w", perr)
				}
				sizes = append(sizes, n)
			}
		case "POINTS":
			if len(parts) < 2 {
				return nil, fmt.Errorf("POINTS 缺数量")
			}
			p, perr := strconv.Atoi(parts[1])
			if perr != nil {
				return nil, fmt.Errorf("POINTS 解析失败: %w", perr)
			}
			points = p
		case "DATA":
			if len(parts) > 1 {
				dataMode = parts[1]
			}
		}
		if dataMode != "" {
			break
		}
	}
	if dataMode != "binary" {
		return nil, fmt.Errorf("仅支持 DATA binary，得 %q", dataMode)
	}
	if points < 0 {
		return nil, fmt.Errorf("缺 POINTS")
	}
	if len(fields) == 0 || len(sizes) != len(fields) {
		return nil, fmt.Errorf("缺 FIELDS/SIZE")
	}
	step, ox, oy, oz := 0, -1, -1, -1
	for i, field := range fields {
		switch field {
		case "x":
			ox = step
		case "y":
			oy = step
		case "z":
			oz = step
		}
		step += sizes[i]
	}
	if step <= 0 || ox < 0 || oy < 0 || oz < 0 {
		return nil, fmt.Errorf("PCD 缺 x/y/z 字段")
	}
	body := make([]byte, points*step)
	if _, err := io.ReadFull(br, body); err != nil {
		return nil, fmt.Errorf("读二进制主体失败(期望 %d 点): %w", points, err)
	}
	out := make([]float32, points*3)
	for i := 0; i < points; i++ {
		b := i * step
		out[3*i] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+ox : b+ox+4]))
		out[3*i+1] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+oy : b+oy+4]))
		out[3*i+2] = math.Float32frombits(binary.LittleEndian.Uint32(body[b+oz : b+oz+4]))
	}
	return out, nil
}
