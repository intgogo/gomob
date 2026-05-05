# 03 — cv-engine 迁移闭包审计

> 对 `/root/lilw/gosmart` 跑 `go list -deps ./apps/api/ivv/...` 摸出来的真实依赖闭包。
> M-S10 阶段实施前必读，避免动手时发现依赖体积远超预期。

## 1. 闭包顶层视图

```
gomob server/cmd/cvengine
   └─ server/internal/cvengine            (= gosmart/apps/api/ivv 重命名)
        │
        ├─ Go 闭包 ───────────────┐
        │   gosmart/engine/gocv    │── cgo binding
        │   gosmart/engine/image   │── 纯 Go 图像处理
        │   gosmart/engine/async   │── 异步原语（待评估去留）
        │   gosmart/util/*         │── conv / crypt / datetime / fs / json /
        │                              levenshtein / logs / pack
        │
        ├─ C/C++ 闭包（cgo 链接）─┐
        │   gosmart/engine/ccv     │── 554 MB 二进制 + 源码
        │     ├ libccv.a / libccv.so
        │     ├ libccv_atlas.so / libccv_lynxi（昇腾 / 灵汐异构卡，可选）
        │     ├ libengine_crypt.so
        │     └ src/ + include/    │── ccv 自家 C++ 源码
        │   /usr/local/lib/libopencv_world.so
        │   /usr/local/onnxruntime/lib/libonnxruntime.so
        │
        └─ 第三方 Go ──────────────┐
            gin / logrus           │
            gonum.org/v1/gonum     │── 矩阵 / 优化 / 统计（VIN 几何拟合用）
            nats-io/nats.go        │── 已在用 NATS，gomob 直接复用
            其它（待裁剪，见 §4）
```

## 2. 体积与文件数

| 路径（gosmart） | 大小 | 文件数 | 角色 |
|----------------|------|-------|------|
| `apps/api/ivv` | 584 KB | 27 | **核心业务**：VIN 检测 / 字形比对 / 拓印识别 |
| `engine/gocv` | 588 KB | ~30 | **cgo binding**：OpenCV / ONNX / libccv 包装 |
| `engine/ccv` | **554 MB** | — | **C/C++ 库 + 源码**：含 `libccv.{a,so}` / `libccv_atlas.so` / `libengine_crypt.so` / `src/` |
| `engine/image` | 376 KB | — | 纯 Go 图像处理工具 |
| `engine/async` | 2.2 MB | — | 异步原语（gomob 引入 NATS 后多数可剪） |
| `util/*` | 592 KB | — | 工具集（部分迁，见 §3） |

## 3. 必迁 / 重写 / 丢弃 三分

| 包 | 处置 | 理由 |
|----|------|------|
| **apps/api/ivv** | **必迁** | 业务核心；按目录搬到 `server/internal/cvengine/`，命名空间从 `gosmart/apps/api/ivv` → `io.gomob/server/internal/cvengine` |
| **engine/gocv** | **必迁** | cgo binding，被 ivv 直接依赖 |
| **engine/ccv** | **必迁（含二进制 + 头文件）** | 上游 C 库 + 静态/动态库；落到 `server/internal/cvengine/ccv/`，并入 cv-engine Dockerfile 镜像 |
| **engine/image** | **必迁** | 纯 Go 图像工具 |
| **util/levenshtein** | **必迁** | VIN 字符串相似度 |
| **util/{conv,crypt,datetime,fs,json,pack}** | **必迁** | ivv 直接 / 间接用到；裁剪冗余函数 |
| **util/logs** | **重写** | 替换为 gomob 统一 `pkg/logger`（zap） |
| **util/license** | **丢弃** | gomob 是闭源服务，不做 license 校验 |
| **engine/async** | **重写或丢弃** | gomob 引入 NATS + Go 原生 channel 已足够；ivv 用到的部分用 NATS subject 替代 |
| **apps/data** | **重写** | 用 sqlite/xorm 做 license/settings/车型字典 → 改为 PG + vehicle-catalog + model-registry |
| **apps/db** | **丢弃** | 被 apps/data 引入；gomob 用 `pkg/repo` 抽象 |
| **apps/core** | **部分迁** | `core.Init` 中 license 检查丢弃；模型加载逻辑改为调 model-registry |
| **apps/model** | **重写** | gomob 用 `pkg/ent` 定义 PG 映射 |
| **apps/config** | **重写** | 用 gomob 统一 yaml + env override |
| **apps/apps.go**（路由部分） | **必迁** | `/cv/ocr/v1/vin_*` 路由注册搬入 cv-engine 主入口 |

## 4. 第三方 Go 依赖处置

### 4.1 必保留

| 包 | 用途 |
|----|------|
| `github.com/gin-gonic/gin` | HTTP server |
| `github.com/sirupsen/logrus` | 迁移期保留；M-S10.6 后切到 zap |
| `github.com/nats-io/nats.go` | 模型版本热更订阅；NATS 已是 gomob 标配 |
| `gonum.org/v1/gonum/{mat,floats,optimize,stat}` | 矩阵 / 几何拟合 / 字形特征比对 |

### 4.2 通过 apps/data 间接拉入，**不需要**

| 包 | 来自 |
|----|------|
| `github.com/xormplus/{xorm,builder,core}` | apps/db → apps/data |
| `github.com/mattn/go-sqlite3` | 同上 |
| `github.com/robfig/go-cache` | apps/data 本地缓存 |
| `github.com/agrison/go-tablib` / `mxj` / `Chronokeeper/anyxml` | go-tablib 的 xml/json 工具链；ivv 不直接用 |
| `github.com/CloudyKit/{fastprinter,jet}` | jet 模板引擎；ivv 不需要 |
| `github.com/tealeg/xlsx` | xlsx 导出，不在线上路径 |
| `github.com/foxiswho/echo-go/util` | 历史 echo 框架遗留 |
| `github.com/ugorji/go/codec` | go-tablib 间接 |
| `github.com/fatih/structs` | 同上 |
| `github.com/bndr/gotabulate` | 同上 |
| `github.com/fsnotify/fsnotify` | apps/config 用，不需要（gomob 改 yaml） |

去掉 apps/data + apps/db + apps/config 后，上面这些依赖会自然脱落。**不要**手动 vendor 它们到 gomob。

## 5. 阻塞性发现：cgo + C 库

**这是 M-S10 的最大成本，必须先动手验证**：

1. **构建链复杂度**：cv-engine 镜像必须有：
   - `libccv.so` / `libccv.a`（gosmart 自带；预编译 x86_64 / arm64 二进制）
   - `libopencv_world.so`（来自 OpenCV，需要正确版本，gosmart Dockerfile 里有）
   - `libonnxruntime.so`（来自 ONNX Runtime）
   - `libengine_crypt.so`（gosmart 自带）
   - `gcc` + `cgo` 工具链；`CXXFLAGS=--std=c++11`

2. **部署形态约束**：
   - cv-engine 镜像不可能"瘦"，base 镜像至少 1-2 GB（OpenCV + ONNX）
   - **不能** 与其它 Go 微服务塞进同一 multi-stage 镜像 — 单独 Dockerfile
   - GPU / 异构卡支持（atlas / lynxi / openvino）通过 build tag 选择，多镜像变体（如 `gomob-cvengine:cpu` / `gomob-cvengine:atlas`）

3. **跨平台**：开发机（CentOS 9）与生产环境（k8s 节点）都需要相同版本 `libstdc++` ABI；用 Dockerfile 锁死 base image 是唯一稳的做法

## 6. M-S10 实施细化（基于本审计修订）

> 替代 `TODO.md` 的 M-S10.1 / M-S10.2，更具体。

| 子任务 | 验收 |
|--------|------|
| **M-S10.1a** 把 gosmart `engine/ccv/` 整目录拷到 `server/internal/cvengine/ccv/`，含 `.a` `.so` `include/` `src/` `Makefile` | `ls -la` 校验文件完整；`libccv.so` `libopencv_world.so` 等关键 .so 存在 |
| **M-S10.1b** 把 `engine/gocv/` 拷到 `server/internal/cvengine/gocv/`，cgo 头文件路径从 `../ccv/include` 改为相对 `server/internal/cvengine/ccv/include` | `go build -tags=cv ./internal/cvengine/gocv` 在 CentOS 9 上无 cgo 链接错通过 |
| **M-S10.1c** 把 `apps/api/ivv/` 拷到 `server/internal/cvengine/ivv/`；用 `gofmt` + 全局替换 `gosmart/...` → `io.gomob/server/internal/cvengine/...` | 包内 `go build ./internal/cvengine/ivv` 通过 |
| **M-S10.1d** 把 `engine/image/`、`util/{conv,crypt,datetime,fs,json,levenshtein,pack}` 拷过来，剔除 license / xorm 相关引用 | `go vet` 全绿 |
| **M-S10.2a** 写 `cmd/cvengine/main.go`：gin 注册 `/cv/ocr/v1/*` 路由（迁 `apps/apps.go` 路由片段），加 JWT 中间件 + HMAC 验签中间件 | curl `/cv/ocr/v1/vin_detect` 在 dev 环境跑通（与 gosmart 同样输入产出一致） |
| **M-S10.2b** 替换 `apps/data` 中"按车型拉对照样本"的逻辑：从 sqlite 改为 gRPC 调 vin-ref（M-S8 已交付） | 同一组测试样本输出 char_similarity 数组 |
| **M-S10.2c** 写 `Dockerfile.cvengine`：base `centos:stream9`，COPY ccv/.so + 设置 `LD_LIBRARY_PATH`；构建 `gomob-cvengine:cpu` | `docker run gomob-cvengine:cpu` 启动后 `/healthz` 返 200（待模型加载完成） |
| **M-S10.4** 模型加载：调 model-registry gRPC 拉 active 版本元数据 → 调 asset 下载 .onnx → 加载到 onnxruntime；订阅 NATS `model.version.activated` 热更 | 切版本不重启服务，下一个请求用新模型 |
| **M-S10.6** 内部 gRPC 包装（cvengine.proto），与 HTTP 路由共享 handler | gRPC 单测覆盖 6 个 VIN 接口 |
| **M-S10.7** 与 vin-ref 对接：vin_compare 时按 vehicle_model_id gRPC 调 vin-ref（M-S8） | M-S8.4 用例覆盖 |
| **M-S10.8** harness `cv_vin_pipeline`：gosmart 时代基线样本集回归 | precision/recall 不低于 gosmart 时代 |

## 7. 迁移期间 gosmart 的状态

- gosmart 仓**保留**，作为：
  - 训练侧（`ml/`）继续迭代
  - 兜底：cv-engine 跑不通时，可回退用 gosmart 二进制（部署到内网另一机器，gomob `internal/cvengine_client/` 可改 baseURL 临时指向）
- gomob 与 gosmart 之间的契约**只有** HTTP API（`/cv/ocr/v1/*`），不存在代码互引用 — 这是 M-S10 的设计前提
- M-S10 完成后，gosmart 可以选择性下线线上服务部分（`apps/api/ivv` + `cmd/main`），仅保留 `ml/` 训练侧

## 8. 风险登记

| 风险 | 触发条件 | 缓解 |
|------|---------|------|
| ~~OpenCV 版本不兼容~~ ✅ 已消除 | — | **M-S10.1b spike 已通过**（2026-05-04，本机 CentOS 9 + gcc 11.5 链接 libccv/libopencv_world/libonnxruntime 全通过；详见 [.dev/spike-cgo/REPORT.md](../../../.dev/spike-cgo/REPORT.md)） |
| ONNX Runtime 模型加载失败 | model-registry 下发的 .onnx 是新训练的、ivv 期望的 input shape 不匹配 | M-S5（model-registry）入库时强制 schema 校验 |
| `libccv.so` 体积过大 | 部署到边缘节点 / k8s 限额 | 单独建 cv-engine 节点池；不与轻量 Go 服务共节点 |
| cgo 编译缓慢 | CI 编译时间从分钟拉到十分钟 | cv-engine 单独 CI workflow，base image 缓存 |
| 异构卡（atlas / lynxi）支持迁移成本 | 客户希望用昇腾 / 灵汐推理 | M2 阶段做 build tag 多变体；M1 仅 CPU + 可选 NVIDIA |
