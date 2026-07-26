# 基建 · 服务端 · harness 体系 · 实时协作 — 历史上下文

> 最后更新: 2026-07-26 | 截至 commit: a979415 | 维护规则见 AGENTS.md「历史上下文」节

## 使命与当前状态

本模块是 gomob 所有业务线的地基, 四块拼图: ① **开发环境与 dev.sh 工作流** (CentOS 9 + /opt/android-sdk + VNC 远程 + podman 容器); ② **Go 服务端** (`server/` mono-repo, 14+ 服务, devserver 为 dev 全栈唯一网关) 与治理体系 (registry / CodeGraph / 记忆守门 / BUGS.md); ③ **harness 自分析方法论** (`tests/harness/` 67 套 + `tests/native_host/` 30 个 host C++ 测试, 三态退码判定); ④ **M5 实时消息与第一视角协作** (自研 WS 控制面 + 自托管 LiveKit 媒体面)。

当前状态: 服务端主线完整可用, 激光/VIN/融合业务全跑在其上; harness 体系覆盖广但 M12 质量遗留收窄为 NDEBUG 断言 / flaky（PG 测试硬隔离已在 a979415 落盘）; M5 消息控制面与 LiveKit 接入已落, 但 M5.5-5.9 (通话/直播/录制/ASR) 的验收 harness 未建成闭环; 开发环境 **2026-07-09 曾被整机重置**, 重装后本机无 KVM → x86_64 加速模拟器不可用, UI 验证首选真机 WiFi adb, 兜底纯软仿真 `gomob_x86` AVD (见 07-09 节); 本机 podman 的 gomob-* 容器栈也在重置后未重建 (2026-07-26 实测为空), 接手 dev 全栈先 `./dev.sh up` (含容器栈 + devserver)。本机现有 Go 1.24，可跑纯 Go 测试；激光生产 cgo 构建/部署仍走 192.168.9.160。

## 决策时间线

### 2026-05-04 服务端化重规划 + 一日打底 14 服务 (M0.5 / M-S0~S9)
背景: 项目开局是单 App。用户拍板重规划为 5 tab App + Go 服务端 (87117b8), 当天 eb04282 落 **14 服务 + 18 张表 + 8 套 harness 全绿**, 14d6850 补 M-S9 shape-ref (mesh asset→签名URL→sha256 端到端)。服务划分按"每个服务围绕一个资源边界"第一性推导 (为何不单体/不更碎均有论证), 权威在 `docs/architecture/server/00-server-overview.md`。App 只持有一个网关地址, 登录页 UDP 服务发现优先。dev 基础设施端口全部非默认: pg 15432 / nats 14222 / redis 16379 / minio 19000。M-S10 cv-engine 与 LLM 网关的业务演进见 `docs/context/vin-pipeline.md`。

### 2026-05-04 起 harness 方法论落地 (承 gogame)
harness 是从源仓 gogame 继承的核心纪律: 单测验"代码对不对", harness 验"行为好不好"。二段式 `run.sh` (产出写 `.dev/<名称>/`) + `analyze.py` (必须输出 PASS/WARN/FAIL + 原因, 退码 0/1/2), 五条触发标准 (涌现/参数敏感/长时序/LLM/多Agent) 命中即先建 harness 再写业务; 三档 smoke/soak/stress 靠 env var 缩放。证据: `docs/agent-memory/feedback_harness_mandatory.md` / `feedback_dev_loop.md`; 首批 8 套在 eb04282 (git ls-tree 实证): auth_flow / admin_lifecycle / catalog_lifecycle / inspection_lifecycle / vinref_lifecycle / llm_streaming / model_canary_switch / ws_message_order。

### 2026-05-05 VNC 远程开发定调
用户明确"我都用 vnc 远程的": 所有 GUI (emulator/浏览器/eog) 必须显示在 TigerVNC DISPLAY=:1 (rfbport 5901), Xvfb headless 等于白装 (49b7095/300e44d)。后续追加: 模拟器常开勿关 (agent 不要 emu-stop), 装 App 后设 stayon 防息屏黑屏。证据: auto-memory `feedback_vnc_remote_dev.md`。聊天里图片链接打不开 → 看图用 `DISPLAY=:1 eog` 弹到 VNC 桌面 (auto-memory `feedback_local_image_link_format`, 2026-07-10 定)。

### 2026-05-06 容器运行时统一 podman, 弃 docker
背景: 早期误起 docker compose 与 podman 同占 :5432/:6379 等端口, docker-proxy 抢到 bind 但流量进 podman, migration 打到空 PG 报 "relation devices does not exist" 排查半小时。用户拍板"可以不需要 docker 了" → a7a6ef7/71a291a 全面移除。基础设施容器 = 4 核心 `gomob-pg/redis/nats/minio` + `gomob-livekit` (dev.sh `server up` 五个一起拉; 脚本内 `PODMAN_CONTAINERS` 只列 4 核心, livekit 单独 ensure; .160 生产同款另部署), 挂 named volume, `./dev.sh server up/down/ps/logs` 直通 podman。注意: 2026-07-26 实测本机 podman 已无任何 gomob-* 容器/数据卷 (07-09 重置后未重建), "常驻"需先 `server up` 恢复。Dockerfile 文件名保留 (OCI 标准, podman build 同吃)。证据: auto-memory `feedback_runtime_split_dev_prod.md`。

### 2026-05-07 端→服务端日志同步全链路 (b0ce30a)
core:logging + `/v1/logs/upload` + `tests/harness/logs_upload` + `scripts/tail-user-logs.sh`, 让真机问题可以从服务端侧读日志定位, 支撑「默认不截图、优先日志判断」的 UI 验证规范。

### 2026-05-08~13 M5 实时消息控制面自研 (914b04c → 40de696)
自研 WebSocket + REST + PostgreSQL 控制面 (消息顺序/会话/未读/邀请/审计), devserver 挂消息路由 (b286cc3)。三个踩坑沉淀在 `docs/agent-memory/finding_android_realtime_ws_devserver_2026-05-09.md`: ① OkHttp `newWebSocket` 必须传 http scheme, ws:// 会崩; ② devserver 访问日志包装 ResponseWriter 必须透传 `http.Hijacker`, 否则 `/v1/ws` 升级返 500 (独立 harness 暴露不了, 只有合体服务会); ③ HTTP fallback 发消息必须进同一条实时投递链 (`RealtimeMessageNotifier`), 且只对 AppendIdempotent 新插入行推 `msg.recv` 防重。消息资产 migration 0011 让 `inspection_id` 可空, 否则语音上传 500。

### 2026-05-24 LiveKit 媒体面接入 (f7951b9, M5.4) + emulator 无网证实
第一性分界 (设计权威 `docs/architecture/09-realtime-message-live.md`): **控制面自研落 PG, 媒体面交自托管 LiveKit SFU**, 不自研 RTP、不用 gomob WS 转发媒体; signaling 的 `call.invite` 从 P2P SDP 语义升级为"邀请加入 media room"。同期证实: `dev.sh emu-start` 为避 netsim/packet-streamer 崩溃关掉 VirtioWifi/ModemSim, 代价是模拟器**整张路由表为空** — HTTP/WS 靠 adb reverse 走通造成"有网"错觉, 但 WebRTC ICE 必败 (session 15s 超时)。结论: 控制面可在 emu 验到 `Room.connect` 为止, **媒体面端到端只能真机**。证据: auto-memory `finding_emulator_webrtc_no_net_2026-05-24.md`。

### 2026-06-02 RgbdStreamClient → gorob 边缘 (8eca9fc)
把 gomob 手机 + Berxel 深度相机当 gorob 机器人的眼睛: WS 二进制 protobuf (手写编码 `stream/Proto.kt`, 不引 gradle 插件), 契约 = gorob `proto/rgbd.proto` 复制入仓。状态: **代码已写、未在安卓设备实测** (`docs/architecture/14-rgbd-stream-gorob.md` 自述)。

### 2026-06-03~04 devserver 成为 dev 全栈唯一入口 + 一键拉起配方
M8' 激光下沉服务端当天 (fde8b7a) devserver 并入激光路由, 成为 all-in-one 网关 (反代 laserworker/cvengine, 桥 NATS→WS); 激光业务本身见 `docs/context/laser-station.md`。devserver **非守护进程**(机器重启/会话切换后经常已死), 症状 = App 全链 `unexpected end of stream on :18808`, 极易误判成模拟器/参数问题; 一键拉起已固化为 `./dev.sh up` (容器栈 + ASR + 后台 devserver + 健康等待 + adb reverse)。诊断顺序 + 手动 build/env 配方 + dev seed 账号 `shenhm/shenhm123` (每次启动重置必可登) 全在 `docs/agent-memory/finding_dev_stack_local_startup_2026-06-04.md`。laserworker 是 cgo 二进制 (`-tags laser_cgo` 链 PCL) 常驻 /tmp, 挂了才重建。

### 2026-06-13 从 gogame 同步方法论 + 治理工具 (102db33)
一次性移植: 3 principle (动手三问/省算力结果等价) + 9 feedback (批判性思考/整体非打补丁/mock-first I/O 守恒/自测后再声明完成/验业务非只验 UI 等) + 工具 `scripts/codegraph.sh` / `check_doc_index.sh` / `sync-claude-memory.sh` + BUGS.md 约定; AGENTS.md 升为正式入口, worktree 政策改"仅并行才开"。CodeGraph 索引落 `.dev/codegraph/`, 覆盖 Kotlin/自有C++/Go/Python, third_party 不解析 (`docs/agent-memory/reference_codegraph_coverage_boundaries.md`)。

### 2026-06-20 M11 全量代码审查 71 条整改 (e9bcbd2/39d88de)
引 gogame review 策略全量扫描, 71 条处置 = **57 真修 + 7 demo-data 加 greppable 标记 (用户决策保留观感) + 9 结构性降级留 TODO**; 未完项出账为 TODO M11.A (接线/部署必做) 与 M11.B (结构性重构)。项目从"堆功能"转入"还债"节奏。完整明细在 `.dev/code-review/report.md` (gitignored 临时产物; 2026-07-26 实测仍在, 同目录 `tests-quality-report.md` 是 M12 明细)。

### 2026-06-21 M12 tests-quality 补扫 + 服务端可靠性组 (f21b019/03565f5/4efa0d9)
对 56 harness + 29 native_host 对抗式证伪, 确认 30 条 (CRIT 2/MED 6/LOW 22)。当轮修掉: ① `scan_quality/analyze.py` 算出 FAIL 却恒 exit 0 (虚假信心 CRIT); ② `eys3d-host-test.sh` 漏源文件整链失败 (CRIT); ③ 建 native 自动门 `scripts/host-tests-all.sh` 接入 `dev.sh native-test`/`ci` (此前 native host 测试零 CI)。03565f5 补 harness 三态退码/路径参数化安全批次; 4efa0d9 修服务端可靠性组 (cvengine goroutine 退出 / webhook 入库 / worker JetStream)。剩余见 TODO M12.A/M12.B。

### 2026-07-09 开发环境整机重置事件
机器环境被重置 (JDK17/Android SDK/AVD 全丢只剩 JRE 1.8), 当天重装齐: JDK 17.0.18 + /opt/android-sdk (build-tools 35+34, platforms 34/35, NDK 27.2.12479018, cmake 3.22.1)。**新宿主 (Xeon E5-2689 v4 虚机) 无 /dev/kvm** → x86_64 模拟器拒启 (当天 boot 等 7 分钟超时证实), 之前 5-6 月能跑模拟器的是旧宿主。UI 验证首选真机 WiFi adb (`adb mdns services`; 测试机池见 auto-memory `project_test_phone`), 兜底 `gomob_x86` AVD (32 位 x86 API30, `-accel off` 纯软仿真, 极慢但可验布局/IME/ANR — 07-10/11 的 ANR 排查与 A/B 验证即在其上完成)。烂网对策: sdkmanager 大包必损坏 → `curl -fL -C -` 直连 dl.google.com 手动解压 (build-tools zip 根目录叫 android-14 要改名); Python 大包 (open3d 447MB) 用 `aria2c -x16 -c` 抓 wheel 离线装 (auto-memory `reference_flaky_mirror_aria2c_install`)。证据: auto-memory `project_dev_env_2026-05-03.md` (07-09 快照) + session digest 3aaf07b3。**读到任何环境快照先 `./dev.sh doctor` 验证现状。**

### 2026-07-09 激光部署机 .160 拓扑固化 (auto-memory project_laser_deploy_host_160)
本开发机 192.168.9.159 现有 Go 1.24；激光 cgo 构建/生产服务仍全部 ssh 到 192.168.9.160 (rsync 差量同步)。.160 上服务手动 nohup (`.dev/services/gomob-{laserworker,devserver,laserstationweb}`, 无 systemd), pgrep 用 `[g]omob-laserworker` 括号技巧防自杀; podman gomob-pg 宿主端口 15432; MinIO 点云可从卷直读 (part.N 拼接后每 [32B bitrot 哈希][≤1MiB 数据] 剥头)。测量业务细节见 `docs/context/laser-station.md`。

### 2026-07-14 PostgreSQL 集成测试硬隔离 (已随 a979415 落盘)
背景: 激光背景事务测试用 `TRUNCATE ... CASCADE`, 当 `GOMOB_TEST_DB_DSN` 误指向共享 gomob 库时把真实扫描任务/site/region/background 整库级联清空, App 报"当前工位尚未保存外参"。**新规: 会改数据的 PG 测试只许连 harness 创建的唯一临时库 + `current_database()` 命名硬门 + 只按测试专属 session 行级清理; 恢复现场前先备份到 `.dev/db-backups/`**。证据: `docs/agent-memory/finding_postgres_integration_test_isolation_2026-07-14.md` (呼应 M12.2 的隔离欠账, 事故坐实了它的优先级)。

### 2026-07-26 laserstationweb 登录安全收口（M11.2）
删除按日期可推算的 `3d+MMDD` 弱口令；`GOMOB_LASER_STATION_PASSWORD` 成为唯一登录口令，缺失或少于 16 个 Unicode 字符时进程在监听前拒绝启动。默认监听 `127.0.0.1:5177`，需显式放宽地址并置于反代/鉴权之后；登录态默认 30 天，`GOMOB_LASER_STATION_SESSION_DAYS` 允许 1–90 天。缺失/弱口令/会话有效期测试已覆盖，TODO M11.2 已销账。

## 禁区与已证伪路线

- **禁 docker / docker compose**: 会与 podman 撞端口重演"migration 打进空 PG"事故; doctor 也不检查 docker。证据: auto-memory `feedback_runtime_split_dev_prod`。
- **禁 Xvfb headless 起 GUI**: 用户只看得到 DISPLAY=:1 (TigerVNC 5901); 也不要顺手 emu-stop/kill 常开模拟器。证据: `feedback_vnc_remote_dev`。
- **禁在模拟器上调 WebRTC/LiveKit 媒体面**: emu 无任何 net interface, ICE 必败; 看到 ICE fail 先 `adb shell ip addr` 而不是怀疑 LiveKit 配置。证据: `finding_emulator_webrtc_no_net_2026-05-24`。
- **禁 OkHttp WebSocket 用 ws:// scheme**; devserver 中间件包装 ResponseWriter 必须委托 Hijack()。证据: `finding_android_realtime_ws_devserver_2026-05-09`。
- **禁开 KSP2** (`ksp.useKSP2=false` 是唯一解): Hilt 2.53.x 在 KSP2 下 @AndroidEntryPoint transform 不生效; Hilt 明确支持 KSP2 前不要"为用新功能"打开。证据: `finding_ksp1_required_for_hilt_2_53.md`。
- **App 报 :18808 unexpected end of stream 时禁先怀疑模拟器/参数**: 根因永远先查 devserver 进程在不在 (`ss -ltnp | grep 18808`)。证据: `finding_dev_stack_local_startup_2026-06-04`。
- **禁止 PG 测试连共享库、禁 TRUNCATE CASCADE**: 已发生整库清空事故。证据: `finding_postgres_integration_test_isolation_2026-07-14`。
- **禁自研 RTP/SFU、禁用 gomob WS 转发媒体流**: 09 设计第一性结论, 媒体面 = 自托管 LiveKit + coturn + Egress。证据: `docs/architecture/09-realtime-message-live.md` §2。
- **PG harness 隔离纪律**: 已有独立临时库、`current_database()` 硬门和行级清理；仍禁止连共享库或 `TRUNCATE ... CASCADE`。证据: `finding_postgres_integration_test_isolation_2026-07-14.md`。
- **harness 分析器禁"打印一堆数据让人看"**: 必须三态可判定结论 + 对应退码; scan_quality 恒 exit 0 的虚假信心已是前车之鉴 (f21b019)。
- **产物禁落仓库根目录 / 模块目录**: 一律 `.dev/` (gitignored); **禁自动 git push** (仅用户明确要求时)。证据: AGENTS.md。
- **烂网下禁傻等 sdkmanager / pip 单流大包**: 必损坏/卡死, 走 curl -C - 或 aria2c 续传。证据: `project_dev_env` / `reference_flaky_mirror_aria2c_install`。

## 关键资产指针

- `dev.sh` — 唯一工作流入口: build/install/run/up (dev 全栈一键)/test/ci/native-test/harness/log/shot/record/release/reverse/adb-wifi/doctor/emu-start|stop/avd-create/server doctor|up|down|ps|logs|build|test|run|migrate|proto。
- `scripts/ensure-android-sdk.sh` — SDK 一键补装 (环境重置后救场用过); `scripts/adb-wifi.sh` — 真机 WiFi 配对; `scripts/shot-compress.py` — 截图压缩后再 Read。
- `scripts/host-tests-all.sh` — native host 测试自动门 (M12 建, 接 `dev.sh native-test`/`ci`); `scripts/codegraph.sh` / `check_doc_index.sh` / `sync-claude-memory.sh` — 治理三件套 (102db33 引入)。
- `server/` — Go mono-repo; `server/cmd/` 23 个二进制: devserver (dev all-in-one 网关) + gateway/api/auth/asset/signaling/worker/device/modelregistry/admin/catalog/vinref/shaperef/cvengine/llmgateway/fusionworker/asrworker/laserworker/laserstationweb/laserreplay + 3 个 harness 辅助 (realtimeharness/wsharness/deviceinteractionharness); `server/internal/` 同名实现包; `server/migrations/` SQL。
- `server/internal/vinalgo/{client.go,signer.go}` — 外部 VIN RSA-SHA1 签名器只接受 `GOMOB_VIN_ALGO_PRIVATE_KEY_FILE` 的部署只读挂载，缺失或无效即启动失败。
- `docs/architecture/server/00-server-overview.md` (14 服务边界与拆分论证) / `01-go-project-layout.md` / `02-api-contract.md` / `03-cvengine-migration.md`。
- `docs/architecture/registry/` — modules/dependencies/capabilities + server-modules/server-dependencies 五份 yaml, 机器可校验治理真理源 (S4 要求架构变更同步)。
- `docs/architecture/09-realtime-message-live.md` + `-implementation.md` — M5 权威设计与分节验收。
- `docs/architecture/14-rgbd-stream-gorob.md` — RGBD 流喂 gorob 边缘的线缆契约 (代码已写未真机实测)。
- `tests/harness/` — 67 套; 服务端组: auth_flow / admin_lifecycle / inspection_lifecycle / catalog_lifecycle / shaperef_lifecycle / vinref_lifecycle / llm_quota / llm_fallback / llm_streaming / logs_upload / model_canary_switch / cv_hmac_auth; 实时组: ws_message_order / realtime_message_sync / device_realtime_interaction / asr_transcript_queue; 其余归各业务模块。`tests/harness/README.md` 有三档约定。
- `tests/native_host/` — 30 个 host C++ 测试 (berxel/eys3d/icp/tsdf/measurement 等), 经 host-tests-all.sh 出门。
- agent-memory 本模块核心: `feedback_harness_mandatory.md` / `feedback_dev_loop.md` / `finding_dev_stack_local_startup_2026-06-04.md` / `finding_android_realtime_ws_devserver_2026-05-09.md` / `finding_postgres_integration_test_isolation_2026-07-14.md` / `finding_emulator_setup_2026-05-04.md` / `finding_ksp1_required_for_hilt_2_53.md`。
- auto-memory (`/root/.claude/projects/-root-lilw-gomob/memory/`): `project_dev_env_2026-05-03.md` (环境快照) / `feedback_runtime_split_dev_prod.md` (podman) / `feedback_vnc_remote_dev.md` / `finding_emulator_webrtc_no_net_2026-05-24.md` / `reference_flaky_mirror_aria2c_install.md` / `project_laser_deploy_host_160.md` (.160 部署拓扑) / `project_test_phone.md` (测试机池)。

## 未竟事项

- **M5.5-5.9 实时协作验收未闭环**: 验收要求的 `livekit_room_lifecycle` / `livekit_call_quality` / `first_person_live_quality` / `live_recording_egress` 四个 harness 在 `tests/harness/` 中尚不存在; M5.9 ASR 的 `asr_transcript_queue` 已有 (无模型环境路径), 但"有模型环境 CER≤5% / 关键词召回≥98%"未见验证记录 (TODO M5)。
- **服务端与治理 S1-S4**: App 端标定同步 (sha256 比对拉取); shape compare 升几何级 (chamfer/Hausdorff); cvengine gRPC server 生成接入 (`server/proto/cvengine.proto`); registry yaml 随架构变更持续同步 (TODO「服务端与治理待办」节)。
- **M11.A 接线/部署必做**: M11.1 P100R3 出厂内参 blob JNI 下发 (CRIT, 归 berxel 模块但欠账挂此处出账); M11.5 Room migration 测试进 CI; M11.6 https/wss TLS 入口。
- **M11.B 结构性重构**: M11.7 MessageDao withTransaction 原子化; M11.9 MessageRepository 去 Noop 默认参; M11.10 拒接通知服务端 fanout; M11.11 core/media 空模块下沉真 LiveKit 实现或删除; M11.15 eYs3D 实验取流路径 build-flag 物理隔离。
- **M12 遗留**: M12.3 native 断言去 NDEBUG 依赖 + eys3d smoke 永真占位换真判据; M12.5 固定 sleep → 轮询健康检查 + curl --max-time; M12.6 scan_bundle_roundtrip 改用真产物校验契约。
- **finding_emulator_setup 待订正标注**: 模拟器矛盾已核实 (2026-07-26 实测): 本机确无 KVM (`/dev/kvm` 缺失, CPU 无 vmx/svm), x86_64 加速 AVD `gomob_test` 自 07-09 启动失败后未再运行过; 其 2026-07-11 冷启动/键盘/SkiaVK A/B 验证实际跑在纯软仿真 `gomob_x86` (`-accel off`, port 5558; 证据 = `~/.android/avd/gomob_x86.avd/emu-launch-params.txt` + `.dev/anr-diagnosis/ab/` 07-11 goldfish logcat), 与 `project_dev_env` 不矛盾。该 finding 的「已验证」节未写明所用 AVD, 待补一句订正。
- **RGBD→gorob 流真机实测** (docs/architecture/14 自述缺口): 需 `./dev.sh install` 推真机 + 连 gorob 边缘验证。
