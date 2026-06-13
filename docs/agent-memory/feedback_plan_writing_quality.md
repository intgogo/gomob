---
name: plan / spec / TODO 写作质量硬规
description: 无占位符 / 批判性复审 / 任务按 harness 可验收单元切而非时间切
type: feedback
---

# Plan / Spec / TODO 写作质量硬规

**立项**：从 obra/superpowers 提炼有价值部分 + 项目方法论融合。

## 适用场景

- 在 `TODO.md` 写"## 进行中: <topic>"节，列实施步骤
- 在 `docs/architecture/` 或专题文档里写实施计划章节
- 写设计 spec（含 design / specification / proposal 类文档）
- 任何**留给未来自己 / Codex / 其它 Agent 执行**的清单

## 规则 1：无占位符

每一步必须给执行者**实际可用的内容**。以下是**写作失败**，永远不写：

| 红旗 | 为什么不行 | 怎么写才对 |
|------|----------|----------|
| "TBD" / "待补充" / "细节稍后填" | 推延决策给未来自己 | 当下不能定就回 design 阶段，别埋雷 |
| "做适当的错误处理" / "加校验" / "处理边界情况" | 等于没写，执行时人人解读不同 | 列出具体错误场景 + 期望行为 |
| "为上述写测试"（无具体代码） | 留空 / 跑题 / 假装覆盖 | 给出测试函数名 + 输入 + 期望 |
| "类似 Task N" / "同 Task M 的处理" | 执行者可能跳读，Task M 可能改了 | 重写一遍，不引用 |
| 描述要做什么但不展示怎么做 | 卡执行 / 引发歧义 | 涉及代码就贴代码块，涉及命令就贴命令 |
| 引用任何任务里都没定义的类型 / 函数 / 字段 | 编译失败 | 先在前置 task 定义，后续 task 引用 |

**Why：** 占位符是"开发者写给自己的债"；留 TBD 等于把"想清楚"推延到执行时，执行时上下文已经稀释，必然踩坑。

**How to apply：** 写完任何 task / step，自审一遍，搜上表的红旗模式，一个不留。

## 规则 2：任务颗粒度按 harness 可验收单元切，不按时间切

**反 "2-5 分钟 / step + 每 task 立即 commit" 默认**：本项目不照搬。

- **涌现行为模块**（点云融合 / 重建网格 / 时间同步阈值 / 多视角配准 / 长时序漂移，命中
  `feedback_harness_mandatory.md` 五条触发标准）：task 颗粒度 = "改动覆盖 + 跑 harness +
  整体画像通过"，才提交。**单 step 单测过 ≠ 整体行为正确**（见
  `feedback_holistic_not_patching.md` / `principle_first_principles_no_compromise.md`）
- **确定性 utility 模块**（深度反投影数学 / 几何 / proto 序列化 / 配置解析，无涌现性）：
  task 可以 bite-sized + TDD（失败测试 → 实现 → 通过 → 提交），这种地方 small-step 模板适用

**Why：** 在涌现模块按时间切 + 频繁 commit，等于**"小步走错方向"积累成系统性退化** —
改一个配准阈值单测全过，但 harness 跑出来点云对齐误差上升，已经污染 git 历史。

**How to apply：**
1. 写实施清单前先判定模块类型（涌现 / 确定性）
2. 涌现模块：task = harness 验收单元，每 task 末尾必含"跑 `tests/harness/<name>/` 验收"step
3. 确定性模块：bite-sized TDD + 频繁提交可以
4. 一个 plan 里两类模块都有？分节，各按各的纪律

## 规则 3：接到他人（或自己上次会话）的 plan，先批判性复审

**别上来就执行**，先 sanity check 一遍：

| Checklist | 检查内容 |
|-----------|---------|
| **registry 触发** | 涉及 `core/*` 之间或 `feature → core` 依赖、模块边界、能力成熟度变化？plan 里有"更新 docs/architecture/registry/{modules,dependencies,capabilities}.yaml"task 吗？ |
| **harness 触发** | 命中 CLAUDE.md "何时必须建 harness" 五条（涌现 / 参数敏感 / 长时序 / LLM / 多 Agent）？plan 里有 harness 设计 + 收敛验证 task 吗？ |
| **类型一致性** | Task N 用 `RgbdFrame.depth: ShortArray`，Task M 用 `IntArray` — 一致吗？跨任务的字段 / 方法名是否对齐？ |
| **第一性反模式扫描** | plan 里有没有 "先 hardcode 后面再换" / "先做简单版本" / "在 A 加 fallback 等 B 就绪" / "保留旧逻辑兜底"？见 `principle_first_principles_no_compromise.md` |
| **UI 联动** | 触及 Compose 界面 / HUD / 3D 预览？有真实运行 + logcat / uiautomator / instrumentation / harness 验收吗？不要把截图列成默认验收，除非用户主动要求（见 `feedback_ui_visual_verification.md`） |
| **JNI 联动** | 改 NativeBridge 签名 / 数据契约？plan 里有"同步改 native/jni/ 入口 + native host smoke test"task 吗？数据契约改动是否下沉 `core:model`？ |
| **端云联动** | 触及云端 Open3D multiway_registration / PGO 路径？有端到端采样 → 上传 → 配准结果回看的验收 task 吗？ |

**有疑问立即停下问用户，不瞎猜瞎改 plan 自顾自冲。**

**Why：** plan 是接力棒，上游写得潦草下游必踩坑。批判性复审是接棒的最后机会，把问题挡在执行外。

**How to apply：** 收到 plan / 拿起自己上次会话留的 plan，第一件事跑这张 checklist，5 分钟动作。有 ≥1 项命中 → 修 plan / 问用户，不进执行。

## 规则 4：给执行者**零仓库上下文**级别的清晰度

写实施清单时假设执行者：
- 是个**熟练 Android 开发者**，但对**本仓库 / 本工具链**几乎零知识
- **测试设计**也不熟 — 别写"加合适的测试"，写出测试函数和断言

每一步包含：
- 具体文件路径（`feature/scan3d/src/main/kotlin/io/gomob/feature/scan3d/LaserScanViewModel.kt` 不是"扫描模块的 ViewModel"）
- 完整代码（变更代码就贴代码，不只描述意图）
- 确切命令（`./dev.sh test :feature:scan3d` 不是"跑测试"）
- 预期输出（`BUILD SUCCESSFUL` / `FAILED with: Type mismatch...` / 具体 metric 数值）

## 规则 5：TODO.md 是单一真理源，不另起 plan 文档

项目硬规：`Track tasks in TODO.md`；不另起 plan 文档，不使用本地临时 TODO 替代真理源。

**不另建** `docs/plans/<topic>.md` 之类目录（违反"单一真理源"原则）。复杂 plan 的写法：

- **轻量** plan：直接写到 `TODO.md` "## 进行中: <topic>" 节，含上述四规则要求的全部细节（路径 / 代码 / 命令 / 预期）
- **超大** plan（跨多 Phase / 多 metric / 长时序）：升格成 `docs/architecture/<NN>-<topic>.md` 专题
  文档，**同步更新 registry/docs.yaml**，TODO.md 只放索引行

完成后：
- 轻量：删 "## 进行中" 节，改动留 git log
- 升格：专题文档保留（历史价值），TODO.md 索引行删

**Why：** 双源任务跟踪（TODO.md + plan 文档）必然不同步，Agent 不知道以哪个为准。

## 上下文

- 配套 `principle_first_principles_no_compromise.md`（★ 顶级原则 — 选择层）— 本规则是**写作层**对应的执行纪律
- 配套 `feedback_dev_loop.md`（开发闭环）— 本规则关注**清单怎么写**，dev_loop 关注**写完怎么验**
- 配套 `feedback_no_compromise.md`（零妥协架构）— 规则 3 第一性反模式扫描即是 no_compromise 的工具化
- 配套 `feedback_harness_mandatory.md`（harness 强制）— 规则 2 的模块类型判定与之同源
- 来源：从 [obra/superpowers](https://github.com/obra/superpowers) 的 brainstorming / writing-plans / executing-plans 提炼**真增量**，其余（HARD-GATE 全适用 / TDD 默认 / Visual Companion / subagent / 独立 docs/specs/ 路径）经判定与项目方法论冲突，**未引入**
