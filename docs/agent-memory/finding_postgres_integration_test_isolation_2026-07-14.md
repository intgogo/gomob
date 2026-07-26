# PostgreSQL 集成测试必须与共享开发库硬隔离

## Why

`laser_background_revision_postgres_test.go` 曾用 `TRUNCATE ... CASCADE` 重置事务测试表；当
`GOMOB_TEST_DB_DSN` 被误指向共享 `gomob` 库时，真实扫描任务以及引用它们的 site、region、background
配置被整库级联清空，App 随即报“当前工位尚未保存外参”。只靠调用者记得传测试库不够，测试本身必须拒绝危险目标。

## How to apply

- 会改表数据的 PostgreSQL 测试只允许连接 harness 创建的唯一临时库，并校验 `current_database()` 命名硬门。
- 清理只按测试专属 session/IP 删除自身行，禁止在共享 schema 上使用 `TRUNCATE ... CASCADE`。
- 回归测试必须放入无关任务和外参哨兵，证明清理后仍存在；harness 分析器把该证据列为必过项。
- 现场数据恢复前先把当前库备份到 `.dev/db-backups/`，再从可信 dump、对象存储和可校验报告恢复。
