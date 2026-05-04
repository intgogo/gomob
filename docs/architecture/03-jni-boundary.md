# 03 — JNI 边界契约

## 唯一入口

`io.gomob.nativebridge.NativeBridge` 是 Kotlin → C++ 的**唯一**通道：

- 所有 `external fun` 集中在 `NativeBridge.kt`
- 所有 `JNIEXPORT ... JNICALL` 集中在 `native/jni/jni_bridge.cpp`
- feature / 其它 core 模块**禁止** `System.loadLibrary` / 散点 `external`

**Why:** 散点 JNI → 符号污染、加载顺序不可控、调试时栈帧到处跳。集中边界 → 易测、易审。

## 数据通道选型

| 数据类型 | 通道 | 说明 |
|---------|------|------|
| 标量 / 元数据 | 直接 JNI 参数 | int/double/short/byte 数组（小） |
| 单帧 RGBD 数据（数 MB） | `DirectByteBuffer` 共享内存 | 零拷贝；`GetDirectBufferAddress` 直接拿指针 |
| 跨进程帧（前台服务 → UI 进程） | `HardwareBuffer` + Binder | Android 8+ 推荐，适合 GPU 共享 |
| 流式日志 | `__android_log_print` (C++) ↔ Logcat (Kotlin Timber) | 不要发明新通道 |

**默认规则：** 任何**单次** > 64KB 的数据走 `DirectByteBuffer`；JNI 数组拷贝**仅**用于元数据。

## 错误模型

- 不用 -1 / null 哑值表示错误
- C++ 端抛 `gomob::NativeError(errorCode, message)`
- JNI 入口捕获 → `env->ThrowNew(NativeException)`
- Kotlin 侧统一接 `try { ... } catch (e: NativeException) { ... }`

`NativeException` 已在 `NativeBridge.kt` 定义。`errorCode` 命名空间：

| 区段 | 含义 |
|------|------|
| `0` | OK（不应抛） |
| `1xx` | 设备 / 连接 |
| `2xx` | 帧采集 / 同步 |
| `3xx` | 几何 / 配准 |
| `4xx` | 重建 |
| `9xx` | 内部不变量违反（assert 级别） |

## 内存所有权

| 方向 | 谁分配 | 谁释放 |
|------|--------|--------|
| Kotlin → C++（参数） | Kotlin | Kotlin（JNI 自动） |
| C++ → Kotlin（返回值数组） | C++（`env->NewXxxArray`） | Kotlin GC |
| `DirectByteBuffer` 双向共享 | 看具体场景；约定一方 owner | 同 owner |
| Berxel SDK 内部缓冲 | Berxel SDK 拥有 | 必须在同一调用周期内释放，不跨边界 |

## 线程模型

- Kotlin 调进 C++ 默认在**调用方线程**（不切线程）
- 长耗时（重建、Marching Cubes）在 C++ 内部 `std::thread` 自管，回调通过 `JavaVM::AttachCurrentThread` 回 Java
- Berxel SDK 的回调线程**不是** Java 线程，必须 `AttachCurrentThread` 后再调 `CallVoidMethod`

## 加载顺序

1. App 启动 → `MainActivity` 触发 NavHost
2. 第一次访问 `NativeBridge` → `init { System.loadLibrary("gomob_native") }`
3. JNI_OnLoad（如启用）→ 缓存 JavaVM* / 常用 jclass / jmethodID
4. Berxel SDK 初始化（如有）→ NativeBridge 暴露的 `attachUsbDevice` 在用户授予 USB 权限后调用

**禁止**：在 Application.onCreate() 立刻 loadLibrary（启动慢；权限未到）。

## 测试

- C++ 单测走 GoogleTest（待 M1.3 引入）；通过 host 端跑（不上设备）
- JNI 桥接测试：`androidTest` 里 instrumented test 走真机，验证签名一致性
- 集成测试：harness 串端到端

## 反模式（自检清单）

- [ ] 在 feature 模块 `external fun` → 拆到 `core:native-bridge`
- [ ] JNI 大数据拷贝（> 64KB） → 改 DirectByteBuffer
- [ ] 用 -1 / null 当错误码 → 改 NativeException
- [ ] Berxel 回调里 `CallVoidMethod` 但没 `AttachCurrentThread` → 必崩
- [ ] `JNI_OnLoad` 里执行业务逻辑 → JNI_OnLoad 只做缓存
