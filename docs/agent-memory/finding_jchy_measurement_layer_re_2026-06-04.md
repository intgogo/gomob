# JCHY 车辆测量层逆向与 gomob 当前边界

## Why

JCHY_simple_3.0.0 是 LTS-T1 采集层之上的测量/建模应用：PCL 预处理/聚类+OBB，几何车型判定，局部 PointSIFT 部件分割，再输出 L/W/H、轴距/悬长、罐体、栏板、护栏和容积。`carType.ini` 的 31 行偏移已完整解密，真实会话 `Data/100742` 给出 L1777/W533/H759、总轴距1370等基线。完整证据与未坐实项在 `docs/architecture/16-jchy-vehicle-measurement-app.md`。

gomob 已不再“完全没有测量层”：laserworker 已有 PCL-free 几何 L/W/H、轴距/悬长、货箱、overlay、结果 stats 和同源 `measured.pcd`。但这只覆盖常规几何量，尚不等同于 JCHY 的全车型建模能力。

## How to apply

- 常规车继续以服务端几何管线为主，验收必须基于经过 site/region/raw A+B 背景隔离的 measured 云，而非融合房间。
- 26 型目录/carType 偏移可作数据参考；生产自动判型仍需多车型标注会话，不能用单样本或默认车型硬编码。
- 罐体三段/容积、栏板/护栏、异型车等须补真实样本与 harness；PointSIFT 原厂模型缺失时不以固定值伪装语义分割。
- 当前通用 L/W/H 上限钩子不是逐车型法规表。合规终态必须绑定车型、法规版本、适用条件和证据，完成前客户端不展示“合规结论”。
- carType 精确消费语义、PointSIFT 类别/网络、罐体容积公式和栏板参考面仍以 docs/16 §10 为准，未升级为事实。
