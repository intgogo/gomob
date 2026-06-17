# GOAL — LIDAR_PTZ 车辆外廓扫描系统 Linux 迁移

> 通过逆向工程,把 `/root/WindowsR/LIDAR_PTZ`(Windows 版"QtTrainScan/LIDAR_PTZ")
> **完整迁移**到 Linux,**保持 C++ 技术栈**。第一里程碑 = **无界面管线**(headless),GUI 后续再做。

## 决策(已与用户确认 2026-06-03)
- **技术栈**: 保持 C++/Qt,移植到 Linux(不改写为 Go/Python)。
- **首里程碑**: 无界面管线(设备控制→扫描采集→点云构建→标定→融合→纹理→导出 PCD/PLY)。
- **硬件**: 真机在线于 `192.168.9.101` / `192.168.9.102`(端口 4000)。
  本机 `192.168.9.160/199` 与设备同网段;设备当前未上电(tcp/4000 closed),先用样例数据离线复现,
  上电后联机验证协议与采集层。
  注意:打包内 `DeviceList.xml` 写的是 `192.168.1.100/.106`,以真机 `.9.101/.102` 为准。

## 系统是什么(逆向结论)
原始工程名 `QtTrainScan 1.2`,现名 `LIDAR_PTZ`。依赖:
**Qt 5.12.12 + CloudCompare(CCLib)+ PCL 1.8.0 + OpenCV 4.6.0 + Ceres + yaml-cpp + glog**。

**硬件**: 两台一体化扫描单元。每台 = 2D 激光线扫 + 电机旋转轴(PTZ)+ 4K 相机。
两台从车辆两侧扫描,拼出完整外廓。

**设备协议**: HTTP REST(`QHttpComm`,设备端 cJSON):
`/api/device_info` `/api/device_status` `/api/control_scan` `/api/update_control`
`/api/update_calib_parameters` `/api/config_network`;
另有原始扫描流(`QMultiPortTcp`),行格式 `#h-angle scan-seq segm-seq pt-seq x y z attr`
(或极坐标 `#h-angle scan-seq pt-seq v-angle dist attr`)。

**管线**:
1. **采集**: 旋转轴扫一个角度范围,累积激光线 → 3D 点云(`temp/*.pcd` ~1.3M 点)。
2. **标定**(`CalibrationPipeline`,Ceres `AutoDiffCostFunction`):
   - `P2PlaneFunctor`: 激光↔轴,点到平面残差,平面靶在 `config.yaml: lidar_calibration.planes`。
   - `ReprojectionFunctor`: 相机↔轴 + 内参/畸变,重投影残差,编码靶在 `tag_pos.csv`。
     9 步: Pw_world→Pw_dev_rot→Pw_rot_axis→Prx→Pr_cam→(x,y)_norm→(x,y)_dist→(u,v)→residual。
   - ICP 细化 `tag_world_positions_`。
3. **融合**(`cloud::fusion`): 合并两台点云。
4. **纹理**(`point_cloud_utils::PointCloudColorizer`): 相机帧投影到点,逐点选最优帧 → 彩色点云。
5. **合成/导出**: 体素下采样 → PCD/PLY;柱面 **全景图**导出。
6. **查看器**(里程碑 2): CloudCompare 风格 OpenGL(EDL/SSAO、拾取、包围盒工具)。

`config.yaml` 完整记录了所有标定/优化参数,是算法的权威文档。`setting.ini` 是旧版遗留。

标定输出 JSON(`calibration_results.json` / `calibration.json`)schema:
```
lidar:  { lidar_rot_quat[wxyz], lidar_corr_quat[wxyz], lidar_corr_offset[xyz] }
camera: { camera_rot_quat[wxyz], camera_corr_quat[wxyz], camera_corr_offset[xyz],
          camera_intrinsic[fx,fy,cx,cy], camera_distortion[k1,k2,p1,p2,k3] }
```

## 目标架构(Linux, C++)
```
lidar/
  src/
    device/        # QHttpComm 等价:HTTP REST 客户端 + 扫描流 TCP 采集(QMultiPortTcp)
    cloud/         # 激光线 + 轴角 → 点云;fusion;体素下采样;PCD/PLY IO
    calib/         # CalibrationPipeline:P2PlaneFunctor + ReprojectionFunctor(Ceres)
    texture/       # PointCloudColorizer:投影/选帧/上色;柱面全景
    config/        # config.yaml 解析(yaml-cpp);calibration JSON IO(cJSON/nlohmann)
    pipeline/      # 端到端编排(CLI):scan → build → calib → fuse → texture → export
    app/           # CLI 入口(里程碑1);GUI(里程碑2)
  re/              # 逆向资料:strings、spec_*.md 规格文档、协议抓包/回放
  third_party/     # 依赖说明/子模块
  sample/          # 来自 Windows 包的样例数据(config.yaml/tag_pos.csv/temp/*.pcd/cam.jpg)
  CMakeLists.txt
```

## 里程碑
- **M0 逆向规格** ✅ 完成: `re/SPEC.md` + 6 份子系统 spec;并用 Ghidra 字节级验证几何
  (`re/spec_geometry_resolved.md`,解决 R1/R2/R6/R8)。
- **M1 无界面管线** ✅ 离线完成(9 套测试 102 断言全绿,`lidar_cli` 可跑):
  - M1a 设备层 ✅:`http_client`(/api/* + mock 端到端)、`scan_stream`(CA FE/CRC-16/zstd/LDR·PTS·ENC·IMG)。
    LIVE 采集复用同模块,**上电即接**。
  - M1b 点云构建 ✅:`cloud_build.lineToWorld`(字节级一致;绝对尺度 U1 待联机)。
  - M1c 标定 ✅:`calibration_pipeline`(P2Plane+Reprojection 两 Ceres functor,合成数据反推复原)。
  - M1d 融合+纹理+导出 ✅:`fusion`/`colorizer`/`panorama`,导出 PCD/全景/calibration JSON。
  - M1e CLI 编排 ✅:`pipeline` 离线回放;**与原版逐值对齐验收待联机原始数据**。
  - **唯一待办=联机验证**:设备上电后跑 `spec_protocol §6` 探测,定 U1/R3/R4/R5。
- **M2 GUI 查看器**: CloudCompare 风格 OpenGL 查看/拾取/包围盒(后续)。

## 验收口径
- 离线: 用样例 `temp/*.pcd`、`config.yaml`、`tag_pos.csv`、`cam.jpg` 复现各阶段,数值对齐原版。
- 联机: 设备上电后,协议/采集层与真机互通,扫一次得到与 Windows 版可比的点云。
