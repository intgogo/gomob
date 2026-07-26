package vinalgo

import (
	"crypto/rsa"
	"crypto/sha1"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"math/big"
	"os"
	"strconv"
)

type rsaSHA1Signer struct {
	key *rsa.PrivateKey
}

var sha1DigestInfoPrefix = []byte{
	0x30, 0x21,
	0x30, 0x09,
	0x06, 0x05,
	0x2b, 0x0e, 0x03, 0x02, 0x1a,
	0x05, 0x00,
	0x04, 0x14,
}

// NewRSASignerFromFile 从部署密钥文件加载 PKCS#8 或 PKCS#1 RSA 私钥。
func NewRSASignerFromFile(path string) (Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return NewRSASignerFromPEM(data)
}

// NewRSASignerFromPEM 从 PEM 字节加载 RSA 私钥。
func NewRSASignerFromPEM(data []byte) (Signer, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("RSA 私钥 PEM 解析失败")
	}
	if keyAny, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		key, ok := keyAny.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("PKCS#8 私钥不是 RSA 类型")
		}
		return rsaSHA1Signer{key: key}, nil
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return rsaSHA1Signer{key: key}, nil
	}
	return nil, errors.New("RSA 私钥格式不支持")
}

func (s rsaSHA1Signer) Sign(nanos int64) (string, error) {
	sum := sha1.Sum([]byte(strconv.FormatInt(nanos, 10)))
	// 现场协议仍使用旧 512-bit RSA，标准库会拒绝该尺寸，因此按 RFC 8017 完成兼容签名。
	signature, err := manualRSASignPKCS1v15(s.key, sum[:])
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(signature), nil
}

func manualRSASignPKCS1v15(key *rsa.PrivateKey, digest []byte) ([]byte, error) {
	if key == nil || key.N == nil || key.D == nil {
		return nil, errors.New("RSA 私钥不完整")
	}
	if len(digest) != sha1.Size {
		return nil, errors.New("SHA1 摘要长度错误")
	}
	keySize := (key.N.BitLen() + 7) / 8
	paddingLength := keySize - 3 - len(sha1DigestInfoPrefix) - len(digest)
	if paddingLength < 8 {
		return nil, errors.New("RSA 私钥尺寸不足以完成 PKCS#1 v1.5 签名")
	}
	encoded := make([]byte, keySize)
	encoded[0] = 0
	encoded[1] = 1
	for i := 0; i < paddingLength; i++ {
		encoded[2+i] = 0xff
	}
	offset := 2 + paddingLength
	encoded[offset] = 0
	offset++
	copy(encoded[offset:], sha1DigestInfoPrefix)
	offset += len(sha1DigestInfoPrefix)
	copy(encoded[offset:], digest)

	message := new(big.Int).SetBytes(encoded)
	if message.Cmp(key.N) >= 0 {
		return nil, errors.New("PKCS#1 编码值超出 RSA 模数")
	}
	signed := new(big.Int).Exp(message, key.D, key.N).Bytes()
	result := make([]byte, keySize)
	copy(result[keySize-len(signed):], signed)
	return result, nil
}
