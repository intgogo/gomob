# 11. P100R3 深度相机内嵌手机形态设计

> 设计文档(为什么 + 方向)。背景:外接 USB-C OTG 形态下 P100R3 反复挂死(详见
> [[../agent-memory/finding_p100r3_master_hang_recovery_2026-05-29]]),用户提出"把相机焊进手机内部"。
> 本文裁决该方向是否成立、内嵌怎么解三个反复挂的问题、分阶段怎么走、风险在哪。
> 完整四视角原始分析(供电/USB拓扑/固件驱动/可靠性工程)留存于 `.dev/embedded-camera-analysis/raw-lenses.md`。

## 11.1 核心结论

**内嵌真正干净解决的只有"供电"一个问题,但它由此把另两个挂死从"只能物理拔电"降级为"一根 GPIO 软件自愈"。**

- color MJPEG 秒挂 master、keepalive(XU5 set_cur)饿死 master,是 master Novatek 芯片在 firmware/USB 传输层的行为故障 —— **内嵌不会自动治好**,必须靠"规避 + 自愈"在 host 侧驯服。
- **严禁把内嵌当挂死的解药去盲赌。** 内嵌方向正确且比 OTG+自供电 hub 结构性更优,但要先用最小成本验证分水岭(见 11.5)。

内嵌**不可替代的两个正收益**:
1. **软件可控电源轨** —— 替代无 PPPS 的自供电 ganged hub,挂死可软件断电重启,不用人到场。
2. **位姿固定** —— OTG 每次插拔深度相机与主摄外参就变,根本无法一次性标定;内嵌锁同一刚性基准面 → 出厂标定一次长期有效。这是"双摄合一"的根。

## 11.2 反复挂的三个真因(外接形态实测)

| # | 现象 | 根因 |
|---|------|------|
| 1 | color MJPEG 流秒挂 master(掉枚举 `error -71`) | master USB2 单 TT,MJPEG 大流 + XU5 control 争用 EP0/TT + 供电纹波,firmware 状态机异常 |
| 2 | XU5 keepalive `set_cur` 一直 `rc=-7` TIMEOUT,~20min 饿死 master | 50ms 心跳穿 OTG→ganged hub TT→master,任一级 NAK/抖动/电流跌落使心跳丢失,触发 firmware 看门狗 |
| 3 | 供电不足 + 无法软恢复 | 手机 OTG VBUS(0.5–0.9A)喂不饱三路并发峰值(2.5–3.5A,**结构光投射器脉冲 1–2A 是杀手**);自供电 ganged hub 无 PPPS,挂死只能物理拔电源砖 |

## 11.3 推荐内嵌架构(三条链)

拓扑定型:**SoC USB-C host → 板载 USB3 hub(INDIVIDUAL 模式 + 真 PPPS)→ master(USB2 域)+ companion(USB3 SS 域);供电脱离 OTG VBUS 改板级独立电源树,三路独立 load switch + GPIO 软控;RGB 由手机主摄出,相机端 color 流彻底不启用。**

```
【电源链】 手机电池/PMIC(VSYS)→ 专用 buck(子系统峰值 3.5A ×1.5 裕量,5–6A 连续)
          ├ LS_master(EN→SoC GPIO,<50mΩ,soft-start 限 inrush,≥1A)
          ├ LS_companion(EN→GPIO,≥1.5A,较慢 soft-start 压 USB3 PHY inrush)
          └ LS_projector(带 ILIM,≥2A,本地大容量低 ESR 缓冲吸脉冲,与数字轨电气隔离)
          上电时序:master → companion → projector;掉电反序;GPIO 默认下拉(默认 OFF)

【数据链】 手机主摄 ─CameraX→ RGB ┐(沿用双流时间戳对齐契约)
          SoC USB host(DWC3)→ 板载 USB3 hub(USB5744 / TUSB8041 类,务必 INDIVIDUAL)
              ├ port1(USB2 480M)→ master:只跑控制 + XU5 心跳,★绝不开 MJPEG★
              └ port2(SS 5G)   → companion:IR-raw+phase(90Ω 差分/等长/完整参考层)→ depth 重建

【控制链】 NATIVE_REWRITE 固化为常驻系统服务(独占两芯片 EP 调度)
          ├ SCHED_FIFO 实时线程:XU5 set_cur 50ms 周期,单次 timeout 25ms,失败立即重发
          └ watchdog(三路独立判活,★用 master 心跳判活,绝不用 companion depth 帧★)
               set_cur 连 N=3 次 -71 / 节点消失 → 拉低 LS_master EN ≥100ms → 重上电 soft-start
               → 重枚举 → 重跑双 session+XU5 init+companion commit → 续流
               (可只 cycle master 不动 companion 保住 depth;或两路一起冷复位,策略软件可选)
               连续失败 M 次 → 停自动重试 + 提示用户(护硬件寿命)
```

## 11.4 三个问题逐一解法

| 问题 | 内嵌解法 | 性质 |
|------|---------|------|
| 1 color 秒挂 | RGB 改走手机主摄,master 永不发 MJPEG 的 PROBE/COMMIT/STREAM_ON —— 既消除触发源又释放 master 带宽,正是"双摄合一"本意 | **规避**(firmware 改不了);第一性最优解 |
| 2 keepalive 饿死 | 直挂 SoC 去 OTG 协商层 + 去 hub 一级 TT;剥离 color 释放 EP0;心跳走实时线程 25ms 重发;供电稳消除 NAK 风暴;仍死则 watchdog power-cycle | **大幅缓解 + 自愈兜底**(不保证根除) |
| 3 供电+软恢复 | 板级独立电源树喂饱、投射器轨隔离吸脉冲;三路 load switch,master EN→GPIO 软断电(粒度比 hub PPPS 更细,可只 cycle master 保 depth) | **干净彻底解决** ← 内嵌唯一结构性增益 |

## 11.5 分阶段路线(中间必经"带可控电源 carrier"过渡态)

**第一性:在投入任何机构/载板/标定工装成本前,先用外接形态 + 可控电源轨做分水岭验证。**

| 阶段 | 形态 | 可判定验收 |
|------|------|-----------|
| **P0 分水岭**(最高优先,纯软件+小硬件) | 现外接 + GPIO 可控断电 carrier,Linux host parity 台架 | ① 关 color/RGB 走主摄后 master 还秒挂吗?② 供电稳+心跳实时后 set_cur -71 消失、20min 饿死解除吗?③ "断电→上电→重枚举→重发 init/commit→重启 keepalive"恢复链可重入?④ 断电时长 N(起步 2s)vs 重枚举成功率曲线 |
| **P2 depth 重建**(并行,独立大工程) | 同上 | companion 的 IR-raw+phase 经结构光重建逻辑移植到 native 出真 depth(现在只有 IR 预览);156B 出厂参数 blob 接入 |
| P1 watchdog 自愈闭环 | 同 P0 carrier | 三路判活状态机;DETECT_FAIL→断电 N 秒→重枚举→续流全自动;扫描会话把"相机重连"做成第一类事件(已采集数据保留、UI 不崩);打点为 harness 可判定输出 |
| P3 半内嵌 carrier | 深度模组+主摄装同一刚性支架,板载 hub+电源树,host 仍外置/手机 | 板载 hub INDIVIDUAL 单口 power-cycle 隔离度(不误触 companion);companion 5G SI 达标;一次性外参标定可行性 |
| P4 完全内嵌焊死 | 取代 OTG,定制 FPC/载板 + 出厂标定工装 + 热管理 | 仅 P0–P3 全绿才进;NTC 过温纳入 watchdog;外参覆盖温度区间;跌落/热循环后外参漂移 ≤1%@1-2m |

## 11.6 风险登记(★ = showstopper)

1. **★ depth 重建未解 = 内嵌毫无意义**:companion 推 IR-raw+phase 不是成品 depth,USB 稳了也只有 IR 预览。**与挂死正交的独立大工程**,不解决整个硬件投入归零 → P2 必须并行。
2. **★ 供电修好后 keepalive 仍死 → 根因在 master firmware/EP0**:则可靠性目标从 MTBF 转为 MTTR(挂了多快自愈)。**连续实时扫描不可接受,多角度静态拍照(容忍 2–4s 重连)可接受 → 目标扫描模式必须先定。**
3. **★ master 单独 power-cycle 是否污染 companion**(芯片状态耦合):影响"保住 depth 流"策略,P0 实测。
4. 板载 hub PPPS 真实性(datasheet 可能只切 USB2 不切 SS)→ 必须样板实测 `SET_FEATURE(PORT_POWER)` 真能断 master VBUS;叠 GPIO load switch 硬兜底,不单赌 hub。
5. companion USB3 5G 内嵌 PCB 的 SI(目标 SoC 的 xHCI/dwc3 quirk);热(投射器温漂直接污染 depth 标定);失去物理拔电终极兜底(GPIO 链路必须独立于挂死的 USB 链路、极高可靠)。
6. 团队/商业:内嵌=重资产长周期,Berxel 不支持、P100R3 停产则沉没成本高 → P0–P3 用可拆 carrier 验证清楚、depth 重建打通后再决定是否 P4 焊死;carrier 形态本身已能交付一台可用扫描设备。

## 11.7 决断

内嵌方向正确且比当前 OTG+自供电 hub 结构性更优。**立即并行执行 P0(可控电源 carrier 分水岭验证)+ P2(depth 重建)** —— 两条都是低成本/纯软件可先行的 showstopper 验证。只有它们绿灯,才逐级走到 P4 焊死。中间的"带可控电源外接 carrier"过渡态不是可选项,是用最小成本锁死最大不确定性的必经阶段。

---

相关:[[10-android-uvc-stack-rewrite]](NATIVE_REWRITE)、[[01-depth-camera-integration]](双摄绑定/标定)、
[[../agent-memory/finding_p100r3_master_hang_recovery_2026-05-29]]、
[[../agent-memory/finding_p100r3_companion_pushes_ir_raw_2026-05-28]](depth 重建是独立工程)。
