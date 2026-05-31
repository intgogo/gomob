//go:build e2e_fusion

// 端到端集成测(需 pg/nats/minio + fusion_service 在跑;由 tests/harness/scan_fusion_e2e/run.sh 驱动)。
// 路径:上传 bundle 入 MinIO → 入队 → worker.ProcessOne → 断言 DB done + scan.fusion_done 事件 + GLB 落 MinIO。
package fusion

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"testing"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func TestFusionE2E(t *testing.T) {
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

	session := os.Getenv("GOMOB_E2E_SESSION")
	bundlePath := os.Getenv("GOMOB_E2E_BUNDLE_PATH")
	bundleKey := os.Getenv("GOMOB_E2E_BUNDLE_KEY")
	resultPath := os.Getenv("GOMOB_E2E_RESULT_PATH")
	fusionURL := os.Getenv("GOMOB_FUSION_URL")
	if session == "" || bundlePath == "" || fusionURL == "" {
		t.Skip("缺 e2e 环境变量(GOMOB_E2E_SESSION/GOMOB_E2E_BUNDLE_PATH/GOMOB_FUSION_URL)")
	}

	pool, err := repo.NewPool(ctx)
	must(err, "连 PG")
	defer pool.Close()
	jobs := repo.NewScanFusionRepo(pool)

	endpoint := env("GOMOB_MINIO_ENDPOINT", "127.0.0.1:19000")
	bucket := env("GOMOB_MINIO_BUCKET", "gomob-assets")
	mc, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(env("GOMOB_MINIO_ACCESS_KEY", "gomob"), env("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"), ""),
		Secure: os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
	})
	must(err, "MinIO 客户端")
	if ok, _ := mc.BucketExists(ctx, bucket); !ok {
		must(mc.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}), "建 bucket")
	}

	// 1) 上传 bundle 入 MinIO(模拟端侧 asset upload 完成)
	bundle, err := os.ReadFile(bundlePath)
	must(err, "读 bundle")
	_, err = mc.PutObject(ctx, bucket, bundleKey, bytes.NewReader(bundle), int64(len(bundle)), minio.PutObjectOptions{ContentType: "application/zip"})
	must(err, "上传 bundle")
	t.Logf("上传 bundle %d 字节 → %s/%s", len(bundle), bucket, bundleKey)

	// 2) 入队
	job, err := jobs.Enqueue(ctx, session, bundleKey, nil, 10)
	must(err, "入队")
	t.Logf("入队 job=%d session=%s status=%s", job.ID, session, job.Status)

	// 3) 订阅 scan.fusion_done
	pub, err := pubsub.NewNATS(os.Getenv("GOMOB_NATS_URL"))
	must(err, "连 NATS")
	defer pub.Close()
	sub, err := pub.Conn().SubscribeSync(TopicFusionDone)
	must(err, "订阅 scan.fusion_done")

	// 4) worker 单步
	w, err := NewWorker(pool, pub, Config{
		ServiceURL: fusionURL, MinIOEndpoint: endpoint, Bucket: bucket,
		MinIOAccessKey: env("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey: env("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		EnableConfidence: false,
	})
	must(err, "建 worker")
	// 排空队列直到本 session 终态:ClaimNext 是 FIFO,队列里可能有其它 pending(如 5a 真实上传遗留),
	// 单次 ProcessOne 未必处理到本任务,故循环排空(顺带清理遗留 pending)。
	var got *repo.ScanFusionJob
	for i := 0; i < 20; i++ {
		w.ProcessOne(ctx)
		got, err = jobs.FindBySessionKey(ctx, session)
		must(err, "查 job")
		if got.Status == repo.ScanFusionStatusDone || got.Status == repo.ScanFusionStatusFailed {
			break
		}
	}

	// 5) 断言 DB done
	if got == nil || got.Status != repo.ScanFusionStatusDone {
		em := ""
		if got != nil && got.ErrorMessage != nil {
			em = *got.ErrorMessage
		}
		st := "<nil>"
		if got != nil {
			st = got.Status
		}
		t.Fatalf("排空后本任务状态=%s 非 done;err=%q", st, em)
	}
	if got.ResultObjectKey == nil || got.Vertices == nil || *got.Vertices < 1000 {
		t.Fatalf("结果不完整:result=%v vertices=%v", got.ResultObjectKey, got.Vertices)
	}
	tri := 0
	if got.Triangles != nil {
		tri = *got.Triangles
	}
	t.Logf("✓ DB done:result=%s vertices=%d triangles=%d stats=%s", *got.ResultObjectKey, *got.Vertices, tri, string(got.Stats))

	// 6) 断言 scan.fusion_done 事件(排空可能先收到遗留任务的事件,循环匹配本 session)
	var evt FusionDoneEvent
	matched := false
	for i := 0; i < 20; i++ {
		msg, err := sub.NextMsg(15 * time.Second)
		must(err, "等 scan.fusion_done")
		must(json.Unmarshal(msg.Data, &evt), "解析事件")
		if evt.SessionKey == session {
			matched = true
			break
		}
	}
	if !matched || evt.ResultObjectKey != *got.ResultObjectKey || evt.Vertices != *got.Vertices {
		t.Fatalf("未收到本 session 的 scan.fusion_done 或字段不符:%+v", evt)
	}
	t.Logf("✓ scan.fusion_done:%+v", evt)

	// 7) 下载 GLB 落盘供 verify.py 做几何复核
	obj, err := mc.GetObject(ctx, bucket, *got.ResultObjectKey, minio.GetObjectOptions{})
	must(err, "取 GLB")
	defer obj.Close()
	glb, err := io.ReadAll(obj)
	must(err, "读 GLB")
	if resultPath != "" {
		must(os.WriteFile(resultPath, glb, 0o644), "写 GLB")
	}
	t.Logf("✓ GLB %d 字节 → %s", len(glb), resultPath)
}
