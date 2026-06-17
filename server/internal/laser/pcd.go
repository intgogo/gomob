package laser

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"
)

// pcd.go = 把累积的 xyz(mm) 点云编码为 PCD（Point Cloud Data v0.7, DATA binary）。
// 标准格式，端侧 Kotlin 用极简解析器（头 + 小端 float32）即可读回 FloatArray。
// 与 lidar io_pcd 同格式（FIELDS x y z / SIZE 4 / TYPE F / binary）。

// EncodePCDBinary 把 [x,y,z, x,y,z, ...] mm 编码为 binary PCD 字节。len 必须是 3 的倍数。
func EncodePCDBinary(xyzMM []float32) ([]byte, error) {
	if len(xyzMM)%3 != 0 {
		return nil, fmt.Errorf("点数据长度 %d 不是 3 的倍数", len(xyzMM))
	}
	n := len(xyzMM) / 3
	var buf bytes.Buffer
	// ASCII 头。
	header := "# .PCD v0.7 - Point Cloud Data file format\n" +
		"VERSION 0.7\n" +
		"FIELDS x y z\n" +
		"SIZE 4 4 4\n" +
		"TYPE F F F\n" +
		"COUNT 1 1 1\n" +
		"WIDTH " + strconv.Itoa(n) + "\n" +
		"HEIGHT 1\n" +
		"VIEWPOINT 0 0 0 1 0 0 0\n" +
		"POINTS " + strconv.Itoa(n) + "\n" +
		"DATA binary\n"
	buf.WriteString(header)
	// 小端 float32 主体。
	tmp := make([]byte, 4)
	for _, v := range xyzMM {
		binary.LittleEndian.PutUint32(tmp, math.Float32bits(v))
		buf.Write(tmp)
	}
	return buf.Bytes(), nil
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

// DecodePCDBinary 解析 EncodePCDBinary 产物，回 [x,y,z,...]。供测试 + Go 侧回看；
// 仅支持本编码器产出的 FIELDS x y z / binary 形态（非通用 PCD 解析器）。
func DecodePCDBinary(data []byte) ([]float32, error) {
	br := bufio.NewReader(bytes.NewReader(data))
	points := -1
	fields := ""
	dataMode := ""
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("PCD 头未见 DATA: %w", err)
		}
		line = strings.TrimRight(line, "\r\n")
		switch {
		case strings.HasPrefix(line, "FIELDS"):
			fields = strings.TrimSpace(strings.TrimPrefix(line, "FIELDS"))
		case strings.HasPrefix(line, "POINTS"):
			p, perr := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "POINTS")))
			if perr != nil {
				return nil, fmt.Errorf("POINTS 解析失败: %w", perr)
			}
			points = p
		case strings.HasPrefix(line, "DATA"):
			dataMode = strings.TrimSpace(strings.TrimPrefix(line, "DATA"))
		}
		if dataMode != "" {
			break
		}
	}
	if fields != "x y z" {
		return nil, fmt.Errorf("仅支持 FIELDS x y z，得 %q", fields)
	}
	if dataMode != "binary" {
		return nil, fmt.Errorf("仅支持 DATA binary，得 %q", dataMode)
	}
	if points < 0 {
		return nil, fmt.Errorf("缺 POINTS")
	}
	body := make([]byte, points*3*4)
	if _, err := io.ReadFull(br, body); err != nil {
		return nil, fmt.Errorf("读二进制主体失败(期望 %d 点): %w", points, err)
	}
	out := make([]float32, points*3)
	for i := range out {
		out[i] = math.Float32frombits(binary.LittleEndian.Uint32(body[i*4 : i*4+4]))
	}
	return out, nil
}
