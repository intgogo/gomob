# scan_multiview_quality — 多视角重建端到端质量评估(M3.16)

合成 **Stanford Bunny**(非平凡有机体:耳朵 / 凹陷 / 变曲率)8 角度 RGBD → `fusion_core`
端到端重建(全连接 multiway 配准 + 全局 PGO + conf 加权 TSDF + Marching Cubes)→ 量三项质量指标。

比 `scan_fusion`(box+sphere)更严:Bunny 的细节与凹陷考验配准鲁棒性与 TSDF 在曲面上的保真。

## 跑

```bash
./dev.sh harness scan_multiview_quality
# 或直接:tests/harness/scan_multiview_quality/run.sh
```

`run.sh` → `quality_bench.py`(渲染+重建+量化,写 `.dev/scan_multiview_quality/metrics.json`)
→ `analyze.py`(读 metrics 出可判定结论 + exit code)。需带 open3d 的 venv(默认 `.dev/fusion-venv`)。

## 三项指标与门

| 指标 | 含义 | 门 | 实测(clean, voxel5mm) |
|------|------|----|----|
| mesh chamfer | 重建网格 vs 传感器观测面,对称距离 | **硬** ≤ 5.0mm(>4mm 预警) | ~1.76mm(稳定,跑间差 <0.01) |
| 覆盖度 @5mm | 观测面点到最近重建点 <5mm 比例(完整度) | **硬** ≥ 88% | ~96% |
| 覆盖度 @10mm | 同上 <10mm(观测面无大空洞) | **硬** ≥ 94% | ~99.8% |
| 精度/完整度分量 | chamfer 的 fused→ref / ref→fused 两方向 | 报告 | ~1.7 / ~1.8mm |
| UV atlas 利用率 | iso-charts 展开后三角 UV 面积和 / 单位方 | **软** 报告(<60% 地板警告) | ~62–70% |

> 注:本 harness 上线时暴露并修复了 M3.14 配准在 8 视角有机体上的脆弱性 —— 旧固定 FPFH 12mm /
> 对应 30mm 对 Bunny 这类复杂体特征过粗、对应过松,宽基线视角对(近背对)误配且 fitness 假高,
> 全局优化的 line process 拦不住 → 位姿翻转(chamfer 在 3 / 7.5mm 间随机跳)。修复:配准尺度跟
> voxel 派生(`reg_voxel=min(voxel,12mm)`、对应=2.5×reg_voxel),Bunny 8 视角 **7.5→1.76mm 且稳定**,
> box+sphere(M3.14)无回归。详见 `fusion_core.FusionConfig.reg_voxel_m/reg_corr_m`。

硬门只认"扫描真实化"的本质 —— **精度(chamfer)+ 完整度(coverage)**;两者达标即 `正常`。

参考面 = 各视角 clean 深度反投影并集(传感器真正看到的 GT 面),排除纯赤道环绕看不到的底面,
使指标度量纯重建质量而非不可达覆盖惩罚(复用 `scan_fusion/synth_dataset.observed_surface`)。

## 为什么 UV 利用率是软门(而非 TODO 原定 ≥70% 硬门)

实测:在 Bunny 这类 **marching-cubes 有机网格**上,UV 利用率天花板 ~64–70%,且与打包器无关 —
- Open3D 自带 iso-charts:voxel 5/8/12mm、gutter/max_stretch 多组合,均 64–68%;
- xatlas(激进 padding=0/bruteForce):同网格仅 62–65%。

根因是几何而非工具:有机曲面经 MC 切出的 chart **数量多、边界弯**,无法密铺单位方;70%+ 利用率
通常只见于 CAD / 少而大的矩形 chart,或靠大畸变 / 合并 chart 强凑(牺牲纹理与几何真实)。
按"第一性 + 不伪装"取舍:UV 利用率改为**质量监测软报告**(< 60% 才警告,提示展开退化),
不引入 xatlas(实测它在此并不更优)。详见 commit / TODO M3.16 订正说明。

## 真实卡车数据

```bash
export GOMOB_TRUCK_DATASET=/path/to/truck_rgbd.zip   # RgbdShot bundle(server/fusion_service/rgbd_bundle.py 格式)
./dev.sh harness scan_multiview_quality
```

有则跑真实重建并报告 mesh 统计 + UV 利用率(**无 GT,故不出 chamfer/coverage**);未提供则
`analyze` 标 `— 跳过`(非失败)。真实多视角 RGBD 采集见 TODO M3.14 ②(卡物理采集,尚未就绪),
就绪后此路径直接可用,非桩。

## 资产

`assets/bunny.ply` = Open3D BunnyMesh 抽稀到 ~12k 面的离线副本(~300KB,保留全部几何特征),
随仓库走,harness 不依赖网络。
