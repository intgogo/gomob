# 激光工位真理源与 App/Web 同源结果

## Why

旧 3D 工位管理台把 A/B 外参只存在浏览器 localStorage，网页起扫时临时发送 `site_json`，Android 则不带这份
状态；旧 worker 又把空外参静默降为 raw。随后 job207/209/210 进一步证明，仅统一 site 仍不够：网页先按
15 点 region 裁剪，而 App 链路拿未裁整房间 live 减已裁背景，约 700 万区域外静态点被误判为车辆，尺寸从
`1768×531×763mm` 错成约 `3558×144×3107mm`。同一工位若让客户端各自持有配置、裁剪或重算，就不存在
可复现的车辆几何域。

## How to apply

- `laser_site_calibration`、`laser_region_calibration`、服务端 keep ratio 与 `device_info + get_config` acquisition
  profile 是唯一真理源；客户端旧字段只能做一致性校验，不能覆盖 canonical 配置。
- site 按物理 A/B 保存 native 米制刚体 `b_to_a`、来源、RMS、公共标记数和 hash；生产要求 RMS≤5mm、公共标记≥4。
  native 只应用 site，单次 Go 点到面 refine 只属于本任务，禁止回填 canonical site。
- 新背景是服务端 region 裁剪后的 A/B 设备系不可变 revision，并绑定 site/region、设备身份和扫描配置。live 用
  canonical site 进入同一裁剪域，A/B 各自在本设备系相减，再把 B 前景用本次最终 B→A 合并。已验证的
  `legacy_fused` 保留同工位融合云相减兼容路径，不能冒充新 A/B revision。
- `measured.pcd` 是 `MeasureFull` 的实际输入；L/W/H、轴/悬长、货箱、overlay 和 measured_points 必须与其同源。
  `MeasuredCloudArtifact` 用坐标 schema、源点数、XYZ SHA-256、site/region/background revision 和最终 B→A hash
  固化内容身份；校验失败时 fused 只能作为明确标注的场景诊断，不能冒充车辆。
- App/Web 用 WS 与 REST 恢复同一领域结果；`laser_app_web_parity` 对实际 JS/Kotlin 映射，`laser_repeatability` 只比较
  同 inspection 和同 revision 的扫描。scan209 只证明服务端外参接线已通，不证明当前标定已达量测精度。
- 新 A/B schema 的现场终验等待用户确认空工位：完成 ArUco/refine 生产质量门后再重标 site、重采区域 A/B 背景，
  并对同车同 inspection 连扫至少三次；该升级验收不反向阻断历史真扫已验证的 legacy 工位。
