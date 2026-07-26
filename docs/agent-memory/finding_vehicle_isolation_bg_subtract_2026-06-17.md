# 车辆隔离必须用区域裁剪后的 A/B 背景同域相减

## Why

真机 scan168 已证明融合云是整个房间，车通过地面、墙和杂物与环境连成一体，device ROI、自动地面或最大簇都可能量成房间。早期把空工位保存成单个 fused PCD 再做 fused-minus-fused 也不成立：它丢失 A/B 设备系、region 版本和最终 B→A 语义，App/Web 只要裁剪顺序不同就会把区域外静态点当车辆。

## How to apply

- 新采背景使用不可变 `region_cropped_unit_frames_v1`：以服务端 region 和 canonical site 先裁 A/B，再分别保存设备系 PCD、点数/checksum、site/region revision、设备 identity 与配置指纹。区域外房间点不得进入背景对象。
- 已迁移的 `legacy_fused` 保留修改前网页端路径：仅同一 A/B 工位、对象存在且点数可校验时，以本次区域裁剪后的 fused 直接相减；schema 始终标为 legacy，不伪造 A/B/site/region 元数据。真实 job176 背景重放 job197/job207，分别精确复现 1772.0×529.0×763.4mm、1768.0×531.2×763.4mm。
- 生产顺序固定为：A/B live 用同一 canonical region 裁剪 → A/B 各自在本设备系 `SubtractBackground` → 仅 B 前景用本次最终 B→A 变换 → 合并 `measCloud`。单次 refine 不得改变背景裁剪域。
- 兼容的区域 A/B 背景是生产隔离真理源，优先于旧 crop box；背景缺失或 site/region/设备/扫描设置不兼容时起扫前 409，不能完整扫描后再给错误尺寸。
- 地面从同一 background revision 在当前 region/B→A 下重建；ground drift >1.5°/50mm 禁止 measured。site refine 未应用或修正 >50mm/1°也禁止 measured。
- `tests/harness/laser_background` 的新背景入口成套提供 A/B live、已裁剪 A/B background、region、canonical 裁剪 B→A 和最终合并 B→A；历史入口必须显式声明 `BACKGROUND_SCHEMA=legacy_fused` 并提供 `LIVE_PCD/BG_PCD`。
- 既有工位不因软件升级强制重采；只有安装位姿、区域或静态场景确实变化，或主动切换到新 A/B schema 时才采新背景。新 schema 的正式升级验收按 `TODO.md` M13.12 另行执行。
