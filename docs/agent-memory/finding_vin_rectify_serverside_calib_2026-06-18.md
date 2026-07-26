# VIN 还原全量上服务端（Go cvengine+gocv，原厂全保真）；端侧只拍存传

VIN「数码拓印」= 拍照后用深度对 RGB 做正射校正 + 去阴影 + OCR 级后处理，把钢印字符还原成
正视无透视、多角度可重合的拓印图。用户 2026-06-18 两条拍板：①「后处理还原逻辑/算法按原厂逆向对齐」；
②「把还原相关的算法都放到服务端去」。

**Why**：原厂还原是**重型 OpenCV + ONNX 管线**（YOLO 字符 OBB + RANSAC 平面 + 摆正 + 四点单应 +
去阴影 + OCR 后处理 + 逐设备双目标定），端侧扛不动也不该扛 → 服务端是对的家。

## 订正旧结论（本轮逆向推翻）

- ❌「原厂算法是明文 C++ 源码、so 里只是立体校正不是 VIN 正射、已移植 70%」**全错**。
  真还原管线**就在 `libcreator_jni.so`**：`ImageRestorerFunc::restoreImageFlow`（0x37f48c，2026-06-18 工作流逐函数反汇编+对抗校验）：
  深度反投影(门 50<z<1000mm,点<100 废) → **RANSAC 平面 z=ax+by+d(50迭代)** → **tilt=acos(1/√(a²+b²+1))·180/π,>70°废** →
  **R=RotY(atan2(a,√(b²+1)))·RotX(−atan2(b,1))** 摆正 → **metric 画布 px/mm(模式 20/25/10),上限40000×10000** →
  4角 GetPlaneXYZ/GetRotatedXY → `getPerspectiveTransform`+`warpPerspective` → postProcessV3G → picshadow → IsCheckImage。
- ❌❌ **订正臆测**：`picshadow` **不是去阴影**(是黑像素行列投影的**内容裁剪**,无滤波)；`postProcessV3G` **不是亮度/对比度/gamma**
  (是**四角合法性裁剪**:≥3角 minAreaRect去斜/<3角 128灰底居中ROI)。真二值化/去阴影/gamma 在**上游**(GetSignature3G/CaptchaRecog,待逆向)。
- 畸变 = 偶次径向多项式+Brown切向+前置 atan(FOV)，LM(MINPACK)去畸变，**非 OpenCV 5 参**。2026-07-14 订正：标定 bin 的 `0x200` 是 format version=3，不是 camCount；原厂 loader 不校验相机数量。
- 4 月 `Tools/VinRectifyDemo/vin_rectify_demo.cpp` + `VinRectifyGui` + `VIN_RGBD_Rectification_Design.md` 是
  **旧简化逆向稿**（单相机 registered 假设、forward splat+hole fill），**非原厂真源码，不作对齐基准**。
- 端侧 `native/vin/ortho_rectify.{h,cpp}` + JNI `vinOrthoRectify` 降级为「拍到了」即时近似预览，**不再是真还原路径**。

## 决策（用户拍板）

- 运行时 = **扩 Go cvengine（gocv）**，复用容器 OpenCV4.6；保真度 = **原厂全保真**（不做简化 MVP）。
- 端侧只「拍 + 存原始 + 上传」。原厂输入契约：`*_rgb1300.jpg`(HLSD8 彩色) + `*_depth.yuv`(深度16bit) + deviceID + regions[]。
- `processAll` 真签名：`(deviceID, rgbPath, depthPath, outPath, "/VIN/param", regions[], 站名, 操作员, 记录VIN, "front"|"rear", brightness:int, contrast:int, gamma:float)`。
- ONNX 侧纯 Kotlin 全 recovered：`yolo-obb.onnx`(YOLO11s-OBB,[1,3,640,640]→[1,6,8400],单类)；letterbox640/pad114/÷255/NCHW；
  score≥0.5；rotatedNms IoU>0.4；regions 每字符 **9 floats** `[TL,TR,BR,BL 各xy + angleDeg]`(rgb1300 像素系)。

## 首批真机数据（脱机自测基准）

`.dev/vin_captures/` 11 张/2 块板（2510DRK44C）：板1 cap_001-006=`☆LA99FRP32G0LTH013☆`、板2 cap_007-011=另一串(暗+反光+角度)。
该批彩色是旧 1280×256 预览转储；深度 640×128 的 u16 实为 raw disparity×8，历史 meta 误写 mm 与 `fy=163.894`，所以“板距1850mm”无效。当前生产使用 HLSD8 4160×832、原厂 BIN 的 640×128 `fx=fy=614.60498,cx=324,cy=65.4325` 及完整双相机外参。

## How to apply

- 还原算法别再去看 `vin_rectify_demo.cpp` 当原厂；真理源 = 反编 `libcreator_jni.so` + `ONNXDetector`(dex)。
  逆向工具：NDK `llvm-objdump`(host binutils 无 aarch64)+`androguard 4.0.1`。
- 先建 Python harness `tests/harness/vin_restore/`(cv2+numpy+onnxruntime)做参考实现+「多张还原重合」可判定分析，
  验证算法后再端口到 Go cvengine。验收=同一 VIN 多角度还原图对齐后重合(SSIM/字符位置一致)。
- 标定 bin 格式由 `CCameraModel::LoadBinStereoParas`(0x36a32c) 决定，关系到我们自标定输出布局；若实测有视差走 ArUco(15cm 靶,与距离无关,不用深度流)。
- 权威：`docs/architecture/08-vin-rectify-design.md` §10（全量逆向规格 + 数据现状 + 落地计划）。
- 相关：[[finding_two_cameras_hlsd8_rgb_2026-06-10]]、[[finding_p100r3_device_params_offline_only_2026-05-27]]。
