# laser_ab_refine

验证 A/B 两镜头点云融合的高精度外参精修：

```text
BToA_final = ΔT_cloud · BToA_marker
```

`BToA_marker` 来自现场 marker 角点法；`ΔT_cloud` 来自 A/B 共同可见几何的点云 ICP。该 harness 不依赖 Open3D，只用 `numpy/scipy`。

## 默认合成闭环

```bash
./dev.sh harness laser_ab_refine
```

默认会构造三块互相垂直平面 + 非对称台阶，给 `BToA_marker` 注入约 1-2°、几十毫米误差，再验证精修能恢复到毫米级。合成闭环用于判断算法核是否可靠。

## 真实 A/B 闭环

```bash
A_PCD=.dev/xxx/unit_a.pcd \
B_PCD=.dev/xxx/unit_b.pcd \
BTOA_JSON=.dev/xxx/site.json \
EXPECTED_BTOA_JSON=.dev/xxx/expected_final.json \
./dev.sh harness laser_ab_refine
```

真实输入由生产 Go `RefineBToA` 验证；Python 原型只跑合成靶，避免用全场景非重叠点的最近邻统计误判。真实输入必须满足：

- `A_PCD` 是 unit A 原始点云。
- `B_PCD` 是 unit B 原始点云。
- `BTOA_JSON` / `SITE_JSON` 是行优先 4x4 `{"b_to_a":[...]}`，平移单位为 mm。
- 设置真实输入后，harness 会同时运行生产 Go `RefineBToA`；`EXPECTED_BTOA_JSON` 可选，用于要求终态落在 5mm/0.2° 内。
- A/B 点云里有足够多共同可见、非共面、刚性的几何面。

不要用 Python `refine.py` 的全场景最近邻统计判断生产结果。整场景通常包含大量只被单侧看到的墙、地面、背景和车体侧面；生产 Go 算法使用点到面残差与法向相容性拒绝对立面，并带 150mm/5° 守卫。需要单独评估标定靶时，再裁出 A/B 共同可见的固定非共面区域运行 Python 原型。

只有显式设置 `LASER_AB_REFINE_PYTHON_REAL=1` 时，`analyze.py` 才会对真实 PCD 跑 Python 原型。
