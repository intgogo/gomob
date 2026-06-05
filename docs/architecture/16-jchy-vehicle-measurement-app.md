# 16 — JCHY 车辆外廓测量/建模应用 逆向（JCHY_simple_3.0.0）

> 逆向真理源：原厂安装 `/root/WindowsR/JCHY_OFFLINE/`（含 `JCHY.pdb` 完整符号、`JCHY_new.exe`/`JCHY_ONLINE.exe`、`AlgFuncDLL.dll`、一次真实扫描会话 `Data/100742/`）；
> 抽取文本产物 `/root/lilw/gomob/.worktrees/laser-scan/.dev/jchy-re/`（每个二进制 `*.ascii.txt`/`*.utf.txt`/`*.cjk.txt`）。
> 文档约定：每条结论标 **[直证]**（符号/源文件/UI 串/配置值/数据文件事实）或 **[推断]**（符号关联但无反汇编/公式直证）。未坐实/待验证项统一收在第 10 节，不混入正文。
> 原厂源码树：`D:\software\jchy_simple_3.0.0.2\jchy\`，核心算法在 `arithmetic/`。

---

## 0. 摘要 + 它在 gomob 全链中的位置

**JCHY 是车辆外廓激光扫描的「测量/建模层」应用**：Windows x64，Qt5 + PCL 1.8 + CUDA + TensorFlow(PointSIFT) + OpenCV + OpenNI2 + 海康 HCNetSDK。
它消费两台 LTS-T1 一体化激光单元（2D 线激光 + 旋转 PTZ + 海康相机）扫出的**彩色点云**，对车辆按车型建模并量出外廓尺寸（车长宽高、轴距、罐体、栏板、护栏等），对标国标 GB7258-2017 §4.15，产品自名「重点车辆智能核验仪」[直证 `JCHY_new.exe.cjk.txt:5694`、`:15138`]。

**在 gomob 全链中的位置（承上启下）：**

```
采集层(已逆向)              测量/建模层(本次逆向 = 本应用)         gomob 现状
/root/lilw/lidar           JCHY_simple_3.0.0                      docs/architecture/15
QtTrainScan                ──消费 PCD/全景/parameters───►          采集→融合点云(瘦客户端)
CA-FE 帧→融合点云           PCL分割+PointSIFT+按车型测量            ❌ 尚无测量/建模层
(docs/16 上游)              (docs/16 本层)                         (本文 §9 给出补齐路线)
```

- **上游（采集层）**：gomob 已在 `docs/architecture/15-laser-scanner-integration.md` 把 `/root/lilw/lidar` 的 CA-FE 帧采集 + 两单元融合点云迁进服务端瘦客户端方案。
- **本层（本次逆向）**：JCHY 把融合后的彩色点云做成「车辆 → 尺寸数字」的测量结果。gomob 当前**只到融合点云，没有这一层**。
- **下游（gomob 缺口）**：要做到与 JCHY 测量对等，gomob 需新增测量/建模管线、PointSIFT 部件分割、车型参数表、各测量算法（见 §9）。

---

## 1. 应用形态与技术栈

| 维度 | 事实 | 证据 |
|---|---|---|
| 形态 | Qt5 桌面应用，主流程类 `JCHY`（QMainWindow），算法在 `arithmetic/` | [直证] PDB `JCHY::onType/onTrailer/onBtnCarType`；源路径 `d:\...\jchy\arithmetic\locater.cpp` |
| 离线版 | `JCHY_new.exe`（2.13MB）= 纯回放 `Data/` 会话；入口 `QProject/QLaserSocket/QPtzDevice::OfflineReading` | [直证] PDB `?OfflineReading@QProject/@QLaserSocket/@QPtzDevice` |
| 在线版 | `JCHY_ONLINE.exe`（2.13MB）= 实时连激光起扫；独占 `/api/control_scan` + `SCAN_START` | [直证] `JCHY_ONLINE.exe.utf` 含 `/api/control_scan`、`SCAN_START`、`application/json`（`JCHY_new.exe` 无） |
| **「在线」≠ 云上传** | 业务 exe 行级 grep `upload/token/oss/QSslSocket/QHttpMultiPart/https://` 全 0；PDB 命中均为库子串（PCL Signature / stb token / Qt SSL 枚举） | [直证] 两 exe grep；`cloud`(8208 次) 全是 `PointCloud/setInputCloud` |
| 点云库 | PCL 1.8.0 经典算子（无 ICP/TSDF/表面重建）；本质=聚类+OBB 测距 | [直证] `pcl_segmentation/surface/filters/io/kdtree/search/common_release.dll` 全在 |
| 深度学习 | TensorFlow 1.4 + PointSIFT（PointNet++ 自定义 GPU 算子），仅做局部部件分割 | [直证] `tensorflow.dll`(201MB)、`tf_user_ops_pointSIFT.dll`、`cudart64_80/cudnn64_5/cublas/cufft/curand/cusolver` 全在 |
| 图像 | OpenCV `opencv_world331.dll`（投影、minAreaRect、Stitcher 全景、形态学） | [直证] DLL 在；PDB `cv::RotatedRect`(376)/`minAreaRect`(20)/`cv::Stitcher` |
| 相机/深度库 | `OpenNI2.dll`（历史深度接口）、海康 `HCNetSDK.dll`（安防监控） | [直证] 两 DLL 在；PDB `NET_DVR_Init/Login_V30/RealPlay_V30/CaptureJPEGPicture` |
| 网络 | `Qt5Network`、`zstd.dll`(643KB) 解压扫描流 | [直证] PDB `ZSTD_decompress/getFrameContentSize/isError` |
| 持久化 | SQLite（`CarSQL.db` 表 `Measure`）+ INI（`Result.ini`/`setting.ini`/`CalibSetting.ini`/`carType.ini`） | [直证] sqlite3 dump + cat |

---

## 2. 模块 / 类结构树

源码树（`d:\software\jchy_simple_3.0.0.2\jchy\`，PDB 源文件名直证）：

```
jchy/
├─ arithmetic/                      ← 算法核心
│  ├─ cloudfilter.cpp/.h            CCloudFilter：多帧汇聚 pushFrame/clearPoints
│  ├─ lidarcoloring.cpp/.h          point_cloud_utils::PointCloudColorizer：点云上色/纹理投影
│  ├─ locater.cpp/.h                CLocater：测量/分割引擎（主力，方法名即领域算法）
│  ├─ locater3dcnn.cpp/.h           CLocater3Dcnn：PointSIFT 3D-CNN 部件分割封装
│  ├─ pointsift_api.h               class PointSifter 声明（tf_user_ops_pointSIFT.dll）
│  ├─ processbaffle.cpp             PointCloudBoard：栏板/护栏处理
│  ├─ tankprocess.cpp              TankProcess：罐体处理
│  └─ imgprocfunc / imgprocfunc.h   (走 ImageProFunc.dll: AlgImgFunc)
├─ ccprocess.cpp                    主测量编排（无独立类，逻辑内联进 QProject 槽）
├─ cmanualmeadlg.cpp                CManualMeaDlg：手动测量对话框
├─ appinit / algfunc.h
├─ cameraview/  ccamerawnd.cpp      CCameraWnd：海康相机窗口（hcnetsdk.h）
├─ fileopera/                       qconfigreader/qdatawrite/qglobalvar 配置持久化
├─ drawing/                         Qt 绘图控件
├─ qtypeseldlg                      QTypeSelDlg：车型选择对话框（IsOk/GetType）
├─ ccalibentrydlg                   CCalibEntryDlg：基准录入/校准
├─ qimagesign                       QImageSign：全景图上手动测距标注（非"图像签名"）
├─ qappenddlg
└─ 设备/网络（与采集层 /root/lilw/lidar 同源，源目录 lasersocket_t/）
   ├─ qhttpcomm.cpp                 QHttpComm：HTTP REST 控制面
   ├─ qmultiporttcp.cpp             QMultiPortTcp：原始 TCP 多端口数据面 + zstd 解帧
   ├─ qptzdevice.cpp                QPtzDevice：聚合五路回调 + 设备控制
   ├─ qlasersocket.cpp              QLaserSocket：底层电机/扫描命令
   ├─ qproject.cpp                  QProject：会话编排 + 本地持久化
   ├─ qlasermonitor / qmonitorview
   └─ qscarletopenglinterface       OpenGL 点云/全景显示

外置 DLL：AlgFuncDLL.dll(class findObjectD, PointSIFT) / ImageProFunc.dll(class AlgImgFunc) /
          tf_user_ops_pointSIFT.dll(PointNet++ GPU 算子)
```

PDB 类名命中次数（规模佐证，[直证]）：`QLaserSocket`=815、`QProject`=662、`QPtzDevice`=508、`QHttpComm`=331、`QMultiPortTcp`=309、`CLocater3Dcnn`=171、`PointCloudBoard`=460、`TankProcess`=119。

---

## 3. 端到端算法管线（点云 → 测量）

主编排无独立 `CCProcess` 类（PDB `CCProcess::`/`CcProcess::` 方法 0 命中），逻辑内联进 `QProject` 槽函数 [直证]。
运行期分阶段计时 `runtime1.txt` 实测 8 阶段（顺序直证）：`filtertime → typetime → cuttime → calutetime → flagtime → heighttime → wheeltime → imagetime` [直证 `runtime1.txt`]。

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ① 采集/回放                                                                     │
│   在线: QPtzDevice/QLaserSocket 连 .101/.102 → QMultiPortTcp 收 CA-FE/zstd 流   │
│   离线: QProject::OfflineReading 回放 Data/<sess>/                              │
│      产物: points1.txt/points2.txt(各~130MB raw 点, 5 字段 X Y Z color5 idx)    │
│           + 30 帧 PTZ 纹理 image1/ image2/ (img_NNNN_<角度>.jpeg, 0→88.8°)      │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  CLocater::CloudFilter(vector<vector<PointCloud3D>>,float,float,int)
┌──────────────────────────────────────────────────────────────────────────────┐
│ ② 预处理 (CCloudFilter + CLocater::filterCloud)  [filtertime]                   │
│   PassThrough<XYZ>(setting.ini [Param] xmin..zmax 体裁剪)                        │
│   → StatisticalOutlierRemoval<XYZ>(离群剔除)                                     │
│   → VoxelGrid<XYZ>/<XYZRGB>(降采样)                                              │
│   → EuclideanClusterExtraction<XYZ>(车体主簇)                                    │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  point_cloud_utils::PointCloudColorizer::colorizePointCloud
┌──────────────────────────────────────────────────────────────────────────────┐
│ ③ 上色 (lidarcoloring.cpp)  [whole 计时中约 53%-76%]                            │
│   loadJsonParams(parameters_N.json fx fy cx cy + 畸变 + 四元数外参)              │
│   loadImages(30 PTZ 帧) → filterImagesByAngle(按方位角选纹理帧)                  │
│   buildFixedTransform(针孔投影 lidar→camera) → isPixelInSafeArea(裁边)           │
│   colorizeCoreMultiImage: PointCloud<XYZ> → PointCloud<XYZRGB>                  │
│      产物: 1.pcd(181497 点)/2.pcd(202036 点) XYZRGB binary, mm; *_panorama.jpg  │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  [typetime] (typetime 在 cuttime 前)
┌──────────────────────────────────────────────────────────────────────────────┐
│ ④ 车型判定 (几何启发式, 非 DL)                                                   │
│   getVehicleType(XYZ)→int / getTrailerType / getTrailerTankType / BoxOrCangShan│
│   setCarType(n) → 叠加 carType.ini 的 Type<n>_x/_y/_z 偏移 (mm)                  │
│   罐车 Type5/7 另经 CompareCloudType5/7 与标准点云模板二次确认                    │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  (setting.ini [TEMP] deepmode=1 时启用 DL)
┌──────────────────────────────────────────────────────────────────────────────┐
│ ⑤ 深度学习部件分割 (locater3dcnn.cpp, 仅难分割目标)                              │
│   CLocater3Dcnn::generate_tensor(cloud, 6 floats) → float* 张量                 │
│   AlgFuncDLL findObjectD::predict(shared_ptr<PC<XYZ>> in, shared_ptr<PC<XYZL>>  │
│      out, int, int) → 逐点语义 Label (PointXYZL = 部件标签, 非整车编号!)         │
│   底层 PointSifter::Load(ckpt)/Predict → tf_user_ops_pointSIFT.dll(GPU)         │
│   模型: wheel_seg / oilTank_seg / cementTank_seg / heavyTruck_seg .ckpt          │
│   decodeCloud: PointXYZL 按 label 拆成 vector<PC<XYZRGB>>                        │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  [cuttime → calutetime → flagtime → heighttime → wheeltime]
┌──────────────────────────────────────────────────────────────────────────────┐
│ ⑥ 尺寸提取 (CLocater)                                                           │
│   Cloud2Image: PointCloud<XYZ> 投影成 cv::Mat                                    │
│   bound_box(cloud, cv::RotatedRect&, 6 int): minAreaRect → 车长(长边)/宽(短边)  │
│   boundingBox_h + getMinMax3D<XYZ>: 车高(Z 包围盒)                               │
│   caluteDeepWheel/caluteDeepWholeWheel + segWheelBottom + SortWheelYMin2fMax:   │
│      轴距(相邻轮心 Y 间距) + 前后悬(maxLengthPt0_l/maxLengthPt1_r)               │
│   TankProcess::caluteTank/caluteOilTank: 罐体三段长+双直径+倾斜角+容积           │
│   PointCloudBoard::getCarBoardDeep/SideDeep/InnterSize: 栏板深度/货箱内尺寸     │
│   ChangeGroundColor/tree_upground → 四向护栏离地高; getSymmetry → 对称度         │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 ▼  [imagetime] QProject::set*Value 汇总
┌──────────────────────────────────────────────────────────────────────────────┐
│ ⑦ 输出 QProject::sendResult                                                     │
│   → Result.ini(全测量项, ~40 字段) + CarSQL.db Measure 表(精简 14 列) + 顶/侧图  │
└──────────────────────────────────────────────────────────────────────────────┘
```

每步源/算子映射（[直证]）：

| 步 | 类/源文件 | PCL/CV 算子 / 关键方法 |
|---|---|---|
| ② | `CCloudFilter`(cloudfilter.cpp) + `CLocater::filterCloud`(两重载) | `PassThrough<PointXYZ>` / `StatisticalOutlierRemoval<PointXYZ>` / `VoxelGrid<PointXYZ>&<PointXYZRGB>` / `EuclideanClusterExtraction<PointXYZ>` |
| ③ | `point_cloud_utils::PointCloudColorizer`(lidarcoloring.cpp) | `loadJsonParams/loadImages/buildFixedTransform/filterImagesByAngle/continuizeAngle/isPixelInSafeArea/colorizeCoreMultiImage/colorizePointCloud`；`cv::UndistortTypes` |
| ④ | `CLocater`(locater.cpp) | `getVehicleType/getTrailerType/getTrailerTankType/BoxOrCangShan`(均返回 int)；`CompareCloudType5/7` |
| ⑤ | `CLocater3Dcnn`(locater3dcnn.cpp) + `findObjectD`(AlgFuncDLL) + `PointSifter`(tf_user_ops_pointSIFT.dll) | `generate_tensor/decodeCloud/Predict`；`predict(PC<XYZ>,PC<XYZL>,int,int)`；`VoxelGridding/ChooseCPoints/SelectCriteria/PointSifter::Load/Predict` |
| ⑥ | `CLocater` + `TankProcess`(tankprocess.cpp) + `PointCloudBoard`(processbaffle.cpp) + `AlgImgFunc`(ImageProFunc.dll) | `bound_box(cv::RotatedRect)` / `getMinMax3D` / `caluteDeepWheel`(190 次) / `caluteTank/caluteOilTank` / `cvHilditchThin/getLineSlopeABC/frontCantailPos` / `getCarBoardDeep` / `getSymmetry` / `AlgImgFunc::fillHole/findLines/RemoveSmallRegion` |
| ⑦ | `QProject::sendResult` | `setValue/setWheelBaseValue/setTankValue/setTankEachValue/setQianYinTouValue/setFrontOverAndRearOver/setBoardValue` |

---

## 4. 车型分类体系全表 + carType 参数表解密

### 4.1 车型枚举（26 项，编号即语义分组）

[直证 `JCHY_new.exe.cjk.txt:18430-18451` combobox + `:15141-15169` #车型说明# 块]

**货车 `#货车类型#`（0-15）：**

| 编号 | 名称 | 编号 | 名称 |
|---|---|---|---|
| 0 | 牵引头 | 8 | 大型平板货车 |
| 1 | 吊车 | 9 | 特殊吊车 |
| 2 | 常规 | 10 | 特殊栏板吊车 |
| 3 | 路边清障车 | 11 | 专项特殊车 |
| 4 | 垃圾清理车 | 12 | 箱式尾板车 |
| 5 | 洒水罐车 | 13 | 自卸式货车 |
| 6 | 小型平板货车 | 14 | 仓栅式货车 |
| 7 | 水泥搅拌车 | 15 | 箱式货车（说明块写"厢式货车"） |

**挂车 `#挂车类型#`（50-59）：**

| 编号 | 名称 | 编号 | 名称 |
|---|---|---|---|
| 50 | 常规挂车 | 55 | 异型挂车 |
| 51 | 光板挂车 | 56 | 箱式挂车 |
| 52 | 光板挂车（带杆） | 57 | 仓栅式挂车 |
| 53 | 常规罐体挂车 | 58 | 下灰式罐体挂车（说明块写"下灰式罐挂"） |
| 54 | 低平板挂车 | 59 | 水泥罐体挂车 |

子分组：**吊车型** = 1/9/10；**罐体型** = 货车 5/7 + 挂车 53/58/59。中间编号 16-49、60+ 未占用。
判型函数：`getVehicleType`(34)/`getTrailerType`(193)/`getTrailerTankType`(12)/`BoxOrCangShan`(28) [直证 PDB]。

### 4.2 carType 混淆破解（XOR 单层回文 key）

`carType.ini` 与 `carType.bin` 均 1102B，均混淆。破解结论（python 实算复现，[直证]）：

- **真明文 = `carType.ini` XOR Kdec**，Kdec = `00200000070030101030000700002000`（周期 16 回文）→ 整 1102B 100% 可打印 ASCII。
- `carType.bin` XOR `carType.ini` 的差 = `fe11546a213d1c0b0b1c3d216a5411fe`（亦回文，但这是**两份密文之差**，不是解密 key）。
- `carType.bin` XOR (Kdec ^ 上述差) 得同一明文（双向验证一致）。
- 整块严格周期-16、0 字节偏离，**无第二层混淆**。

混淆实现 [直证 PDB]：`RecodeString::SimpleXor`(签名 `...HH@Z` 末两 int 含 keySize)、`SimpleXorIni`、`SimpleXor2Ini`、`Bin2IniData`、`Bin2IniFile`、`Ini2BinData`、`keySize`。
exe 引用 [直证]：`JCHY_new.exe.utf:40061 'carType.bin'`、`:40089-40114 'Type0_x=%1,Type0_y=%2,Type0_z=%3'` 格式串。

### 4.3 carType 还原全表（31 行，单位 mm）

[直证 `carType_decoded.txt`，本文档已 python 复算逐字节一致]

| Type | _x,_y,_z | Type | _x,_y,_z | Type | _x,_y,_z |
|---|---|---|---|---|---|
| 0 | -20,-35,0 | 8 | 40,-45,0 | 51 | 90,-100,10 |
| 1 | -60,-35,0 | 9 | -40,-15,0 | 52 | -50,-20,0 |
| 2 | -20,-35,-10 | 10 | -40,-25,0 | 53 | -20,-10,0 |
| 3 | 20,-45,0 | 11 | -40,-40,0 | 54 | 30,0,0 |
| 4 | -40,-10,0 | 12 | -30,-35,0 | 55 | 70,0,0 |
| 5 | -20,-45,0 | 50 | -50,-20,0 | 58 | -50,-10,0 |
| Tank5 | -40,-60,0 | 13/14/15 | 0,0,0 | 59 | 50,-10,0 |
| 6 | -50,-45,0 | 56/57 | 0,0,0 | Type2_s | -20,30,0 |
| 7 | 20,5,0 | board | 0,0,0 | Tank15 | 0,0,0 |
| Tank7 | -20,-20,0 | | | | |

**还原脚本要点**（Python）：

```python
ini = open('carType.ini','rb').read()                     # 1102B 密文
key = bytes.fromhex('00200000070030101030000700002000')   # 16B 回文 Kdec
plain = bytes(ini[i] ^ key[i % 16] for i in range(len(ini)))
# plain.decode() → "Type0_x=-20,Type0_y=-35,Type0_z=0\r\n..." 100% 可读
```

**语义**：每车型三轴偏移（mm），与 `CalibSetting.ini` 的 `xOffset/yOffset/zOffset`、`setting.ini` 的 `xoffset_q` 同量纲（mm 级三轴偏移），由 `CLocater::setstandardValue/setCarType` 在判型后叠加到点云裁切/测量基准上。精确消费路径属 **[推断]**（见 §10）。

---

## 5. 测量量字典

会话 `Data/100742/Result.ini` 实测值（carType=2 常规货车）逐字段直证。UI 格式串行号见 §3 引用。
入库列 = `CarSQL.db` 表 `Measure`（14 列：date/plate/length/width/height/wheelbase/xOffset/yOffset/zOffset/wOffset/angle/Topimg/Sideimg/path）；其余只写 `Result.ini`。

| 测量量 | UI 串（cjk 行号 / 格式串） | 几何方法 | 计算函数 | Result.ini 字段（实测值） | 入 Measure 表 |
|---|---|---|---|---|---|
| 车长 | `车长:`(18455) | 俯视投影 minAreaRect 长边 | `bound_box`/`GetMinRotateRect` | `Length=1777` | ✅ length |
| 车宽 | `车宽:`(18456) | minAreaRect 短边 | 同上 | `Width=533` | ✅ width |
| 车高 | `车高:`(18457) | Z 包围盒 | `boundingBox_h`/`getMinMax3D` | `Height=759` | ✅ height |
| 第二组尺寸 | — | 按 laser 分块出第二组 | `setValue`(Length2) | `Length2=1775 Width2=534 Height2=761` | ❌ |
| 轴距 1-4 | `轴距1`(18467)/`距1:-距4:` | 相邻轮心 Y 间距 | `caluteDeepWheel`/`segWheelBottom`/`SortWheelYMin2fMax`/`setWheelBaseValue` | `Wheelbase1=710 Wheelbase2=399 Wheelbase3=261 Wheelbase4=-1`(未测=-1) | ❌(细项) |
| 总轴距 | `总轴距`(18464)/`总轴距:`(15048) | 各相邻轴距之和(710+399+261=1370) | `setWheelBaseValue` | `Wheelbase=1370` | ✅ wheelbase |
| 轮数 | — | 轮检测计数 | `DetectWheels` | `WheelCounts=2` | ❌ |
| 前悬/后悬 | — | 首尾轮到车端伸出量 | `setFrontOverAndRearOver`/`maxLengthPt0_l`/`maxLengthPt1_r` | `FrontOverhang=261 RearOverhang=163` | ❌ |
| 栏板深度 | `栏板深度:`(15045) | 栏板内侧到底面 | `getCarBoardDeep`/`getCarBoardSideDeep`(90)/`PointCloudBoard::detectBaffle` | （本车 0，无栏板） | ❌ |
| 护栏离地高度(四向) | `护栏离地高度:`(15046) | 护栏顶到地面基准(`ChangeGroundColor`/`tree_upground`) | `processbaffle` | `Front/Tail/Left/RightGuardrailHeight=0`(本车无) | ❌ |
| 挂长/宽/高 | `挂长:挂宽:挂高:`(15098-15100) | 挂车段俯视外接矩形+Z 包围盒 | `LocateTraile`/`getTrailerType` | `Length2/Width2/Height2`(第二段复用) | ❌ |
| 牵引头 LWH+轴距 | — | 车头段单独测 | `findHeadStockY`/`setQianYinTouValue`；`setting.ini xoffset_q` ROI | `QianYinTouLength/Width/Height/Zhouju=0` | ❌ |
| 罐长/宽/高 | `罐长:罐宽:罐高:`(18459-18461) | 罐体段分割+投影 | `TankProcess::caluteTank`/`seg_block_tank` | `TankLength/Width/Height=0` | ❌ |
| 罐体前/中/后(段长+双直径) | `前:长-%1 直径1-%2 直径2-%3`(15103) / `中:%4-%6`(15104) / `后:%7-%9`(15105) | 三段切分+投影轮廓拟合直径 | `frontCantailPos`/`getLineSlopeABC`/`cvHilditchThin`/`setTankEachValue` | `Front=0X0X0 Middle=0X0X0 tail=0X0X0`(格式=长X直径1X直径2) | ❌ |
| 罐体倾斜角 | `倾斜角度: %10`(15106) | 长轴相对坐标轴夹角 | `theta_tank`/`CaluteAngle`/`continuizeAngle` | `Angle=0.0` | ✅ angle |
| 大直径 | `大直径:`(15101) | 罐截面最大直径 | `caluteTank` | (并入三段) | ❌ |
| 货箱 LWH(外/内) | `货箱长-%4 货箱宽-%5 货箱高-%6`(15108) / `罐体/货箱`(18458 二选一) | 货箱段分割 | `calutePickingBox`/`getCarBoardInnterSize`/`BoxOrCangShan` | `hxInnerLen/Wid/Hei=0` | ❌ |
| 容积/几何容积 | `容积:`(18462)/`几何容积:`(15102) | 罐体分段几何积分（含填充系数 `vol_s=0.96`） | `caluteTank`(`tankVol/cloud_vol/volImage`) | `TankVol=0` | ❌ |
| 屏蔽车高/异型 | `屏蔽车高`(18479) | ROI 遮罩开关（建模辅助，非纯测量） | `setCarBlock`/`filter_box` | `HXHeight/HXLength/HXWidth/HXFullHeight=0`；`XLength1/XLength2=0`(异型两段) | ❌ |
| 对称度 | — | 两侧/两 laser 一致性 | `getSymmetry` | `Symmetry=1` | ❌ |
| 车型 | `罐车型号:`(18471)/`建模类型`(18452) | 判型回填 | `setCarType` | `carType=2` | ❌ |

单位：线性量 mm（格式串 `%1(mm)`，可由 `setting.ini [Param] unit=0` 配置）；尺寸三元组另有 `%1X%2X%3` 紧凑格式 [直证]。
`carInfo.txt` 两行 `1777 530 764`(LWH 基准) / `710 400 260`(三段轴距基准)，供基准录入/校准自检 [直证]；与 Result 有 530vs533 等 mm 级差。

---

## 6. 数据与标定

### 6.1 会话目录布局 `Data/100742/`

| 产物 | 事实 | 证据 |
|---|---|---|
| `1.pcd`/`2.pcd` | 两台 LTS-T1 各自彩色点云，PCD v0.7 binary，`FIELDS x y z rgb`，16B/点，mm；181497/202036 点 | [直证] pcd 头 + struct 解点体 X[311,923] Y[55,1848] Z[10,760]mm |
| `points1.txt`/`points2.txt` | 上色前 raw 点（各~130MB），每行 5 字段 `X Y Z color5 idx`，含海量全 0 行 | [直证] ls/wc；非零样本 `-60 3073 688 55255 -129` |
| `image1/`+`image2/` | 各 30 帧 3840×2160 PTZ 纹理 `img_NNNN_<角度>.jpeg`，角度 0.033°→88.795°，步距~3° | [直证] ls：`img_0000_0.0331`→`img_0029_88.7950` |
| `1_panorama.jpg`/`2_panorama.jpg` | OpenCV Stitcher 全景（11044×3301 / 2105×3330） | [直证] PIL；PDB `cv::Stitcher`/`onBtnPanorama` |
| `parameters_1/2.json` | 相机内外参 + lidar 外参 | [直证] cat（下表） |
| `Result.ini` | 该会话全测量结果（§5） | [直证] cat |

### 6.2 parameters_1.json 内外参与坐标变换链 [直证 cat]

```
camera_intrinsic   = [fx 2092.71, fy 2091.80, cx 1887.24, cy 1097.84]   ← 对应 3840×2160
camera_distortion  = [k1 0.0646, k2 -0.0924, p1 -0.00165, p2 -0.00127, k3 0.0234]
camera_rot_quat    = [0.5, 0.5, -0.5, -0.5]   ← 展开为纯 0/±1 轴系置换矩阵(90° 重排,非误差)
camera_corr_quat   = [0.99983, -0.01625, -0.00824, 7.5e-5]   ← 标定微调旋转(约 2.09°)
camera_corr_offset = [-17.06, 45.04, 57.07] mm                ← 平移微调(原 JSON 单位 m)
lidar_rot_quat     = [0.5, 0.5, 0.5, 0.5]                     ← 另一组纯置换(非 camera 的转置)
lidar_corr_quat    = [0.99983, 0.0039, 0.0181, 0.0015]        ← 约 2.13°
lidar_corr_offset  = [6.8, 15.2, 43.4] mm
```

变换链（上色用）：点云 lidar 系 → `rot_quat`(90° 轴系重排) + `corr_quat/corr_offset`(标定微调) → camera 系 → 针孔投影(intrinsic + distortion) → 取像素 RGB。
左右乘顺序、corr 加在哪个系属 **[推断]**（无逐指令反汇编）。

### 6.3 CalibSetting.ini ROI（小车/大车两套）+ setting.ini ROI [直证 cat]

```
CalibSetting.ini  [S_CAR] xOffset_s=0 yOffset_s=20 zOffset_s=-30 left_s=3 right_s=15
                          front_s=15 tail_s=10 limt_s=2000 limt_y_s=5 limt_z_s=600
                  [L_CAR] xOffset_l=15 yOffset_l=15 zOffset_l=-30 left_l=5 right_l=10
                          front_l=10 tail_l=20 limt_z=1600 limt_z_l=15   (字段不对称,无 limt_l)
setting.ini [Param](两个重复段,值不同) 段1 xmax=1000 zmax=800 / 段2 xmax=900 zmax=850
                    共有 xmin=270 ymin=0 ymax=2200 zmin=10 xoffset_q=-50 zoffset_q=-40
            [LIMT]  carlimt=1 carlength=12000 carwidth=2550 carheight=4000 carBord=1500
                    loff=-30 woff=0 hoff=-20 boff=-30 vol=10.0 vol_s=0.96   ← GB7258 合规硬限值
            [CALIB] length=11000 width=2520    ← 标定标准件
            [ANGLE] angle1=angle2=angle3=90    ← PTZ 扫描角度
            [TEMP]  carmode=0 deepmode=0        ← deepmode=DL 开关(0=关)
```

1.pcd 实测 X[311,923]⊂[270,1000]、Z[10,760]⊂[10,800]，证 `[Param]`=有效体裁剪框 [直证]。

### 6.4 CarSQL.db schema [直证 sqlite3 dump]

```sql
CREATE TABLE Measure(date varchar(15), plate varchar(15), length int, width int,
  height int, wheelbase int, xOffset int, yOffset int, zOffset int, wOffset int,
  angle double, Topimg BLOB, Sideimg BLOB, path varchar(105))   -- 本样本 0 行
```

只落精简集（总尺寸 + 单一总轴距 + 标定 offset + 角度 + 顶/侧图 BLOB + 会话路径）；轴距 1-4、罐体三段、容积、护栏、前后悬、货箱内尺寸只进 `Result.ini`。

---

## 7. 设备与网络

### 7.1 设备类（与采集层同源）[直证 PDB 源文件名 `lasersocket_t\*.cpp`]

| 类 | 角色 | 关键方法 |
|---|---|---|
| `QHttpComm` | HTTP REST 控制面（`http://%1:%2%3` + `application/json`） | `getDeviceInfo/getDeviceState/setControl/setControlScan/setParameters/scanMode/queryDeviceState`；命令枚举 `type_PROTOCOL_COMMAND_T`：NONE/READY/START/STOP/WATCH_T/ALIGN_T/RISE_T/DROP_T/ERROR/CONFIG/MON/WRITE/DATA |
| `QMultiPortTcp` | 原始 TCP 多端口数据面 + zstd 解帧 | `findHead/getPackageSize/isDecompress/parse{Lidar,Camera,Encoder,PointCloud}/tcpRegister*Callback`；`ZSTD_decompress` |
| `QPtzDevice` | 聚合五路回调 + 设备控制 | `ptzConnect/ptzScanMode/ptzRegister{Lidar,PointCloud,Camera,Encoder,State}Callback`；结构体 `PRawImg/PLdrPts/PRawEnc/PRawLdrSeg` |
| `QLaserSocket` | 底层电机/扫描命令 | `SetScanAngle/SetScanSpeed/SetMotorGears/SetEncoderResolution/SetBackToZero/SetMonitorAngle/SendFactoryReset/SendSelfCheck/ToLaserXYZ/Start/Stop` |
| `QProject` | 会话编排 + 本地持久化 | `ConnectLaser/CreateNewFile/WriteLaser/WritePoints/PushPoint/setCarType/sendResult/OfflineReading` |

**REST 端点**：实际只有 `/api/control_scan`（在线独占）、`/api/device_info`、`/api/device_status` [直证]。`setControl/setParameters` 的 URL 后缀未在串中取到（见 §10）。

### 7.2 双单元配置 [直证 setting.ini]

`[laser_T] laser_count=2`：laser1 = `192.168.31.101`（http=tcp 同 IP）、laser2 = `192.168.31.102`、laser3 = http`.114`/tcp`.124`（备用/挂车多激光）。
`[Laser1_Calib]..[Laser5_Calib]` 各 12 值 = 3×4 外参（旋转 3×3 + 平移 r03/r13/r23 mm，如 `laser1_r03=1740.02`）。
另有 `[Laser_U]`(type=4, count=0) 段，说明同时支持 U/T 两族设备。

### 7.3 海康相机 = 安防监控（不参与上色）[直证]

`CCameraWnd`(ccamerawnd.cpp + hcnetsdk.h)：`InitCamera/OpenCamera/StartPlay/CameraCapture`，经 `HCNetSDK`(`NET_DVR_Init/Login_V30/RealPlay_V30/CaptureJPEGPicture`)。
`[CAMERA] ip1=192.168.100.104 port1=8000`、`ip2=.105`，抓拍存 `[Result] monpath=D:\message`，写入 `Measure.plate`。UI：`打开监控/监控角度/已进入监控区域`。
点云上色的纹理来自 PTZ 旋转帧（§6.1），**与海康监控无关**。

### 7.4 与采集层 LTS-T1（/root/lilw/lidar）的协议关系

JCHY 内嵌**与 `/root/lilw/lidar`（QtTrainScan）同套采集代码**（同名类 `QHttpComm/QMultiPortTcp/QLaserSocket/QPtzDevice`）[直证 同名 + 同源文件名]。JCHY = 采集层 + 其上的测量/建模层。
端口 `4000(REST)/4010(PTS)/4002(LDR)/4001(ENC)/4003(IMG)` 与 `CA-FE` 帧头是 `/root/lilw/lidar` 已 byte-verified 的结论；JCHY 自身二进制未直证这些常量（沿用，[推断]）。详见 `docs/architecture/15-laser-scanner-integration.md`。

---

## 8. 更新记录（changelog）与领域规则/阈值

两 exe 内嵌同一份 `#更新记录#` 段（30 条，底=最早 顶=最新）[直证 `JCHY_new.exe.cjk.txt:15169-15202`]：

- **基础期**：增加新分类 → 增加前悬后悬 → 增加罐体容积计算 → 增加洒水罐体对比 → 护栏高度计算 → 增加罐体比对 → 程序结构优化 → 界面大幅更改。
- **罐体/精度期**：罐体分割 → 优化罐体分类 → 优化销轴位置查找 → 文件加密 → 优化轴距计算 → 增加各车型参数设置 → 增加深度学习开关 → 完成算法加速 → 添加罐体计算。
- **车型扩展/修复期**：修复数据异常 → 更新标定参数 → 添加特殊专项车辆联网代码 → 修复箱式挂车计算出错 → 临时增加专项车识别 → 修复吊车车型识别错误 → 增加高度限制 → 修改图片显示 → 添加车辆类型 10 → 添加手动测量 → 添加相机显示。
- **联网期（最新）**：修改异常罐车过滤参数 → 修复异型罐车车型识别错误 → 添加日志显示 → 修复联网指令错误。

**领域规则/阈值**：

- 对标 **GB7258-2017 §4.15**（外廓尺寸/轴距），产品名「重点车辆智能核验仪」[直证 cjk:5694/:15138]。
- 合规硬限值 `[LIMT]`：车长≤12000、车宽≤2550、车高≤4000、栏板≤1500 mm，`carlimt=1` 启用 [直证]。
- 罐体容积填充系数 `vol_s=0.96`，罐体分前/中/后三段各量「段长 + 双直径」+ 倾斜角 [直证]。
- DL 可关：`deepmode=0` 时不走 PointSIFT [直证]。
- 基准/比对工作流错误串 [直证 cjk]：`请先录入基准数据!`(15085)、`基准录入成功!`(15079)、`点云缺失,请检查车型是否匹配!`(15117)、`没有该型号!`(15121)、`比对完成!`(15123)、`注册码过期!`(15025)。

---

## 9. gomob 差距分析（关键交付）

### 9.1 现状对照

| 能力 | JCHY（测量层） | gomob 现状 | 差距 |
|---|---|---|---|
| 激光采集 | QtTrainScan 同源（CA-FE/zstd） | ✅ 已迁服务端（`docs/15`，laserworker 直连 .101/.102） | 无 |
| 两单元融合点云 | 两 PCD union | ✅ 服务端融合（`laser_scan_jobs` 表 + `scan.fusion_done`） | 无 |
| 点云上色 | `PointCloudColorizer`（PTZ 纹理投影） | ⚠️ 采集侧有点，但 gomob 主线 RGBD 上色不复用激光 PTZ 帧 | 需端/云上色（若要纹理） |
| **车型分类** | `getVehicleType` 几何 + `CompareCloudType` | ❌ 无 | **全缺** |
| **部件语义分割** | PointSIFT(TF) `findObjectD::predict` | ❌ 无 | **全缺**（需模型 + 推理） |
| **外廓测量** | `CLocater` 全套（LWH/轴距/罐体/栏板/护栏） | ❌ 无 | **全缺** |
| 车型参数表 | `carType.ini`（31 行已解密） | ❌ 无 | 可直接移植 §4.3 表 |
| 结果落库 | `Result.ini` + `CarSQL.db Measure` | ⚠️ gomob 有 Room/scan_session，但无车辆测量 schema | 需新增 schema |

**结论**：gomob 激光集成到「采集 → 融合点云」为止（`docs/15`），**完全没有测量/建模层**。本节给出补齐路线。

### 9.2 需新增的能力（要素清单）

1. **测量管线模块**（建议 `native/measurement/` 或服务端 Go/C++ worker）：复刻 §3 管线 ②④⑤⑥⑦。
2. **PCL 算子或 Eigen 重写**：gomob 端侧无 PCL；需在 NDK 引 PCL（重）**或**用 Eigen + 自写算子重写 `PassThrough/SOR/VoxelGrid/EuclideanCluster/minAreaRect(OBB)/getMinMax3D`（轻，推荐云端 Go+gonum 或 C++ Eigen）。
3. **PointSIFT 模型依赖**：四套 `*_seg.ckpt`（wheel/oilTank/cementTank/heavyTruck）+ `tf_user_ops_pointSIFT.dll` GPU 自定义算子。原厂 `models/` 目录为空（仅真机有），**网络结构/类别数未知**（见 §10）→ 须从真机取模型或重训。gomob 若不上 DL，可先做几何-only 测量（牵引/常规/平板可行，罐车/异型精度受限）。
4. **车型参数表**：直接移植 §4.3 解密表（31 行 Type<n>_x/_y/_z）+ §4.1 枚举 + `CalibSetting.ini` ROI。
5. **各测量算法**：轴距（轮检测+Y 排序）、前后悬、罐体三段双直径+容积（`vol_s=0.96`）、栏板深度、四向护栏离地高、对称度。
6. **结果 schema**：对齐 `Result.ini` 字段（或 Room 实体）+ 合规判定（`[LIMT]` 阈值）。
7. **标定输入**：复用 `docs/05-calibration-pipeline.md` 框架，新增激光-相机外参（四元数 + corr_offset）。

### 9.3 建议里程碑骨架（harness 可验收单元，对齐 TODO.md 风格，无占位符）

> docs: docs/architecture/16-jchy-vehicle-measurement-app.md；上游 docs/architecture/15-laser-scanner-integration.md

- **M9.1 测量管线骨架 + ROI 裁剪 + 主簇分割**
  - 实现：融合 PCD → PassThrough(`[Param]` ROI) → SOR → VoxelGrid → EuclideanCluster 取车体主簇（Eigen 重写或 PCL）。
  - 验收 harness `vehicle_measure/`：喂 `Data/100742/{1,2}.pcd`，输出主簇点数稳定，裁剪框覆盖率与原厂一致（点全落 `[Param]` 内）。

- **M9.2 LWH + 轴距 + 前后悬（几何-only，常规车）**
  - 实现：俯视投影 → minAreaRect OBB 取长宽、Z 包围盒取高；轮检测 + Y 排序算轴距 + 前后悬。
  - 验收 harness：对 `100742` 复算 `Length≈1777 Width≈533 Height≈759 Wheelbase 710/399/261 总1370 FrontOverhang≈261 RearOverhang≈163`，各项误差 < 1%（对齐 carInfo 基准 + Result.ini）。

- **M9.3 车型几何分类 + carType 偏移表接入**
  - 实现：移植 §4.3 表 + `getVehicleType/BoxOrCangShan` 几何规则；判型后叠加 Type<n>_x/_y/_z。
  - 验收 harness：对若干会话判型与原厂 `Result.ini carType` 一致；常规/牵引/平板分类准确率 = 100%（已知样本）。

- **M9.4 罐体三段 + 容积 + 栏板/护栏专项**
  - 实现：罐体切段（前/中/后）+ 投影轮廓拟合双直径 + 容积（`vol_s=0.96`）；栏板深度/四向护栏离地高。
  - 验收 harness：备罐车/栏板车会话（需补样本），三段 `长X直径1X直径2`、容积、栏板深度与原厂 Result 对齐（误差阈待定标）。

- **M9.5 PointSIFT 部件分割接入（DL 路径，可选/难分割目标）**
  - 实现：取真机四套 `*_seg.ckpt` 或重训；`generate_tensor → predict(PointXYZ→PointXYZL) → decodeCloud`；轮/罐难分割场景替几何 fallback。
  - 验收 harness：分割 IoU vs 几何基线提升；DL 开关下罐车/异型测量精度提升可量化。

- **M9.6 结果 schema + 合规判定 + 落库**
  - 实现：Room 实体对齐 `Result.ini` + `Measure` 表；`[LIMT]` 阈值合规标记。
  - 验收：端到端一次会话产出完整测量 + 合规结论，与原厂一致。

---

## 10. 证据与置信度说明（未坐实 / 待真机验证）

> 以下为 refuted / low / unverifiable / 仅符号无反汇编的点，**不作为正文事实**。

- **[refuted] camera_rot_quat 与 lidar_rot_quat 互为转置**：独立 Hamilton 展开 Rc.T ≠ Rl，二者是不同的 0/±1 轴系置换矩阵，非转置/逆关系。
- **[refuted] carType 第二层 per-position 混淆未破解**：实测整 1102B 严格周期-16、0 字节偏离，**无第二层**，明文已完整还原（§4.3）。早期把 `fe11546a...`（两份密文之差）当解密 key 的说法已订正为单层 Kdec=`0020...`。
- **[low] `findObjectD::predict` / `CLocater3Dcnn::Predict` 末尾两 int 语义**：疑为类别数/采样点数或 batch/npoint 或标签起止索引，需反汇编 AlgFuncDLL `.text` 确认。
- **[low] `generate_tensor` 6 个 float 入参语义**：是 ROI 包围盒(xmin..zmax) 还是中心+尺度，需反汇编。
- **[unverifiable] PointSIFT 部件类别名单 + 网络结构**：`models/` 原厂为空（仅真机有 `.ckpt`），无法列出 `decodeCloud` 的 label→部件（车头/车厢/罐体/吊臂/轮）映射，也无法核验层数/通道/类别数。DL label→0-59 编号是否纯几何规则、有无隐藏查表未能彻底排除。
- **[unverifiable] 容积/几何容积精确公式**：符号 `cloud_vol/volImage/vol_rc/volMinPt/volMaxPt` 暗示同时有图像/体素体积路径，「分段圆台/椭圆柱解析积分」是 [推断]，未反汇编 `caluteTank` 证得公式。`vol=10.0` 语义（上限报警? 单位换算?）无证据，仅 `vol_s=0.96` 确证为填充系数。
- **[medium→未升级] carType `_x/_y/_z` 精确语义**：是点云裁切 ROI 偏移、摆放角度补偿还是测量基准偏移，无符号级直证；仅凭与 `CalibSetting.ini`/`setting.ini` 同量纲（mm 三轴 offset）类比。需反汇编 `CLocater::setstandardValue`(226 次)/`setCarType` 看消费路径。
- **[推断] 双激光「交叉验证」语义**：`seg_block_ben_laser1/2/3` 是三块分型而 `laser_count=2`；`Length` 与 `Length2` 是否真分别来自 laser1/laser2 还是同一融合点云两种算法，需反汇编 `setValue` 数据流。
- **[推断] 地面/罐体 SAC 模型子类**：地面用 `SACMODEL_PLANE` 还是 `PERPENDICULAR/PARALLEL_PLANE`、罐体是否 `SACMODEL_CYLINDER` 无法从符号定死（只有泛化基类 `SampleConsensusModel<PointXYZ>` + 自定义 `struct PLANE` + `cv::RANSAC`，`SACSegmentation`/`RandomSampleConsensus`/具体子类 PDB 0 命中）。distance threshold 未取证。
- **[推断] 栏板三 getter 参考面**：`getCarBoardDeep/SideDeep/InnterSize` 各自相对货箱底/栏板内侧/地面哪个参考面未直证；本车 carType=2 无栏板，无非零样本。
- **[推断] 护栏「地面」基准**：RANSAC 平面拟合还是固定标定平面、四向取顶面哪种统计量，仅到字段名，无非零实测样本（本车四向全 0）。
- **[未定] `points{1,2}.txt` 第 4/5 字段**：第 4 字段(12850-65535) 是 RGB565 编码色还是激光回波强度，第 5 字段(-129/0/正整数) 是扫描行索引还是帧标志，需读采集层写出逻辑。
- **[未定] REST `setControl/setParameters` URL 后缀**：`control_scan` 是确证端点，但这两个方法对应的 `/api/` 路径名在串里缺失（早期猜的 `update_control/update_calib` 已证伪：全产物 0 命中）。`control_pc/update_fps_/update_laser_package_length` 是底层 JSON 配置键（仅 PDB），非 REST。
- **[未定] runtime color/whole 单位**：数值万级（11k-21k），DevLog 标 `sec` 与之矛盾；若 ms 则单次 12-21s。`DevLog.txt` 仅含两条带时间戳的路径日志（`D:/software/JCHY_simple_3.0.0/x64/Release` + `sec`），更新记录条目实际来源是 exe 内嵌 `#更新记录#` 串块（非 DevLog.txt）。
- **[未定] `Type2_s`/`board`/`Tank15` 归属**：`Type2_s`(-20,30,0 与 Type2 不同) 疑常规车某子工况，`board`/`Tank15`(全 0) 疑兜底项，需源码确认。
- **[未定] `carType.bin`(mtime 2023-11-08) vs `carType.ini`(mtime 2026-04-07) 同明文双存**：疑运行时 ini 编辑与 bin 双向持久化（`Bin2IniFile/Ini2BinData` 成对），读写方向需反汇编 QConfigReader。
- **[局限] VoxelGrid leaf size / EuclideanCluster tolerance 具体数值**：瘦壳 exe 算法 `.text` 内联或在 PCL DLL，常量未绑定到 setter；需反汇编或动态 hook。
