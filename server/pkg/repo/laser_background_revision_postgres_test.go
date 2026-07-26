package repo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestActivateAndCompletePostgresTransactions(t *testing.T) {
	pool := openLaserBackgroundTestPool(t)

	t.Run("reset preserves unrelated station", func(t *testing.T) {
		resetLaserBackgroundTestTables(t, pool)
		ctx := context.Background()
		scans := NewLaserScanRepo(pool)
		job, err := scans.Create(
			ctx,
			fmt.Sprintf("laser-bg-unrelated-%d", time.Now().UnixNano()),
			"192.0.2.101",
			"192.0.2.102",
			"site",
			1,
			nil,
			nil,
		)
		if err != nil {
			t.Fatalf("创建无关工位哨兵任务失败: %v", err)
		}
		_, err = pool.Exec(ctx, `
			INSERT INTO laser_site_calibration (
				unit_a_ip, unit_b_ip, site_json, source, source_scan_id, updated_at
			) VALUES (
				'192.0.2.101', '192.0.2.102',
				'{"b_to_a":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}',
				'test_isolation_guard', $1, now()
			)`, job.ID)
		if err != nil {
			t.Fatalf("创建无关工位外参哨兵失败: %v", err)
		}

		resetLaserBackgroundTestTables(t, pool)
		if _, err := scans.FindByID(ctx, job.ID); err != nil {
			t.Fatalf("测试清理误删无关扫描任务: %v", err)
		}
		var siteCount int
		if err := pool.QueryRow(ctx, `
			SELECT count(*) FROM laser_site_calibration
			WHERE unit_a_ip='192.0.2.101' AND unit_b_ip='192.0.2.102'`,
		).Scan(&siteCount); err != nil {
			t.Fatalf("读取无关工位外参哨兵失败: %v", err)
		}
		if siteCount != 1 {
			t.Fatalf("测试清理误删无关工位外参: count=%d", siteCount)
		}
		if _, err := pool.Exec(ctx, `
			DELETE FROM laser_site_calibration
			WHERE unit_a_ip='192.0.2.101' AND unit_b_ip='192.0.2.102'`); err != nil {
			t.Fatalf("清理无关工位外参哨兵失败: %v", err)
		}
		if _, err := pool.Exec(ctx, `DELETE FROM laser_scan_jobs WHERE id=$1`, job.ID); err != nil {
			t.Fatalf("清理无关工位哨兵失败: %v", err)
		}
		t.Log("TX_CASE:reset_scope_preserved:PASS")
	})

	t.Run("cancel wins", func(t *testing.T) {
		resetLaserBackgroundTestTables(t, pool)
		scans, backgrounds, job, old := seedLaserBackgroundTestState(t, pool, "cancel", 1)
		if _, err := scans.Cancel(context.Background(), job.ID); err != nil {
			t.Fatalf("预先取消任务失败: %v", err)
		}
		_, _, err := backgrounds.ActivateAndComplete(
			context.Background(), job.ID, testBackgroundCompletion(), testRawBackgroundRevision(job),
		)
		if !errors.Is(err, ErrNotFound) {
			t.Fatalf("取消先赢时完成 CAS 必须失败，得 %v", err)
		}
		assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusCancelled, old.ID, 1, false)
		t.Log("TX_CASE:cancel_wins:PASS")
	})

	t.Run("complete wins", func(t *testing.T) {
		resetLaserBackgroundTestTables(t, pool)
		scans, backgrounds, job, _ := seedLaserBackgroundTestState(t, pool, "complete", 2)
		completed, activated, err := backgrounds.ActivateAndComplete(
			context.Background(), job.ID, testBackgroundCompletion(), testRawBackgroundRevision(job),
		)
		if err != nil {
			t.Fatalf("背景完成事务失败: %v", err)
		}
		if _, err := scans.Cancel(context.Background(), job.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("完成先赢后取消 CAS 必须失败，得 %v", err)
		}
		if completed.Status != LaserScanStatusDone || activated.SourceScanID == nil || *activated.SourceScanID != job.ID {
			t.Fatalf("完成结果错误: job=%+v revision=%+v", completed, activated)
		}
		assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusDone, activated.ID, 2, true)
		t.Log("TX_CASE:complete_wins:PASS")
	})

	t.Run("insert failure rolls back", func(t *testing.T) {
		resetLaserBackgroundTestTables(t, pool)
		_, backgrounds, job, old := seedLaserBackgroundTestState(t, pool, "insert", 3)
		ctx := context.Background()
		_, err := pool.Exec(ctx, `
			CREATE OR REPLACE FUNCTION laser_bg_tx_reject_new_revision() RETURNS trigger AS $$
			BEGIN
				IF NEW.source_scan_id IS NOT NULL THEN
					RAISE EXCEPTION 'injected background insert failure';
				END IF;
				RETURN NEW;
			END;
			$$ LANGUAGE plpgsql;
			CREATE TRIGGER laser_bg_tx_reject_new_revision
			BEFORE INSERT ON laser_background_revision
			FOR EACH ROW EXECUTE FUNCTION laser_bg_tx_reject_new_revision();
		`)
		if err != nil {
			t.Fatalf("创建插入故障注入器失败: %v", err)
		}
		defer func() {
			_, _ = pool.Exec(context.Background(), `
				DROP TRIGGER IF EXISTS laser_bg_tx_reject_new_revision ON laser_background_revision;
				DROP FUNCTION IF EXISTS laser_bg_tx_reject_new_revision();
			`)
		}()

		_, _, err = backgrounds.ActivateAndComplete(
			ctx, job.ID, testBackgroundCompletion(), testRawBackgroundRevision(job),
		)
		if err == nil {
			t.Fatal("revision 插入失败必须使整个事务失败")
		}
		assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusFusing, old.ID, 1, false)
		t.Log("TX_CASE:insert_failure_rollback:PASS")
	})

	t.Run("commit failure rolls back", func(t *testing.T) {
		resetLaserBackgroundTestTables(t, pool)
		_, backgrounds, job, old := seedLaserBackgroundTestState(t, pool, "commit", 4)
		ctx := context.Background()
		_, err := pool.Exec(ctx, `
			CREATE TABLE laser_bg_tx_allowed_source (id BIGINT PRIMARY KEY);
			ALTER TABLE laser_background_revision
				ADD CONSTRAINT laser_bg_tx_deferred_source
				FOREIGN KEY (source_scan_id) REFERENCES laser_bg_tx_allowed_source(id)
				DEFERRABLE INITIALLY DEFERRED;
		`)
		if err != nil {
			t.Fatalf("创建提交故障注入器失败: %v", err)
		}
		defer func() {
			_, _ = pool.Exec(context.Background(), `
				ALTER TABLE laser_background_revision DROP CONSTRAINT IF EXISTS laser_bg_tx_deferred_source;
				DROP TABLE IF EXISTS laser_bg_tx_allowed_source;
			`)
		}()

		_, _, err = backgrounds.ActivateAndComplete(
			ctx, job.ID, testBackgroundCompletion(), testRawBackgroundRevision(job),
		)
		if err == nil {
			t.Fatal("延迟约束提交失败必须向调用方返回错误")
		}
		assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusFusing, old.ID, 1, false)
		t.Log("TX_CASE:commit_failure_rollback:PASS")
	})

	t.Run("concurrent terminal is linearizable", func(t *testing.T) {
		const iterations = 24
		resetLaserBackgroundTestTables(t, pool)
		for iteration := 0; iteration < iterations; iteration++ {
			scans, backgrounds, job, old := seedLaserBackgroundTestState(
				t, pool, fmt.Sprintf("race-%02d", iteration), 10+iteration,
			)
			start := make(chan struct{})
			var wg sync.WaitGroup
			var cancelErr, completeErr error
			var activated *LaserBackgroundRevision
			wg.Add(2)
			go func() {
				defer wg.Done()
				<-start
				_, cancelErr = scans.Cancel(context.Background(), job.ID)
			}()
			go func() {
				defer wg.Done()
				<-start
				_, activated, completeErr = backgrounds.ActivateAndComplete(
					context.Background(), job.ID, testBackgroundCompletion(), testRawBackgroundRevision(job),
				)
			}()
			close(start)
			wg.Wait()

			stored, err := scans.FindByID(context.Background(), job.ID)
			if err != nil {
				t.Fatalf("第 %d 轮读取任务失败: %v", iteration, err)
			}
			switch stored.Status {
			case LaserScanStatusCancelled:
				if cancelErr != nil || !errors.Is(completeErr, ErrNotFound) {
					t.Fatalf("第 %d 轮取消终态不线性: cancel=%v complete=%v", iteration, cancelErr, completeErr)
				}
				assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusCancelled, old.ID, 1, false)
			case LaserScanStatusDone:
				if completeErr != nil || !errors.Is(cancelErr, ErrNotFound) || activated == nil {
					t.Fatalf("第 %d 轮完成终态不线性: cancel=%v complete=%v revision=%+v", iteration, cancelErr, completeErr, activated)
				}
				assertLaserBackgroundState(t, pool, job.ID, LaserScanStatusDone, activated.ID, 2, true)
			default:
				t.Fatalf("第 %d 轮出现非法终态 %q", iteration, stored.Status)
			}
		}
		t.Logf("TX_CONCURRENT_ITERATIONS:%d", iterations)
		t.Log("TX_CASE:concurrent_linearizable:PASS")
	})
}

func openLaserBackgroundTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("GOMOB_TEST_DB_DSN")
	if dsn == "" {
		t.Skip("未设置 GOMOB_TEST_DB_DSN，PostgreSQL 事务测试由 laser_background_transaction harness 执行")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatalf("连接隔离 PostgreSQL 失败: %v", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		t.Fatalf("隔离 PostgreSQL ping 失败: %v", err)
	}
	var databaseName string
	if err := pool.QueryRow(ctx, `SELECT current_database()`).Scan(&databaseName); err != nil {
		pool.Close()
		t.Fatalf("读取 PostgreSQL 测试库名失败: %v", err)
	}
	if err := validateLaserBackgroundTestDatabaseName(databaseName); err != nil {
		pool.Close()
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

func validateLaserBackgroundTestDatabaseName(databaseName string) error {
	if !strings.HasPrefix(databaseName, "gomob_laser_bg_tx_") {
		return fmt.Errorf(
			"拒绝在非隔离数据库 %q 运行激光背景事务测试；必须通过 laser_background_transaction harness 创建 gomob_laser_bg_tx_* 临时库",
			databaseName,
		)
	}
	return nil
}

func TestValidateLaserBackgroundTestDatabaseName(t *testing.T) {
	for _, databaseName := range []string{"gomob", "postgres", "gomob_test"} {
		if err := validateLaserBackgroundTestDatabaseName(databaseName); err == nil {
			t.Fatalf("共享/普通数据库 %q 必须被拒绝", databaseName)
		}
	}
	if err := validateLaserBackgroundTestDatabaseName("gomob_laser_bg_tx_1720956451_1234"); err != nil {
		t.Fatalf("harness 临时库应被允许: %v", err)
	}
}

func resetLaserBackgroundTestTables(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
		DROP TRIGGER IF EXISTS laser_bg_tx_reject_new_revision ON laser_background_revision;
		DROP FUNCTION IF EXISTS laser_bg_tx_reject_new_revision();
		ALTER TABLE laser_background_revision DROP CONSTRAINT IF EXISTS laser_bg_tx_deferred_source;
		DROP TABLE IF EXISTS laser_bg_tx_allowed_source;
		DELETE FROM laser_background_revision
		WHERE unit_a_ip LIKE '10.77.%.101' AND unit_b_ip LIKE '10.77.%.102';
		DELETE FROM laser_scan_jobs
		WHERE session_key LIKE 'laser-bg-tx-%'
		  AND unit_a_ip LIKE '10.77.%.101' AND unit_b_ip LIKE '10.77.%.102';
	`)
	if err != nil {
		t.Fatalf("重置隔离测试表失败: %v", err)
	}
}

func seedLaserBackgroundTestState(
	t *testing.T,
	pool *pgxpool.Pool,
	suffix string,
	stationIndex int,
) (*LaserScanRepo, *LaserBackgroundRevisionRepo, *LaserScanJob, *LaserBackgroundRevision) {
	t.Helper()
	scans := NewLaserScanRepo(pool)
	backgrounds := NewLaserBackgroundRevisionRepo(pool)
	unitA := fmt.Sprintf("10.77.%d.101", stationIndex)
	unitB := fmt.Sprintf("10.77.%d.102", stationIndex)
	legacyKey := "laser-background/" + suffix + "/legacy-fused.pcd"
	old, err := backgrounds.Activate(context.Background(), LaserBackgroundRevision{
		UnitAIP: unitA, UnitBIP: unitB,
		LegacyFusedObjectKey: stringPtr(legacyKey), CoordinateSchema: LaserBackgroundSchemaLegacyFused,
	})
	if err != nil {
		t.Fatalf("写入旧 active 背景失败: %v", err)
	}
	job, err := scans.Create(
		context.Background(), "laser-bg-tx-"+suffix, unitA, unitB, "site", 1, nil, nil,
	)
	if err != nil {
		t.Fatalf("创建背景任务失败: %v", err)
	}
	job, err = scans.MarkFusing(context.Background(), job.ID, 120, 130)
	if err != nil {
		t.Fatalf("背景任务进入 fusing 失败: %v", err)
	}
	return scans, backgrounds, job, old
}

func testRawBackgroundRevision(job *LaserScanJob) LaserBackgroundRevision {
	unitAKey := "laser-background/raw/unit-a.pcd"
	unitBKey := "laser-background/raw/unit-b.pcd"
	siteRevision := "site-revision-sha256"
	regionRevision := "region-revision-sha256"
	checksumA, checksumB := "checksum-a", "checksum-b"
	deviceHashA, deviceHashB := "device-hash-a", "device-hash-b"
	scanHashA, scanHashB := "scan-hash-a", "scan-hash-b"
	return LaserBackgroundRevision{
		UnitAIP: job.UnitAIP, UnitBIP: job.UnitBIP,
		UnitAObjectKey: &unitAKey, UnitBObjectKey: &unitBKey, SourceScanID: &job.ID,
		SiteRevision: &siteRevision, RegionRevision: &regionRevision, UnitAPoints: 120, UnitBPoints: 130,
		UnitAChecksum: &checksumA, UnitBChecksum: &checksumB,
		UnitAIdentity:         json.RawMessage(fmt.Sprintf(`{"ip":%q}`, job.UnitAIP)),
		UnitBIdentity:         json.RawMessage(fmt.Sprintf(`{"ip":%q}`, job.UnitBIP)),
		UnitADeviceConfigHash: &deviceHashA, UnitBDeviceConfigHash: &deviceHashB,
		UnitAScanConfigHash: &scanHashA, UnitBScanConfigHash: &scanHashB,
		CoordinateSchema: LaserBackgroundSchemaRegionCroppedUnitV1,
	}
}

func testBackgroundCompletion() LaserScanCompletion {
	return LaserScanCompletion{
		AlignMethod: "site", PtsA: 120, PtsB: 130, Fused: 250, AfterCrop: 250,
		FusedObjectKey: "laser-scans/background/fused.pcd",
		UnitAObjectKey: "laser-scans/background/unit-a.pcd",
		UnitBObjectKey: "laser-scans/background/unit-b.pcd",
		Stats:          json.RawMessage(`{"measure":{"valid":false},"marker":"preserved"}`),
	}
}

func assertLaserBackgroundState(
	t *testing.T,
	pool *pgxpool.Pool,
	jobID int64,
	wantStatus string,
	wantActiveID int64,
	wantRevisionCount int,
	wantStatsRevision bool,
) {
	t.Helper()
	scans := NewLaserScanRepo(pool)
	backgrounds := NewLaserBackgroundRevisionRepo(pool)
	job, err := scans.FindByID(context.Background(), jobID)
	if err != nil {
		t.Fatalf("读取任务失败: %v", err)
	}
	if job.Status != wantStatus {
		t.Fatalf("任务状态错误: got=%q want=%q", job.Status, wantStatus)
	}
	active, err := backgrounds.GetActive(context.Background(), job.UnitAIP, job.UnitBIP)
	if err != nil {
		t.Fatalf("读取 active 背景失败: %v", err)
	}
	if active.ID != wantActiveID {
		t.Fatalf("active 背景错误: got=%d want=%d", active.ID, wantActiveID)
	}
	var count int
	if err := pool.QueryRow(
		context.Background(),
		`SELECT count(*) FROM laser_background_revision WHERE unit_a_ip=$1 AND unit_b_ip=$2`,
		job.UnitAIP,
		job.UnitBIP,
	).Scan(&count); err != nil {
		t.Fatalf("统计背景 revision 失败: %v", err)
	}
	if count != wantRevisionCount {
		t.Fatalf("背景 revision 数错误: got=%d want=%d", count, wantRevisionCount)
	}
	var stats map[string]any
	if err := json.Unmarshal(job.Stats, &stats); err != nil {
		t.Fatalf("任务 stats 非法: %v", err)
	}
	value, hasRevision := stats["background_revision_id"]
	if wantStatsRevision {
		if !hasRevision || value != float64(active.ID) || stats["marker"] != "preserved" {
			t.Fatalf("任务 stats 与 active revision 不一致: stats=%+v active=%d", stats, active.ID)
		}
	} else if hasRevision {
		t.Fatalf("回滚任务不得写入 background_revision_id: %+v", stats)
	}
}

func stringPtr(value string) *string { return &value }
