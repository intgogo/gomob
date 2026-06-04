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
