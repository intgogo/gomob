# 跨 Agent 共享记忆索引

> 本文件是索引，**不是**记忆载体本身。每个条目是一行 `- [标题](文件名.md) — 一句话摘要`。
> 控制在 ~150 行内，超出考虑分主题文件。

## 顶级原则（principle）

- [第一性原则选最优, 开发设计不做妥协](principle_first_principles_no_compromise.md) — 项目级硬规则：分叉时按第一性推导最优解，不因实现成本退到妥协解。

## 写作 / 协作纪律（feedback）

- [无妥协架构原则](feedback_no_compromise.md) — 发现架构问题不调参凑合，直接重做。
- [先设计后实现](feedback_first_principles_design.md) — 不从"现有代码改动最小"出发，先写正确设计再看复用。
- [Plan / Spec / TODO 写作质量硬规](feedback_plan_writing_quality.md) — 无占位符 / 批判性复审 / 任务按 harness 可验收单元切。
- [开发闭环：自驱动验证](feedback_dev_loop.md) — 写完功能必须自己跑通采样 → 分析闭环，不等用户报。
- [设计决策风格偏好](feedback_design_style.md) — 激进决策、长期主义、严格反馈、设计/实施文档分层。
- [Git Push 策略](feedback_git_push_policy.md) — 本地可 commit；只在用户明确要求时 push。
- [UI 改动必须真实运行并截图自查](feedback_ui_visual_verification.md) — 涉及 Compose 界面/HUD/布局改动必须 install 真机/模拟器 + 截图复核。
- [用户全程 VNC 远程](feedback_vnc_remote_dev.md) — emulator / 任何 GUI 必须走 DISPLAY=:1（TigerVNC 5901 端口），不能起 Xvfb headless 否则用户看不到。
- [重要不确定模块必须建 harness](feedback_harness_mandatory.md) — 五条触发标准命中时先建 harness 再写业务代码。

## 项目 / 参考（reference）

- [Berxel SDK 资源位置](reference_berxel_sdk_locations.md) — Windows SDK 头文件 / 样例 / VIN 文档的 SMB 挂载点路径。
- [iHawk P100R3.0 产品规格](reference_iHawkP100R3_spec.md) — gomob 实际硬件型号；工作距离 0.2-8m / 理想 0.25-2m / 精度 ≤1%@1-2m。native 阈值真理源。
- [gogame 方法论源仓](reference_gogame_methodology_origin.md) — 本项目的方法论源头是 `/root/lilw/gogame`，可回溯。

## 历史决策（finding / design）

- [**重建主线 2026-05-07 重大方向变更**](finding_multiview_rgbd_pivot_2026-05-07.md) — 实时 SLAM → 多视角 RGBD 配准 + 端云融合；权威设计在 04b；既有 native 沉淀阶段 3 复用。**新工作不要扩 04 路线**。
- [模拟器在本机的可工作配置 2026-05-04](finding_emulator_setup_2026-05-04.md) — SwiftShader SIGSEGV；必须 `-gpu host` + `DISPLAY=:1` 走 NVIDIA。
- [KSP2 + Hilt 2.53.x 不兼容](finding_ksp1_required_for_hilt_2_53.md) — `ksp.useKSP2=false` 是当前唯一解。
- [mob3d 设计最新交付核对 2026-05-05](finding_mob3d_handoff_realign_2026-05-05.md) — 现仓本就是 handoff 业务化升级版；本次只补 token + 微观对齐 + FirstPersonViewer 重写 + 删 PlaceholderScreen。
