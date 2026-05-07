# TODO

> 单一真理源。**不另起 `docs/plans/`**。完成后从此处删除，记录留 git log。
>
> 写作纪律：`docs/agent-memory/feedback_plan_writing_quality.md`（无占位符 / 任务按 harness 可验收单元切）。

## M0 — 工程基线（已完成 2026-05-05）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| ✅ M0.1 | Android SDK / NDK / build-tools 安装 (`/opt/android-sdk`) | 已装；`./dev.sh doctor` 通过 | `scripts/ensure-android-sdk.sh` |
| ✅ M0.2 | 多模块 Gradle 骨架 + convention 插件 | app + 7 core + 6 feature + native + designsystem 全联编 | `docs/architecture/02-app-architecture.md` |
| ✅ M0.3 | gradle wrapper + `./dev.sh doctor` 通过 | Gradle 8.10.2 + JDK 17 + AGP/Kotlin 跑通 | `dev.sh` |
| ✅ M0.4 | git 初始化 + 首次 `./gradlew help` 同步通过 | repo 初始化；多次 commit；远端 origin/master 落定 | — |
| ✅ M0.5 | 真机验证：`./dev.sh install && ./dev.sh shot home` 跑通 | gomob_test AVD（GUI 模式默认）+ adb install + 5 tab 截图全收齐于 `.dev/screenshots/redesign-7/`（01-login → 07-history） | `CLAUDE.md` UI 验证规范 |
| ✅ M0.6 | 端侧 mob3d 全屏 jsx 重做（OLED dark + 7 commit） | 01 login / 02 home AI / 03 message / 04 scan3d / 05 collab / 06 profile + 设置抽屉 + 07 history 日历 端到端通过 | 7 commit `9a8d745..8a37818` |

## M1 — 深度相机接入

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| ✅ M1.1 | Berxel Android SDK jar/.so 投放 + 反编译验证 SDK 内部行为 + USB OTG 权限 + 热插拔 + jar PendingIntent flag 二进制补丁 | jar+多 ABI .so 已在 third_party/berxel-android/；BerxelService.kt 自枚举 + attachAuthorizedDevice + 主动 requestPermission 兜底；`patches/berxel-android/{BerxelJarPatch.java,patch.sh}` 用 ASM 把 SDK requestDevicePermission 的 PendingIntent flag 0 改成 IMMUTABLE\|UPDATE_CURRENT；2026-05-07 真机 LOG-AN10 Android 15 走通 Color+Depth 640×400@30 MIX streaming 29 fps | `docs/architecture/01-depth-camera-integration.md` + memory `finding_berxel_sdk_internals_2026-05-07.md` |
| M1.2 | CameraX 主摄像头 RGB 流采集 + 内参读取（LENS_INTRINSIC_CALIBRATION）+ 帧时间戳归一化到 nanoTime | ☐ | `docs/architecture/01-depth-camera-integration.md` §M2 |
| M1.3 | Berxel + CameraX 双流时间戳同步 harness（参数：SYNC_THRESHOLD_NS 默认 5ms） | ☐ | `tests/harness/rgbd_sync/` |
| M1.4 | 单帧点云可视化（Filament） — 端到端最小闭环 | ☐ | `docs/architecture/04-reconstruction-pipeline.md` |

## M2 — 双摄外参标定（未启动）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M2.1 | 标定板设计（棋盘 / Charuco）+ 流程文档 | ☐ | `docs/architecture/05-calibration-pipeline.md` |
| M2.2 | feature:calibration UI 向导 + 数据采集 + 求解 | ☐ | 同上 |
| M2.3 | 标定结果落库 + 跨会话复用 | ☐ | `core:database` |

## M3 — 实时融合 + 重建（未启动）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M3.1 | 主从外参投影 colorize 单元测试 + harness | ☐ | `tests/harness/fusion_quality/` |
| M3.2 | TSDF voxel grid + Marching Cubes 出 mesh | ☐ | `docs/architecture/04-reconstruction-pipeline.md` |
| M3.3 | 纹理烘焙 + glTF 导出 | ☐ | 同上 |
| M3.4 | feature:gallery Filament 渲染回看 | ☐ | — |

## M4 — 工程治理 / Harness（贯穿）

| ID | 项 | 状态 |
|----|----|------|
| M4.1 | rgbd_sync harness（采样 + analyze.py） | ☐ |
| M4.2 | fusion_quality harness | ☐ |
| M4.3 | recon_quality harness | ☐ |
| M4.4 | `docs/architecture/registry/` 机器可校验真理源（modules / dependencies） | ☐ |

---

# 服务端主线（M-S，与端侧 M0–M4 并行推进）

> 目录在仓内 `server/`；契约见 `docs/architecture/server/`。
> **每条任务以"可独立 curl / harness / 单元测试验收"为切分边界**，不按时间切。

## M-S0 — 服务端基线（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S0.1 | `server/` 仓内骨架 + go.mod (`io.gomob/server`) + Makefile（15 个服务二进制） | `make build` 全部 15 个二进制产出（gateway/api/auth/asset/signaling/worker/device/modelregistry/admin/catalog/vinref/shaperef/cvengine/llmgateway/devserver） | `docs/architecture/server/01-go-project-layout.md` |
| ✅ M-S0.2 | `./dev.sh server doctor` — 校验 Go 1.23+ / podman / protoc / git；自动侦测 4 个 dev 容器是否就位 | 缺任一项明确报错并给安装提示；`server/scripts/server-doctor.sh` | 同上 §开发主入口 |
| ✅ M-S0.3 | 容器栈（dev + prod 统一 podman）：gomob-pg/redis/nats/minio 四个 named-volume 持久容器，`./dev.sh server up/down/ps/logs` 一键管 | 容器持续 running ≥ 44h 稳定；OCI 镜像可下发任意容器引擎 | 同上 |
| ✅ M-S0.4 | `migrations/0001_init.up.sql` — 11 张基线表 | 已存在（stations/users/vehicles/inspections/inspection_assets/reviews/conversations/conversation_members/messages/call_logs/audit_log），各 M-S 阶段在自己的 0002+ 增量加表 | `docs/architecture/server/00-server-overview.md` §3 |
| ✅ M-S0.5 | `pkg/{logger,token,httpx,repo,audit,rbac,metric,trace}` 公共包骨架 + 单测 | `go test ./pkg/...` 全绿（audit/metric/rbac/trace 含完整单测） | 同 §F2/§B4 |
| ✅ M-S0.6 | `scripts/{server-doctor.sh, migrate.sh, proto-gen.sh}` | 缺 migrate / protoc 时给出具体安装命令 | 同 §01-go-project-layout |

## M-S1 — gateway + auth（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S1.1 | `cmd/auth` 注册接口（写 `users` `status=pending`；dev 模式自动激活） | curl POST /v1/auth/register → code=0 user_id 返回；DEV 模式 status=active；`internal/auth/handler.go` Register | `02-api-contract.md` §3.1 |
| ✅ M-S1.2 | `cmd/auth` 登录 + token 颁发（access 2h / refresh 7d） | active 用户拿到双 token；refresh 接口拿新 access；harness S2/S5 通过 | 同 §3.2/§3.3 |
| ✅ M-S1.3 | `cmd/auth` 改密 + GET /v1/me | 旧密码错返 40101；旧密码对成功；旧密码不能再登录；新密码登录返新 token；harness S6a-d 通过 | 同 §3.4/§3.5 |
| ✅ M-S1.4 | `cmd/gateway` 反代 + JWT 校验 + 注入 `X-Gomob-User-Id` / `X-Gomob-Roles` + Redis 限流 | gateway:8808 反代到 auth:8082；公开路径白名单（register/login/refresh）；token 失效返 40102；超 limit 返 429；下游 fallback Bearer | `00-server-overview.md` §8 / §6.app |
| 🟡 M-S1.5 | RBAC：M-S0.5 已交付 `pkg/rbac.Baseline()`（4 角色策略 + 单测） | `pkg/rbac` 单测覆盖 inspector/supervisor/reviewer/admin；**casbin 接入留 M-S2** 与具体业务接口一起 | 同 §5 |
| ✅ M-S1.6 | `tests/harness/auth_flow/` 全链路自验 | `./dev.sh harness auth_flow` → 10/10 场景通过；S1-S6d 业务正确性 + S7 限流触发；analyze.py 输出"正常"判定 | `CLAUDE.md` 自分析规范 |

## M-S2 — api + asset 业务主域（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S2.1 | `cmd/api` + `internal/api` 查验 CRUD（列表/详情/创建/状态机/关闭/写预审/提交复核） | 状态机 5 状态 PG CAS（`UPDATE ... WHERE status=ANY($from)`），非法跳转返 40401；inspections_test.go 覆盖 11 个状态对组合 | `02-api-contract.md` §4 / §9.2 |
| ✅ M-S2.2 | `cmd/api` 抽查复核：bucket 列表（pending/done/expired）+ 详情 + decide CAS | reviewer/inspector 角色区分（rbac.Baseline）；重复 decide 返 40401；audit 入表 | 同 §6 / §9.3 |
| ✅ M-S2.3 | `cmd/asset` 分片上传：init / part / complete / abort，复用 MinIO multipart + Redis 存 part etag | 24 MB 文件 8 MB×3 片端到端通过；CAS sha256 + size 双校验；S3 part 最小 5 MB 约束已对齐（默认 8 MB） | 同 §5 |
| ✅ M-S2.4 | `cmd/asset` 签名 URL 下载：`PresignedGetObject` 5 分钟 TTL | URL `expires_in=300`；下载文件 sha256 与原文件全匹配；权限：自己的 OR supervisor/reviewer/admin | `00-server-overview.md` §11 |
| ✅ M-S2.5 | `audit_log` 接入：api 全部写路径 + asset.upload_complete | `pkg/audit.PG` 写 users/action/target/before/after JSON；harness 验证 7 条事件 | 同 §11 |
| ✅ M-S2.6 | `tests/harness/inspection_lifecycle/` — 23 场景全链路自验 | `./dev.sh harness inspection_lifecycle` → 23/23 通过；含 sha256 下载校验 + 状态机一致性 + audit 数量 | `CLAUDE.md` 自分析规范 |

## M-S3 — device（相机绑定 + 标定云同步）（已完成 2026-05-05 schema/admin/读路径；App 端接入留 M3 重建管线落地后）

> 设计第一性：serial 全系统 active 唯一（partial unique index uq_devices_serial_active），
> 老主人 retire 后允许转手；标定参数 version 自 devices.calibration_seq 在事务内 FOR UPDATE +1 单调；
> 同 sha256 重传幂等不 bump（端侧网络抖动重试不浪费版本号）。
> App 比对版本：先 GET /v1/devices/{id}/calibrations/latest 返 (version, sha256)，
> 端侧本地 sha256 一致 → 跳；不一致 → 拉完整 params。

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S3.1 | migration 0009：`devices` + `device_calibrations` schema（partial unique active serial / 状态机 active→retired / calibration_seq FOR UPDATE 单调） | up/down 可逆；migration 直应用 PG 通过 | `migrations/0009_devices.{up,down}.sql` |
| ✅ M-S3.2 | `pkg/repo/device.go`：DeviceRepo（Bind 幂等 + 跨用户冲突 / FindByID / ListByUser / Patch / TouchLastSeen / Retire）+ DeviceCalibrationRepo（Insert 事务内 FOR UPDATE 取 seq+1 / 同 sha256 幂等不 bump / FindLatest / FindByVersion / ListByDevice） | harness S3-S5 / S11-S14 / S18-S21 全过 | `pkg/repo/device.go` |
| ✅ M-S3.3 | `cmd/device` HTTP :18086 + `internal/device/handler.go`：App 写/读路径（bind / list / get / patch / touch / retire / upload_calibration / list+latest+by_version）+ admin（AdminList / AdminGet / AdminListCalibrations） | harness S6-S10 / S15-S17 / S22-S23 通过 | `cmd/device/main.go` / `internal/device/handler.go` |
| ✅ M-S3.4 | gateway `/v1/devices` 已配（既有 route.go）；admin BFF 加 `/admin/v1/devices/*` 反代到 device | harness S1（401 via gateway） / S24（gateway 反代 list） / S22（admin 反代）通过 | `internal/admin/handler.go` `mountProxy` |
| ✅ M-S3.5 | `tests/harness/device_sync/` — 25 场景 39 断言全链路 | `./dev.sh harness device_sync` → **39/39 通过**；含幂等/转手/版本 bump/跨用户隔离/audit≥4/gateway/admin BFF | `tests/harness/device_sync/{run.sh,analyze.py}` |
|     | App 端 `core:data` 接入（扫描启动前比对版本 + Room 离线退化） | 留 M3 重建管线落地后；当前服务端契约已就绪 | `docs/architecture/05-calibration-pipeline.md` |

## M-S4 — signaling（消息 + WebRTC 信令）（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S4.0 | migration 0006：`conversations.next_seq` + `conversations.p2p_key` partial unique + `pending_calls` 表 + 索引 | up/down 可逆；schema 落盘 | `migrations/0006_signaling.{up,down}.sql` |
| ✅ M-S4.1 | `cmd/signaling` wss 双向通道（gorilla/websocket）+ 心跳（25s ping / 60s pong）+ Hub 多端登录索引 | harness S2/S3 hello 投递 ≤3ms；S15 `/v1/signaling/online` 暴露在线列表 | `internal/signaling/{hub,conn,handler}.go` |
| ✅ M-S4.2 | 单聊消息：发送 → 落 `messages` → server_seq 严格单调（`UPDATE conversations SET next_seq=next_seq+1` 行锁分配）+ p2p 自动建会话 | harness S4 单条 seq=1；S5 顺序 50 条 [2..51]；S6 并发 100 条 [52..151] 无重无空；S7 fetch since=0 拿全部 152 条升序 | `pkg/repo/messages.go` / `internal/signaling/router.go` handleMsgSend |
| ✅ M-S4.3 | WebRTC 信令：call.invite/answer/ice/bye 透传到对端所有在线连接 | harness S11 answer / S12 ICE 双向 / S13 bye 全 ≤4ms 转发 | `internal/signaling/router.go` handleCall* |
| ✅ M-S4.4 | 离线兜底：被叫不在线时 invite → `pending_calls`（默认 60s TTL）；callee 上线 → `DeliverPending` 一次性补发 + MarkDelivered；后台 `SweepLoop` 把过期标 expired | harness S9 invite_ack online=false；S10 重连 5s 内收 pending=true；S14 TTL=3s sweep=1s 过期后不再投递 | `pkg/repo/calls.go` / `internal/signaling/router.go` |
| ✅ M-S4.5 | `tests/harness/ws_message_order/` — 16 场景全链路自验（注册/登录/双 ws/单条/顺序/并发/fetch/错误帧/离线/重连/answer/ice/bye/TTL/online） | `./dev.sh harness ws_message_order` → **16/16 通过**；burst=100 conc=5 单调严格无重复 | `tests/harness/ws_message_order/{run.sh,analyze.py}` + `cmd/wsharness/main.go` |
|     | gateway 端：`/v1/ws` 路由声明 Public（浏览器/RN 不能设 Authorization；signaling 自校 `?token=` query）；`httputil.NewSingleHostReverseProxy` 自动透传 ws upgrade | `internal/gateway/route.go` 已加 `Public: true` | 同 |

## M-S5 — model-registry（已完成 2026-05-04） + worker（待 cv-engine）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S5.1 | `cmd/modelregistry` 元数据 CRUD + 状态机（draft→canary→active→archived）；同 name 至多 1 active + 1 canary（PG 部分唯一索引） | (name, version) 重复返 40201；非法跳转返 40401；harness 18 场景通过 | `00-server-overview.md` §6.y |
| 🟡 M-S5.2 | sha256 一致性：metadata 字段已存；二进制实际校验由 worker / cv-engine 加载时做 | 当前仅 schema 就绪；M-S10 cv-engine 实施时验证 | 同 §6.y |
| ✅ M-S5.3 | **worker 异步预审任务**：NATS 订阅 `inspection.scan_completed` → MinIO 直拉字符 alpha 字节 → 调 cv-engine `vin_character_compare_with_ref` 厂家库比对 → 17 字符聚合算 verdict（pass≥0.85 / warning≥0.60 / fail）→ `inspections.preliminary_*` 写库 + 状态机 `scanning→preliminary` + publish `inspection.preliminary_done` + audit | harness `worker_preliminary` 9/9 端到端：vinref 录 A → 17 字符 scan_completed → 1s 内 worker 完成 → DB verdict=pass status=preliminary reasons="avg_similarity=1.000 scored=17/17" + audit_log 1 条 | `internal/worker/handler.go` / `cmd/worker/main.go` / `tests/harness/worker_preliminary/` |
| ✅ M-S5.4 | 灰度策略：`model_routes` 表 + `/admin/v1/models/{name}/route` PUT；resolve 算法：白名单 > 比例（FNV hash 确定性 + 50% 分布偏差 ≤ 2.5%） | harness S7 200 user 测试 canary=100/199；S8 user42 命中白名单 → reason="user_whitelist"；S9 同 user 多次 resolve 一致 | 同 §6.y |
| ✅ M-S5.5 | NATS 广播：状态变更（promote/activate/archive）发 `model.version.activated`，worker / cv-engine 据此热更 | harness S12 NATS 订阅器收 3 事件；payload 含 name/version/status/asset_uri/sha256/ts | 同 §6.y / `server-dependencies.yaml` |
| ✅ M-S5.6 | `tests/harness/model_canary_switch/` 全链路 | `./dev.sh harness model_canary_switch` → **18/18 通过** | `CLAUDE.md` 自分析规范 |

## M-S6 — admin BFF（管理面）（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S6.1 | `/admin/v1/users/{id}/{approve,reject,disable}` + 列表 + RBAC | mustAdmin middleware（缺 header 40102 / 非 admin 40103）；状态机 pending→active/disabled；harness S1-S10 通过 | `00-server-overview.md` §7.x |
| ✅ M-S6.2 | 用户管理：`PATCH /admin/v1/users/{id}` 改 role / station_id | 4 角色合法性校验（hacker→10002）；station_id=-1 显式置 NULL | 同 §7.x |
| ✅ M-S6.3 | 模型版本切换：反代 `/admin/v1/models/*` 到 model-registry（含子路径 /{id}/activate） | mountProxy 同时注册末尾斜杠 + 无尾斜杠（避免 ServeMux 默认 301）；harness S12 通过 | 同 §7.x / `01-go-project-layout.md` |
| ✅ M-S6.4/5 | 反代 `/admin/v1/catalog/*` + `/admin/v1/llm/*` 到对应服务 | harness S11 / S13 通过；admin 单一入口聚合三套 admin API | 同 §7.x / §6.z / §6.v |
| ✅ M-S6.6 | `GET /admin/v1/audit` 聚合查询 + 多维度过滤（user_id / action 精确 / action ILIKE 含 % / from / to / target / cursor 分页） | harness S14-S18 通过 | 同 §11 |
|     | M-S6.7 mTLS 内网部署 + 管理网段隔离 | 由部署层（haproxy / istio sidecar）做；本服务做应用层 RBAC | 同 `01-go-project-layout.md` §端口 |
| ✅ M-S6.harness | `tests/harness/admin_lifecycle/` 全链路 | `./dev.sh harness admin_lifecycle` → **25/25 通过**；含 8 反代场景 + 5 audit 维度 | `CLAUDE.md` 自分析规范 |

## M-S7 — vehicle-catalog（车型档案库）（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S7.1 | migration 0003 vehicle_models（含 (make, series, year) 三元组唯一约束 + status 索引 + updated_at 触发器） | up/down 可逆；重复三元组返 ErrConflict | `00-server-overview.md` §6.z |
| ✅ M-S7.2 | `/admin/v1/catalog/vehicles*` admin 写路径：create / patch / publish / archive；状态机 draft→published→archived | mustAdmin RBAC；patch 在 published 上返 40401；harness S7-S13 通过 | 同 §6.z / `02-api-contract.md` §13 |
| ✅ M-S7.3 | catalog 内部 HTTP（:18059）+ api BFF 透传 `/v1/catalog/vehicles*` 反代（§6.app 决策） | gateway → api BFF → catalog 完整链路通过；`httputil.NewSingleHostReverseProxy` | 同 §6.app |
| ✅ M-S7.4 | Redis LRU 缓存按 vehicle_model_id（默认 10m TTL）+ patch/transition 时失效（DEL 单 key） | `X-Gomob-Cache: hit/miss` header 验证；harness S5/S6 通过 | 同 §6.z |
| ✅ M-S7.5 | `tests/harness/catalog_lifecycle/` 24 场景全链路 | `./dev.sh harness catalog_lifecycle` → **24/24 通过**；含状态机 + 缓存 hit/miss + 三元组冲突 + audit≥8 | `CLAUDE.md` 自分析规范 |
|     | NATS 广播失效（多节点）+ keyword GIN（pg_trgm）扩展 | M-S6 admin 完整接入或多节点部署时再补 | — |

## M-S8 — vin-ref（车驾号字形参考库）（已完成 2026-05-04 schema/admin/读路径；与 cv-engine 对接留 M-S10）

> 设计第一性：拒绝 stub，schema 字段对齐 gosmart `apps/api/ivv/item.go` `VinMore`（character / arr_mode /
> font_id / font_family_id / alpha_image_data / origin_image_data），并按"车型 × 批次 × 字符"三层索引升级到字符级 lookup，
> 让 M-S10 cv-engine 把 `doCompareVin` 改成"拉对照样本与本次扫描字符比对"成为单点改动。

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S8.1 | migration 0007：`vin_glyph_batches`（按 vehicle_model 分批 + 状态机 draft→published→archived，partial unique 同车型至多 1 published）+ `vin_glyph_samples`（CHECK character ∈ VIN 33 字符；arr_mode 0-3；qc_score 0-1；触发器自动维护 sample_count） | up/down 可逆；migration 直应用 PG 通过 | `migrations/0007_vin_ref.{up,down}.sql` |
| ✅ M-S8.2 | `pkg/repo/vinref.go`：VinGlyphBatch（Create / FindByID / FindActive / List / Patch / Publish / Archive / DeleteDraft）+ VinGlyphSample（Insert / Delete / ListByBatch / CountByCharacter）；Publish 事务里 `FOR UPDATE` + 旧 active archive；非 draft 写样本返 ErrStateConflict | harness S5 重名 40201 / S10 publish 自动 sample_count=11 status=published / S11 published 写样本 40401 / S15 invalid character 10002 / S16 publish batch2 → batch1 auto archived 全通 | `pkg/repo/vinref.go` |
| ✅ M-S8.3 | `cmd/vinref` HTTP :18058 + `internal/vinref/handler.go`：admin 写路径 + App / cv-engine 读路径（`/v1/catalog/vehicles/{vmid}/vin-refs/active`+`/active/samples` 查 active 批次按字符过滤） | harness S13 active counts_by_char A=2 Z=3 / S14 character=A 拿到 ≥2 条 / S20 App 走 gateway → api BFF → vinref 拿到样本 | `cmd/vinref/main.go` / `internal/vinref/handler.go` |
| ✅ M-S8.4 | admin BFF：`/admin/v1/catalog/vehicles/{vmid}/vin-refs/...` subtree 反代到 vinref（路径比 catalog 更具体，Go 1.22 ServeMux 优先匹配最具体）；api BFF：同前缀的 App 路径反代 vinref，其它 `/v1/catalog/*` 走 catalog | `MountCatalogBFF(catalog, vinref)` 双反代 | `internal/admin/handler.go:80` / `internal/api/catalog_bff.go` |
| ✅ M-S8.5 | `tests/harness/vinref_lifecycle/` — 27 场景全链路自验（鉴权 / 三元组冲突 / 状态机 5 跳变 / 样本 CRUD / 字符校验 / 自动 archive / App 读路径 / audit≥8 vinref.* 事件） | `./dev.sh harness vinref_lifecycle` → **27/27 通过** | `tests/harness/vinref_lifecycle/{run.sh,analyze.py}` |
|     | M-S10 cv-engine 对接：`doCompareVin` 改成按 (vehicle_model_id, character) gRPC 拉 active 批次的对照样本与本次扫描字符比对 | 留 M-S10 cv-engine 迁移时实施 | `docs/architecture/server/03-cvengine-migration.md` |
| ✅ M-S8.x quality | **`tests/harness/vinref_compare_quality/`** — 5 字符厂家库（A/B/C/0/8）+ scan 自比 + 平移 + 不存在字符的端到端精度基线；调 `/cv/ocr/v1/vin_character_compare_with_ref` 比对 best.character 命中 + similarity 区间，`expected.json` 基线偏离即 FAIL；改算法跑此 harness 看精度抖动 | 9/9 通过：5 个字符自比 sim=1.000；A_shift→A sim=0.295（区间 0.25-0.40）；'Z' 字符未注册→40701；改 promote 把 baseline_observed.json 回填 expected.json | `tests/harness/vinref_compare_quality/{run.sh,analyze.py,expected.json}` |

## M-S9 — shape-ref（车型 3D 外廓参考库）（已完成 2026-05-04 schema/admin/读路径；端侧 Filament 对照留 M3 重建管线）

> 设计第一性：shape-ref 不复用 vin-ref 的 batch×sample 双层结构 — 因为 mesh 是单文件资产，没有"按字符聚合"的需求；
> 厂家送达的就是一套完整 mesh，所以一条 vehicle_shapes 记录 = 一个完整版本，状态机 draft→published→archived 直接挂在记录上。
> mesh 本体不入 PG（GB 级），只存 object_key + sha256 + size + format + 几何元数据；字节流走 asset MinIO，5 分钟签名 URL，
> 客户端可 HTTP Range 续传。

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S9.1 | migration 0008：`vehicle_shapes`（mesh_object_key/sha256/size_bytes/format + triangle_count + point_count + 3D bbox 6 字段 + coverage/qc_score 0-1 CHECK + source/format CHECK；状态机 + partial unique 同 vehicle_model 至多 1 published） | up/down 可逆；migration 直应用通过 | `migrations/0008_vehicle_shapes.{up,down}.sql` |
| ✅ M-S9.2 | `pkg/repo/shape.go`：Create / FindByID / FindActive / List / Patch / Publish（事务内旧 active 自动 archive，FOR UPDATE 行锁）/ Archive / DeleteDraft；非 draft 改 / 写返 ErrStateConflict；CHECK 命中返 ErrFieldRange | harness S7 重名 40201 / S8 invalid format "fbx" 10002 / S11 published 改 40401 / S15 publish v2 → v1 自动 archived 全通 | `pkg/repo/shape.go` |
| ✅ M-S9.3 | `cmd/shaperef` HTTP :18056 + `internal/shaperef/handler.go`；MinIO client 自带（与 asset 共用 bucket），App 读路径附带 5 分钟签名 mesh URL + expire_at；admin 写路径全套 CRUD + 状态机 | harness S12 active 含 url_len=330 + expire RFC3339 / S13 download URL + sha256 完全匹配 / S19 App 走 gateway → api BFF → shape-ref 拿到 URL | `cmd/shaperef/main.go` / `internal/shaperef/handler.go` |
| ✅ M-S9.4 | admin BFF：`/admin/v1/catalog/vehicles/{vmid}/shapes/...` subtree 反代到 shape-ref；api BFF：单数 `/shape{,/url}` + 复数 `/shapes/...` 反代 shape-ref，其它 `/v1/catalog/*` 走 catalog | `MountCatalogBFF(catalog, vinref, shaperef)` 三反代并存 | `internal/admin/handler.go:88-104` / `internal/api/catalog_bff.go` |
| ✅ M-S9.5 | `tests/harness/shaperef_lifecycle/` — 25 场景端到端（asset 分片上传 1MB → MinIO → shape-ref 注册 → publish → App 拿签名 URL → curl 下载验 sha256 + size 完全一致 / 多版本 publish 自动 archive 老版本 / audit≥5 shaperef.* 事件） | `./dev.sh harness shaperef_lifecycle` → **25/25 通过** | `tests/harness/shaperef_lifecycle/{run.sh,analyze.py}` |
|     | App 端 `feature:gallery` 双窗口渲染（本次扫描 vs 标准外廓） | 留 M3 重建管线落地后；当前服务端契约已就绪 | `docs/architecture/06-product-features.md` §3.4 |
| 🟢 cv-engine 接入 | **元数据级**已完成（POST /cv/v1/shape_compare，详见 M-S9.x cv-engine 段）：tri/point/bbox IoU/coverage/qc score 综合分 + 三态 verdict + reasons；几何级（chamfer / Hausdorff / 真 mesh 解析）留后续叠加，端点契约稳定 | 详见 M-S9.x cv-engine 段；harness `cv_shape_compare` 19/19 | 同 §6.w |

## M-S10 — cv-engine（CV 算法引擎，从 gosmart 迁移）（Phase 1 地基已完成 2026-05-04；Phase 2 业务端点待续）

> **实施细化见 `docs/architecture/server/03-cvengine-migration.md`**（含闭包审计 / cgo 阻塞点 / 风险登记）。
> M-S10 拆三阶段，第一性原则下分形展开 — 不是 v1 stub，是真实 cgo 集成只是 endpoint 数量先做最少：
>
> - **Phase 1**（M-S10.1a-d + M-S10.2 minimal）：✅ 地基 — cgo 链通 + 一个真实 OpenCV 调用端点
> - **Phase 2**（M-S10.3-7）：迁 ivv 业务端点 + JWT/HMAC + vin-ref 对接 + Dockerfile
> - **Phase 3**（M-S10.8）：harness 基线对比 gosmart 时代 precision/recall

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S10.1a | 拷 `gosmart/engine/ccv/{include,Makefile,Makefile.inc,libccv.so,libengine_crypt.so}` 到 `server/internal/cvengine/ccv/`（**跳过 src/473M / .a/45M / atlas 异构卡变体**，仓内仅 ~10MB；运行期 .so 走 /usr/local/lib + Dockerfile COPY） | `ls -la server/internal/cvengine/ccv/` 头文件 + .so 完整 | `03-cvengine-migration.md` §6 |
| 🟢 M-S10.1b | spike 已通过（2026-05-04）；M-S10.1 实施 `engine/gocv/` 拷贝到 `server/internal/cvengine/gocv/`，cgo 头文件路径 `-I../ccv/include` 不变 | `go build ./internal/cvengine/gocv` 通过 | spike 报告：[.dev/spike-cgo/REPORT.md](../.dev/spike-cgo/REPORT.md) |
| ✅ M-S10.1c | 全局重写 `gosmart/engine/gocv` → `io.gomob/server/internal/cvengine/gocv` + `gosmart/util/*` → `io.gomob/server/internal/cvengine/util/*`；删 `_test.go`（依赖 testdata 未迁）；删 `engine/image/` fork（无人引用） | `grep -rn '"gosmart/' server/internal/cvengine` 空 | 同上 |
| ✅ M-S10.1d | 拷 `util/{conv,crypt,datetime,fs,json,levenshtein,pack,number}` + 顶层 `util.go` 等；删 `util/{license,logs,security,sql,str,check}`（gomob 已替代或未用）；`util/conv/obj.go` 的 `echo-go util.NewError` 替成 `errors.New` | `go build ./...` 全绿 | 同上 §3 |
| ✅ M-S10.2 minimal | `cmd/cvengine/main.go` + `internal/cvengine/handler.go`：4 端点 `/healthz` `/readyz`（强制 cgo 调 `gocv.OpenCVVersion()`）`/cv/v1/version` `/cv/v1/echo_dim`（IMDecode 真解码 PNG/JPEG 返尺寸） | 二进制 `ldd` 真依赖 libccv/libopencv_world/libonnxruntime；POST PNG 返 `cols=48 rows=32 channels=3`（OpenCV 真解码）；坏数据返 10001 | `cmd/cvengine/main.go` / `internal/cvengine/handler.go` |
| ✅ M-S10.smoke | `tests/harness/cv_engine_smoke/` 13 场景：ldd 链路 / 真 cgo / 版本 / PNG 解码 / 坏数据拒绝 | `./dev.sh harness cv_engine_smoke` → **13/13 通过**；`opencv=4.5.5-pre gocv=0.22.0` | `tests/harness/cv_engine_smoke/{run.sh,analyze.py}` |
| ✅ M-S10.2 phase2.0 | **`vin_character_compare` 端点 + JWT 中间件可选**：迁 `judge_vin.go` 的 IoU/Chamfer/AlignByCentroid/CalculateVinFontDifference 到 `internal/cvengine/judge`；写 `internal/cvengine/proc.ProcVinCharacterCompare`；handler 加 `POST /cv/ocr/v1/vin_character_compare`（multipart / form / base64 三种上传都支持）；`GOMOB_CVENGINE_REQUIRE_AUTH` 控制是否强制 X-Gomob-User-Id 头 | harness `cv_vin_compare` 16/16：A 自比 IoU=1.0 / A 平移 IoU=0.659 / A vs O IoU=0.207 / Chamfer 同图=0 不同=5.85；缺 image2 / 非法 method / 坏图 → 10001；REQUIRE_AUTH=true 缺 header → 40102 | `internal/cvengine/{judge,proc,handler}.go` / `tests/harness/cv_vin_compare/` |
| ✅ M-S10 phase2.1 | **ONNX 模型加载基线**：写 `internal/cvengine/core/` Registry — RegisterONNX / Get / List / LoadFromEnv；handler 加 `GET /cv/v1/models`；env `GOMOB_CVENGINE_MODELS="VMET=/path/vmet1.onnx,..."` 启动期解析 | harness `cv_models_smoke` 9/9：106MB vmet1.onnx 真过 gocv.ReadNet 加载 (loaded=true size_bytes=106559949)；不存在路径优雅失败 (loaded=false + error)；OpenCV cgo 仍正常；空集返 items=[] | `internal/cvengine/core/core.go` / `tests/harness/cv_models_smoke/` |
| ✅ M-S10 phase2.2 | **vin-ref 厂家库对接**：vin-ref 给 sample 加 alpha_url 签名 URL（与 shape-ref 同套）；`internal/cvengine/vinrefclient/` HTTP 客户端；handler 加 `POST /cv/ocr/v1/vin_character_compare_with_ref` —— cv-engine 拉 vin-ref active 样本→签名 URL 拉字节→ProcVinCharacterCompare 真比对→返最优匹配 | harness `cv_vinref_compare` 23/23：上传厂家 A 模板→批次 publish→扫描 A 自比 sim=1.0 / 平移=0.65 / O vs A=0.22；threshold 通/不通；vehicle_model 不存在/字符无样本→40701；signed alpha_url 真返 (url_len=333) | `internal/vinref/handler.go` / `internal/cvengine/vinrefclient/client.go` / `tests/harness/cv_vinref_compare/` |
| ✅ M-S10 phase2.3 | **yolo VMASK 实例分割**：core.RegisterMaskONNX 调 gocv.CreateORTMask（onnxruntime 直链 + std/mean 预处理）；handler 加 `POST /cv/ocr/v1/vin_detect_yolo` 返 detections{class,score,rrect,contour}；env `GOMOB_CVENGINE_MODELS="VMASK:mask=path:vin"` 三段语法 | harness `cv_yolo_detect` 11/11：354MB vins0.onnx 真加载 / `kind=mask classes=[vin]` / 合成图返真实 yolo 输出（count=0）/ 错误 tag → 40701 / 缺图 → 10001 | `internal/cvengine/core/core.go` `RegisterMaskONNX`/`RunMask` |
| ✅ M-S10.4 | **model-registry 接管模型加载**：`vinrefclient`+ MinIO 直拉 + SHA256 校验流水线；env `GOMOB_CVENGINE_MODEL_NAMES=VMASK,VMET` 启动期跑流水线；NATS 订阅 `model.version.activated` 热更（旧 Net.Release + 新 Register）| harness `cv_modelregistry_load` 12/12：admin asset 上传 106MB onnx → modelregistry create + activate → cvengine HTTP 拉 → MinIO 下载 → SHA256 OK → 加载成功；NATS 热更：v2 activate → cvengine 收事件自动重载 → cache 含 vmet_v2.onnx + log "热更成功" | `internal/cvengine/{modelregistryclient,loader}/` / `cmd/cvengine/main.go` |
| ✅ M-S10.6 | **Dockerfile.cvengine + cvengine.proto**：multi-stage builder/runtime（OpenCV 4.5.5 + onnxruntime 1.18.1 + libccv 静态打入），HEALTHCHECK + EXPOSE 18810/50062；proto 完整契约（CVEngine service 5 RPC + FontDistMethod enum） | harness `cv_dockerfile_proto` 16/16 静态校验；gRPC server 代码生成留 protoc 装好后跑 `scripts/proto-gen.sh` | `Dockerfile.cvengine` / `proto/cvengine.proto` |
| ✅ M-S10.2a | **完整业务端点 `POST /cv/ocr/v1/vin_pipeline`** —— 整图喂入 → VMASK 检测 → 每检测从 contour FillPoly 抠 alpha PNG → vin-ref 厂家库逐字符 ProcVinCharacterCompare → 聚合 verdict（pass/warning/fail，按 avg+min 双阈值）；自定义 pass_threshold/warn_threshold；按 cx 排序左→右；invalid_class / encode_failed / no_ref / compare_failed 各分支独立计入 reasons | harness `cv_vin_pipeline` 18/18：合成图 0 检测 → verdict=fail + reasons=[no_chars_detected]；image_rows/cols 真实；自定义阈值反映；tag 未注册 → 40701；缺 image / vmid / 非法 method / 负 vmid → 10001；dev 模式无 auth 通过 | `internal/cvengine/handler.go` `VinPipeline` + `contourToAlphaPNG` / `tests/harness/cv_vin_pipeline/` |
| ✅ M-S10.2b | **worker 改用 vin_pipeline 一站式** —— ScanCompletedEvent 加 `full_image_object_key` 字段；非空时走新 `handleViaPipeline` 路径：MinIO 拉整图 → cv-engine /cv/ocr/v1/vin_pipeline 一次到底；旧 characters[] 逐字符路径保留为兼容 ingest 模式（在 handleViaCharacters 中）。延迟从 17×500ms 降到 1×500ms | harness `worker_vin_pipeline` 11/11：上传整张合成 VIN 图 → publish full_image_object_key 模式 → worker 走 pipeline 路径 → 0 检测 → verdict=fail + reasons=[no_chars_detected] + audit mode=pipeline + 状态机 scanning→preliminary | `internal/worker/handler.go` `handleViaPipeline` / `callVinPipeline` / `tests/harness/worker_vin_pipeline/` |
| ✅ M-S10.2c | **HMAC 验签中间件 `pkg/hmacauth/`** —— 三头 (Ts/Nonce/Sig) 防重放：sha256_hex(body) 进签名串；nonce 5min 滑动窗口去重（默认 InMemoryNonceStore）；ts 误差 > 5min 拒；客户端 `NewSigningTransport` 自动签 outbound；cvengine 接入 + worker→cvengine 调用自动签；/healthz /readyz 强制绕过 HMAC（k8s probe）。错误码 40110/40111/40112/40113 | unit test 12/12 通过；harness `cv_hmac_auth` 17/17：required 模式缺签拒 / 错签拒 / 过期拒 / 重放拒 / 正确签放；POST 带 body 签名通过；SigningTransport e2e 通过；/healthz 绕过；lax 模式部分头仍校验；secret 空时完全 noop | `pkg/hmacauth/` / `cmd/cvengine/main.go` / `cmd/worker/main.go` / `tests/harness/cv_hmac_auth/` |
| ✅ M-S9.x cv-engine | **`POST /cv/v1/shape_compare` 端点 + shape-ref 客户端 + shapecmp 算法包** —— shaperefclient 拉 active shape 元数据；shapecmp.Compute 算 tri/point ratio + bbox IoU + coverage/qc diff；shapecmp.Score 加权综合分（bbox_iou 40% + tri 20% + point 20% + coverage 10% + qc 10%）；shapecmp.Verdict 双阈值 + bbox_iou 低位 demote 防伪 pass；missing 字段不强行扣分 | unit test 10/10 通过（IoU3D 几何 / ratioScore / Compute / Verdict 各分支）；harness `cv_shape_compare` 19/19：完美匹配 → pass+score=1+iou=1；偏移 bbox → warning；大幅不匹配 → fail；缺 bbox 仍能算且 reasons 含 bbox_missing；自定义阈值反映；vmid 不存在 → 40701；缺/非法 vmid / bad json → 10001 | `internal/cvengine/{shapecmp,shaperefclient}/` / `internal/cvengine/handler.go` `ShapeCompare` / `tests/harness/cv_shape_compare/` |
| ✅ M-S10.8 | **cv-engine 精度基线 harness** —— `tests/harness/cv_baseline/` 自合成 6 个字符（A/A_shift/B/O/8/0）→ 8 对 IoU/Chamfer 比对 → 与 `expected.json` 基线对比（绝对 tol 或 lt/gt 约束）→ 偏离即 FAIL。基线值由首次实测 promote；改算法 / 换 OpenCV 时跑此 harness 看精度抖动 | harness `cv_baseline` 8/8：A 自比 IoU=1.0 ± 0.001；A 自比 Chamfer=0.0；A vs A_shift IoU=0.291 ± 0.030；A vs O IoU=0.150 / Chamfer=10.47；A vs B IoU < 0.4；8 vs 0 IoU < 0.6 + Chamfer > 0.5 | `tests/harness/cv_baseline/{run.sh,analyze.py,expected.json}` |

## M-S11 — llm-gateway（LLM 大模型网关）（已完成 2026-05-04）

| ID | 项 | 验收标准 | 文档 |
|----|----|---------|------|
| ✅ M-S11.1 | provider-agnostic adapter `Provider interface { Chat / ChatStream }` + DeepSeek（OpenAI 兼容协议）+ Mock provider（无外网时使用） | `Registry.Pick(name)` 选择；harness 用 mock 跑全链路；DeepSeek key 设置时自动启用 | `00-server-overview.md` §6.v |
| ✅ M-S11.2 | API key 由 GOMOB_DEEPSEEK_API_KEY 环境变量托管；空 → 仅启用 mock | 业务服务无 DeepSeek 凭证；harness 默认 mock 不需 key | 同 §6.v |
| ✅ M-S11.3 | `llm_templates` 表（migration 0004）+ admin CRUD + 状态机 draft→active→archived；activate v2 时自动 archive 旧 active；text/template 渲染缺 var 报详细错 | harness S1/S3/S10 通过；S10c 验证 v1 自动 archived | `02-api-contract.md` §15 |
| ✅ M-S11.4 | SSE 流式：event: meta / delta×N / done；`http.Flusher` 即时刷出；client cancel → ctx.Done → provider stream 中止 + 写 status=cancelled audit | harness S5 看到 meta=1 delta=12 done=1；S6 客户端早断后 llm_call_logs 出现 cancelled 记录 | 同 §6.v |
| ✅ M-S11.5 | `llm_call_logs` 表 + 独立 ctx 写入（绕开 client cancel） | harness S12 验证 ≥3 条记录；含 ok / cancelled 两类 status | 同 §11 |
| ✅ M-S11.6 | **LLM 配额** —— `internal/llmgateway/quota.go` Redis INCR 计数器：按 user 日预算 + 按 template 日预算（UTC 日切，48h key TTL）；INCR 后 > budget 自减回滚 + 返 ErrQuotaExceeded → handler 转 40602；redis 不可达自动降级"放行 + 日志告警"（不阻塞业务） | harness `llm_quota` 16/16：user_budget=3 → 第 4 次 40602；user A 超额不影响 B；tpl_budget=2 跨用户共享；budget=0 不限；bad redis addr → 健康启动 + 调用放行 | `internal/llmgateway/quota.go` / `cmd/llmgateway/main.go` / `tests/harness/llm_quota/` |
| ✅ M-S11.7 | **多 provider fallback** —— `internal/llmgateway/fallback.go` `FallbackProvider`：Chat 顺序尝试链上 provider 直到一个成功；ChatStream 在首 chunk 之前 fail → 切下一个，已吐 chunk 后 fail 不切（避风格突变）；ctx 取消立即返不重试。env `GOMOB_LLM_FALLBACK_CHAIN="deepseek,mock"` 配置链；repo 取消"PreferredProvider 默认 deepseek"行为，让空模板 provider 自动享用 fallback | unit test 12/12 通过；harness `llm_fallback` 13/13：deepseek 不可达时自动 fallback 到 mock，content 完整；流式 SSE 也 fallback；显式 provider=mock 走单 mock；显式 provider=deepseek 失败不 fallback；无 chain 配置时 deepseek-only 失败返 502 | `internal/llmgateway/fallback.go` / `pkg/repo/llm.go` / `tests/harness/llm_fallback/` |
| ✅ M-S11.8 | `tests/harness/llm_streaming/` — 流式 / 取消 / 状态机 / 审计 全链路 | `./dev.sh harness llm_streaming` → **18/18 通过** | `CLAUDE.md` 自分析规范 |
