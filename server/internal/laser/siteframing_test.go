package laser

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"testing"
)

// putU32BE/record/frameRecord 复刻 framing_stream.cpp 的二进制帧协议，合成喂给解析器。
func putU32BE(buf *bytes.Buffer, v uint32) {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	buf.Write(b[:])
}

// record = [4B N][1B type][N payload]
func record(buf *bytes.Buffer, typ byte, payload []byte) {
	putU32BE(buf, uint32(len(payload)))
	buf.WriteByte(typ)
	buf.Write(payload)
}

// 'm' payload = [4B metaLen][meta json][jpeg]
func frameRecord(buf *bytes.Buffer, meta string, jpeg []byte) {
	var p bytes.Buffer
	putU32BE(&p, uint32(len(meta)))
	p.WriteString(meta)
	p.Write(jpeg)
	record(buf, 'm', p.Bytes())
}

func TestReadFramingRecordsParsesSequence(t *testing.T) {
	var wire bytes.Buffer
	record(&wire, 's', []byte(`{"ev":"ready"}`))
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02, 0x03} // 假 JPEG SOI + 数据
	meta := `{"unit":1,"seq":7,"heading":33.5,"w":1280,"h":720,"markers":[{"id":5,"px":[[10,20],[30,20],[30,40],[10,40]]}]}`
	frameRecord(&wire, meta, jpeg)
	record(&wire, 'r', []byte(`{"ok":true,"n_common":6,"rms_m":0.004}`))

	var types []byte
	var framePayload []byte
	err := readFramingRecords(&wire, func(typ byte, payload []byte) {
		types = append(types, typ)
		if typ == 'm' {
			framePayload = append([]byte(nil), payload...)
		}
	})
	if err != nil {
		t.Fatalf("readFramingRecords 出错: %v", err)
	}
	if string(types) != "smr" {
		t.Fatalf("记录类型序列应为 s,m,r，得 %q", string(types))
	}

	msg, ok := decodeFrameRecord(framePayload, 42, "site-framing")
	if !ok {
		t.Fatal("decodeFrameRecord 失败")
	}
	if msg.Unit != 1 || msg.Seq != 7 || msg.W != 1280 || msg.H != 720 {
		t.Fatalf("帧元数据解析错: %+v", msg)
	}
	if msg.HeadingDeg < 33.4 || msg.HeadingDeg > 33.6 {
		t.Fatalf("heading 解析错: %v", msg.HeadingDeg)
	}
	if msg.OwnerUserID == nil || *msg.OwnerUserID != 42 {
		t.Fatalf("owner 路由字段错: %+v", msg.OwnerUserID)
	}
	if len(msg.Markers) != 1 || msg.Markers[0].ID != 5 || len(msg.Markers[0].PX) != 4 {
		t.Fatalf("标记检测解析错: %+v", msg.Markers)
	}
	if msg.Markers[0].PX[1][0] != 30 || msg.Markers[0].PX[2][1] != 40 {
		t.Fatalf("标记角点像素解析错: %+v", msg.Markers[0].PX)
	}
	// jpeg 应原样 base64 往返。
	gotJPEG, err := base64.StdEncoding.DecodeString(msg.JPEGB64)
	if err != nil || !bytes.Equal(gotJPEG, jpeg) {
		t.Fatalf("jpeg base64 往返错: err=%v got=%v", err, gotJPEG)
	}

	// 载荷可 JSON 序列化（NATS 发布路径）。
	if _, err := json.Marshal(msg); err != nil {
		t.Fatalf("LaserFrameMsg 不可序列化: %v", err)
	}
}

func TestDecodeFrameRecordRejectsTruncated(t *testing.T) {
	if _, ok := decodeFrameRecord([]byte{0x00, 0x01}, 1, "s"); ok {
		t.Fatal("过短 payload 应拒绝")
	}
	// metaLen 越界。
	var p bytes.Buffer
	putU32BE(&p, 9999)
	p.WriteString("short")
	if _, ok := decodeFrameRecord(p.Bytes(), 1, "s"); ok {
		t.Fatal("metaLen 越界应拒绝")
	}
}
