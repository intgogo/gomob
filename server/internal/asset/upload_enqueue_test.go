//go:build e2e_fusion

// 集成测(需 pg/redis/minio;由 tests/harness/scan_fusion_e2e/run.sh 驱动):
// 真实走 asset 分片上传 init/part/complete(kind=scan3d_bundle)→ 断言上传完成即入队 scan_fusion_jobs。
package asset

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"io.gomob/server/pkg/repo"
)

func TestUploadBundleEnqueuesFusion(t *testing.T) {
	ctx := context.Background()
	must := func(err error, msg string) {
		if err != nil {
			t.Fatalf("%s: %v", msg, err)
		}
	}
	env := func(k, d string) string {
		if v := os.Getenv(k); v != "" {
			return v
		}
		return d
	}
	bundlePath := os.Getenv("GOMOB_E2E_BUNDLE_PATH")
	if bundlePath == "" {
		t.Skip("缺 GOMOB_E2E_BUNDLE_PATH")
	}
	bundle, err := os.ReadFile(bundlePath)
	must(err, "读 bundle")

	pool, err := repo.NewPool(ctx)
	must(err, "连 PG")
	defer pool.Close()

	// seed 用户(upload_sessions.user_id FK)
	var userID int64
	must(pool.QueryRow(ctx, `
		INSERT INTO users(username, real_name, employee_id, password_hash, role, status)
		VALUES('e2e_fusion','e2e 融合','E2E-FUSION','x','inspector','active')
		ON CONFLICT (username) DO UPDATE SET status='active'
		RETURNING id`).Scan(&userID), "seed 用户")

	rdb := redis.NewClient(&redis.Options{Addr: env("GOMOB_REDIS_ADDR", "127.0.0.1:16379")})
	defer rdb.Close()

	cfg := DefaultConfig()
	cfg.MinIOEndpoint = env("GOMOB_MINIO_ENDPOINT", "127.0.0.1:19000")
	cfg.Bucket = env("GOMOB_MINIO_BUCKET", "gomob-assets")
	h, err := NewHandler(cfg, pool, rdb, nil)
	must(err, "建 asset handler")
	mux := http.NewServeMux()
	h.Mount(mux)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	cli := &http.Client{Timeout: 30 * time.Second}
	do := func(method, path string, body []byte, hdr map[string]string) *http.Response {
		req, err := http.NewRequest(method, srv.URL+path, bytes.NewReader(body))
		must(err, method+" "+path)
		req.Header.Set("X-Gomob-User-Id", fmt.Sprintf("%d", userID))
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		resp, err := cli.Do(req)
		must(err, "do "+path)
		return resp
	}
	decode := func(resp *http.Response, out any) {
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s 非 200: %d", resp.Request.URL.Path, resp.StatusCode)
		}
		var env struct {
			Data json.RawMessage `json:"data"`
		}
		must(json.NewDecoder(resp.Body).Decode(&env), "decode envelope")
		must(json.Unmarshal(env.Data, out), "decode data")
	}

	sum := sha256.Sum256(bundle)
	session := fmt.Sprintf("e2e-upload-%d", time.Now().UnixNano())

	// init
	initBody, _ := json.Marshal(map[string]any{
		"kind": KindScan3DBundle, "size_bytes": len(bundle),
		"sha256": hex.EncodeToString(sum[:]), "mime": "application/zip",
	})
	var initResp struct {
		UploadID  string `json:"upload_id"`
		ChunkSize int    `json:"chunk_size"`
	}
	decode(do(http.MethodPost, "/v1/assets/upload/init", initBody, nil), &initResp)
	if initResp.UploadID == "" {
		t.Fatal("init 未返回 upload_id")
	}
	if len(bundle) > initResp.ChunkSize {
		t.Fatalf("bundle %d > chunk %d,本测假设单片", len(bundle), initResp.ChunkSize)
	}

	// part 1
	pResp := do(http.MethodPut, "/v1/assets/upload/"+initResp.UploadID+"/chunk/1", bundle, nil)
	pResp.Body.Close()
	if pResp.StatusCode != http.StatusOK {
		t.Fatalf("part 非 200: %d", pResp.StatusCode)
	}

	// complete(带 scan_session_id + frame_count)
	cBody, _ := json.Marshal(map[string]any{
		"total_chunks": 1, "scan_session_id": session, "frame_count": 10,
	})
	var cResp struct {
		ObjectKey string `json:"object_key"`
	}
	decode(do(http.MethodPost, "/v1/assets/upload/"+initResp.UploadID+"/complete", cBody, nil), &cResp)
	t.Logf("上传完成 object_key=%s", cResp.ObjectKey)

	// 断言自动入队
	job, err := repo.NewScanFusionRepo(pool).FindBySessionKey(ctx, session)
	must(err, "查 scan_fusion_jobs(应已入队)")
	if job.Status != repo.ScanFusionStatusPending {
		t.Fatalf("入队状态=%s 非 pending", job.Status)
	}
	if job.InputObjectKey != cResp.ObjectKey {
		t.Fatalf("input_object_key=%s 与上传 object_key=%s 不符", job.InputObjectKey, cResp.ObjectKey)
	}
	if job.FrameCount != 10 {
		t.Fatalf("frame_count=%d 期望 10", job.FrameCount)
	}
	t.Logf("✓ 上传完成自动入队:job=%d session=%s input=%s frames=%d",
		job.ID, job.SessionKey, job.InputObjectKey, job.FrameCount)
}
