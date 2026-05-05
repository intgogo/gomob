package pack

import (
	"encoding/json"
	"fmt"
	"io.gomob/server/internal/cvengine/util/conv"
	"io.gomob/server/internal/cvengine/util/crypt"
	"io.gomob/server/internal/cvengine/util/fs"
	"io/ioutil"
	"path"
)

type FileIndex struct {
	FileName string
	FileLen  int
	FilePos  int
}

func PackDir(src, dst, key string) error {
	files, err := fs.GetFiles(src, false)
	if err != nil {
		return err
	}

	var buf []byte
	aa := make([]FileIndex, len(files))

	for i, f := range files {
		content, err := ioutil.ReadFile(f.Name)
		if err != nil || len(content) == 0 {
			return err
		}
		aa[i].FileName = path.Base(f.Name)
		aa[i].FileLen = len(content)
		aa[i].FilePos = len(buf)

		buf = append(buf, content...)
	}

	bb, _ := json.Marshal(aa)
	buf = append(buf, bb...)

	cc := conv.Int64ToBytes(int64(len(bb)))
	buf = append(buf, cc...)

	if key != "" {
		buf, err = crypt.AesEncrypt(buf, []byte(key))
		if err != nil {
			return err
		}
	}
	ioutil.WriteFile(dst, buf, 0777)
	return nil
}

func UnpackDir(srcFile, dstDir, key string) (map[string][]byte, error) {
	filesMap := make(map[string][]byte)
	buf, err := ioutil.ReadFile(srcFile)
	if err != nil {
		return filesMap, err
	}
	if len(buf) < 128 {
		return filesMap, fmt.Errorf("bad %s content len(%d)", srcFile, len(buf))
	}
	if key != "" {
		buf, err = crypt.AesDecrypt(buf, []byte(key))
		if err != nil {
			return filesMap, err
		}
	}

	bufLen := len(buf)
	cc := buf[bufLen-8:]
	bbLen := int(conv.BytesToInt64(cc))

	bb := buf[bufLen-8-bbLen : bufLen-8]

	var aa []FileIndex
	err = json.Unmarshal(bb, &aa)
	if err != nil {
		return filesMap, err
	}

	for _, a := range aa {
		filesMap[a.FileName] = buf[a.FilePos : a.FilePos+a.FileLen]
		if dstDir != "" {
			ioutil.WriteFile(path.Join(dstDir, a.FileName), filesMap[a.FileName], 0777)
		}
	}
	return filesMap, nil
}

func PackFile(src, dst, key string) error {
	buf, err := ioutil.ReadFile(src)
	if err != nil || len(buf) == 0 {
		return err
	}

	if key != "" {
		buf, err = crypt.AesEncrypt(buf, []byte(key))
		if err != nil {
			return err
		}
	}
	ioutil.WriteFile(dst, buf, 0777)
	return nil
}

func UnpackFile(srcFile, dstFile, key string) ([]byte, error) {
	buf, err := ioutil.ReadFile(srcFile)
	if err != nil {
		return nil, err
	}
	if key != "" {
		buf, err = crypt.AesDecrypt(buf, []byte(key))
		if err != nil {
			return nil, err
		}
	}
	if dstFile != "" {
		ioutil.WriteFile(dstFile, buf, 0777)
	}
	return buf, nil
}
