---
name: Mock-first 但 I/O 守恒
description: SDK/真机未就绪可 mock/host harness 顶上，但必须走真实数据通道与契约，不做假 fallback。
type: feedback
---

# Mock-first 但 I/O 守恒

gomob 各层高度耦合（设备采集 ⇄ 几何反投影 ⇄ 配准融合 ⇄ 重建 ⇄ 端云同步），但开发阶段不可能同时真实化所有层。真实化是**移动探照灯**：灯下的层做到真实建模，灯外的层按 **BlackBoxMock + I/O 守恒** 顶上，接口零改动。这跟 CLAUDE.md「离线可运行是正式能力」是同一回事——mock / host harness 不是临时蒙混，是正式的离线能力，但必须守住数据通道与契约。

## 规则

1. **真实化是探照灯，不是大爆炸**
   - 任何阶段只有少数层在 Realistic 区（灯下），其它在 BlackBoxMock 区（灯外）。
   - 边界随阶段移动，每移一步**只换内部，接口零改动**。今天 mock 的 Berxel 真机出流，明天换成真 USB 流，上游 `core:model` 帧契约和 JNI 边界不动。

2. **Mock 区硬约束 = I/O 守恒**
   - mock 必须走**真实数据通道与契约**：RGBD 帧出 `core:model` 定义的帧结构、点云/网格走零拷贝 `DirectByteBuffer` / native 指针、跨端 payload 走真实的 `rgbd_bundle` 契约、过 JNI 时走 `core:native-bridge` 唯一入口。
   - 接口处的语义 100% 守恒：mock 的深度帧必须带可验证时间戳、真实的内参/外参字段、合理的有效距离裁剪（追溯 P100R3 规格），下游配准拿到就能算。
   - **内部空 OK，接口空不行**。host harness 可以喂离线采样的 RGBD，但喂出去的帧必须长得跟真机帧一模一样。

3. **三态合法，中间态非法**
   - Realistic（真实建模） / BlackBoxMock（I/O 守恒 + 内部用离线数据顶） / NotImplemented（明确未接，调用即报错而非静默返默认值）。
   - 任何「半真实半 mock」都视为违反，必须二选一。

4. **假 fallback / 假姿态 / 单帧伪装 = 不合格的 mock**
   - 项目魂是扫描真实化：真实=结果经得起近距离量测和反复复现。
   - 不合格的 mock 比 NotImplemented 更危险——它给真实化区喂错数据：硬编码相机姿态、漂亮但假的预览、单帧 demo 循环冒充多视角配准、离线 GLB 资产伪装真实 RGBD 重建链路。这些都不是 I/O 守恒，是 I/O 造假。
   - 「完全不实现」比「接口空 mock」更安全，因为后者污染下游。

## How to apply

- **每个真实化任务启动前先确认 mock 区合格**：灯外的层如果接口不守恒（时间戳缺失 / 内参伪造 / 帧结构不对），先补 mock 改造，不能跳过直接做灯下层。
- **B 不就绪时补 B，不在 A 里加假 fallback**：模块 B 未就绪就让它走 NotImplemented 或合格 mock，不在 A 里塞退化路径。详见 [无妥协架构原则](feedback_no_compromise.md)。
- **mock 也要过 harness 把关**：mock 帧喂进 `device_sync` / `scan_quality` / `cv_vin_pipeline` 等 harness，I/O 守恒不达标 harness 应能判出（时间戳偏差、密度异常、配准翻转）。这是 mock 的入门资格，不是优化项，详见 [重要不确定模块必须建 harness](feedback_harness_mandatory.md)。
- **离线采样数据是正式资产**：host harness 用的 usbmon 回放 / 离线 RGBD bundle 落 `.dev/`（gitignored），但其字段结构必须与真机一致，便于无缝切真机。
- 跟 [Phase 0 是骨架不是真实](feedback_phase_0_is_skeleton_not_realism.md) 联动：那条说「骨架阶段允许不真实」，这条说「不真实的根因常是 mock 不守恒」，两者是同一问题的两面。
- 跟 [第一性原则选最优, 开发设计不做妥协](principle_first_principles_no_compromise.md) 联动：不允许「为赶工 mock 先空着，后面补 I/O 守恒」。I/O 守恒是 mock 的准入门槛。
