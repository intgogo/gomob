# 激光扫描角不能隔离深度，车辆隔离应在服务端几何域完成

## Why

LTS-T1 只有 pan 扫描角和 `lidar_filter_zone` 竖直角，没有径向距离闸门。scan24 中车辆与远办公室覆盖同一 pan/俯仰立体角，联合缩角后远背景仍保留 99.3%，所以“圈点云→反算扫描角→只扫车辆”物理上不可行。扫描角只能控制覆盖与密度，不能区分同一视线上的前后物体。

早期因此把 3D crop box 写成“唯一解”；当前已订正：crop box 是显式兼容 fallback，生产主线是服务端 region 限定工位后，对 A/B raw 空工位背景做同域相减。

## How to apply

- 不再尝试用 pan/`lidar_filter_zone` 做车辆隔离。生产顺序按 `finding_vehicle_isolation_bg_subtract_2026-06-17.md`：服务端 region → A/B 分设备背景相减 → 最终 B→A 合并。
- crop box 仍可用于无背景诊断或人工兼容，但不能压过兼容 raw background revision；写入/删除属于 admin+审计操作。
- 调扫描角只为覆盖整车与点密度：有效弧不得跨 ±180°，跨度须 <180°；判断覆盖看点云横向跨度和 h_angle 连续性，不只看总点数。
- `device-scan-settings` 必须完整回写 control；起扫前 acquisition profile 会读取 `/api/get_config`，角度、速度、过滤参数或标定变化都会使旧背景不兼容。
