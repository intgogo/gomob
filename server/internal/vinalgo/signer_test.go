package vinalgo

import (
	"crypto"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestRSASignerUsesSHA1PKCS1v15(t *testing.T) {
	key := generateTestKey(t)
	signer, err := NewRSASignerFromPEM(testPrivateKeyPEM(t, key))
	if err != nil {
		t.Fatal(err)
	}
	const nanos = int64(123456789)
	hexSignature, err := signer.Sign(nanos)
	if err != nil {
		t.Fatal(err)
	}
	signature, err := hex.DecodeString(hexSignature)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha1.Sum([]byte(strconv.FormatInt(nanos, 10)))
	if err := rsa.VerifyPKCS1v15(&key.PublicKey, crypto.SHA1, sum[:], signature); err != nil {
		t.Fatalf("签名验签失败：%v", err)
	}
}

func TestRSASignerLoadsDeploymentFile(t *testing.T) {
	keyPath := filepath.Join(t.TempDir(), "vin-algo.pem")
	if err := os.WriteFile(keyPath, testPrivateKeyPEM(t, generateTestKey(t)), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewRSASignerFromFile(keyPath); err != nil {
		t.Fatalf("部署密钥文件应可加载：%v", err)
	}
}

func TestRSASignerRejectsInvalidDeploymentFiles(t *testing.T) {
	dir := t.TempDir()
	if _, err := NewRSASignerFromFile(filepath.Join(dir, "missing.pem")); err == nil {
		t.Fatal("不存在的部署密钥应被拒绝")
	}

	invalidPath := filepath.Join(dir, "invalid.pem")
	if err := os.WriteFile(invalidPath, []byte("not a pem"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewRSASignerFromFile(invalidPath); err == nil {
		t.Fatal("非法 PEM 应被拒绝")
	}

	_, nonRSAKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	nonRSABytes, err := x509.MarshalPKCS8PrivateKey(nonRSAKey)
	if err != nil {
		t.Fatal(err)
	}
	nonRSAPath := filepath.Join(dir, "non-rsa.pem")
	if err := os.WriteFile(nonRSAPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: nonRSABytes}), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewRSASignerFromFile(nonRSAPath); err == nil {
		t.Fatal("非 RSA 私钥应被拒绝")
	}
}

func generateTestKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func testPrivateKeyPEM(t *testing.T, key *rsa.PrivateKey) []byte {
	t.Helper()
	return pem.EncodeToMemory(&pem.Block{
		Type:  "PRIVATE KEY",
		Bytes: mustMarshalPKCS8(t, key),
	})
}

func mustMarshalPKCS8(t *testing.T, key *rsa.PrivateKey) []byte {
	t.Helper()
	data, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return data
}
