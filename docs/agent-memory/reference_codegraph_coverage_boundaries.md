---
name: CodeGraph 覆盖边界
description: gomob CodeGraph 已索引 Kotlin/C++/Go/Python/C；third_party 厂商二进制不解析，裸名跨包重名需消歧
type: reference
---

# CodeGraph 覆盖边界

代码结构定位、调用链、被调链、影响面分析优先用 CodeGraph（MCP 工具或 `./scripts/codegraph.sh`）；
精确文本 / 日志 / 配置片段匹配仍优先用 `rg`。本文记录 gomob 实际覆盖边界，避免误信查询结果。

## 已索引内容（2026-06-12 实测）

`./scripts/codegraph.sh status`：1,399 文件 / 45,907 节点 / 88,078 边。按语言：

- **kotlin 226** — Android 端全部模块（`app` / `feature:*` / `core:*`），含 Compose、ViewModel、Hilt 注入点。
- **cpp 765 + c 46** — `native/` 原生层（depth / vin / fusion / reconstruction / jni）。
- **go 236** — 服务端（devserver / cvengine 反代等），随重建主线端云融合的服务端代码。
- **python 81** — harness `analyze.py`、cvengine、采样脚本。
- xml 35 / yaml 5 / properties 2 — 资源、registry、gradle 配置。

## 边界与陷阱

1. **third_party 厂商二进制不解析**：`third_party/berxel-android/` 的 jar / 多 ABI `.so` 是二进制，CodeGraph 不入图。查 Berxel SDK 内部符号要靠反编译产物 / `nm -DC` / `llvm-objdump`，不是 CodeGraph。
2. **JNI 跨语言边界不连边**：Kotlin `external fun` 到 C++ 实现是运行期动态绑定，CodeGraph 不会把 `NativeBridge` 的 `external fun` 自动连到 `native/jni/` 的实现函数。跨 JNI 追调用链要人工对照函数名（`Java_io_gomob_..._xxx`）。
3. **裸名跨包重名要消歧**：`callers` / `impact` / `explore` 按裸符号名聚合时可能混不同模块的同名符号（Go 短名、Kotlin 同名扩展函数）。读结果里 "Aggregated across N symbols" 注记，按文件路径消歧。
4. **blast-radius 的 "no covering tests" 是启发式**，别盲信；以实际 harness / 单测覆盖为准。
5. **索引时效**：MCP 模式由 watcher 自动跟踪（约秒级滞后）；CLI 查询前、切分支 / `git pull` / 大量重命名后先跑 `./scripts/codegraph.sh sync`；结果明显过旧或结构异常再 `./scripts/codegraph.sh index --force` 干净重建。

## How to apply

- 问"X 怎么工作 / 调用链 / 影响面" → 先一次 CodeGraph 查询（MCP `codegraph_explore` 或 `./scripts/codegraph.sh callers|callees|impact`），别开 grep+读循环重复造轮子。
- 命中上述边界（厂商二进制、JNI 跨界、重名）时退回 `rg` + 反编译产物补齐，并在结论里标明走的是哪条路径。
- 索引数据固定在 `.dev/codegraph/`，根目录 `.codegraph` 只是软链，不提交 git。
