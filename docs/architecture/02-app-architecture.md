# 02 — App 多模块架构

## 蓝本

[Now in Android](https://github.com/android/nowinandroid) 的多模块约定 +
本项目的 3D 扫描特化层（`native/` + `core:native-bridge` + `third_party/berxel-android/`）。

## 模块切分

```
app/                      ← Application 入口；只组合，不实现业务
build-logic/convention/   ← Gradle 约定插件（gomob.android.application/.library/.compose/.feature/.hilt/.native/.jvm.library）

core/
├── common/               ← Result / 工具
├── model/                ← 数据契约
├── data/                 ← 仓库
├── database/             ← Room
├── domain/               ← 用例
├── designsystem/         ← Material3 主题 / 公共组件
├── ui/                   ← 公共 Composable
├── network/              ← OkHttp + Retrofit
└── native-bridge/        ← JNI 唯一入口

feature/                  ← 业务功能（每个独立 Activity 入口或 NavGraph）
├── scan/
├── gallery/
├── calibration/
└── settings/

native/                   ← C++ 实现（依赖 NDK，编译为 libgomob_native.so）
├── depth/
├── fusion/
├── reconstruction/
└── jni/                  ← extern "C" + JNICALL 集中点

third_party/
└── berxel-android/       ← Berxel 官方 Android SDK 投放（aar + jniLibs + include）
```

## 依赖规则（硬约束）

详见 `00-overview.md` 的依赖图。补充几条：

1. **`core:model` 是最底层** — 不依赖任何其它 core 模块；任何 core 模块都可以依赖它
2. **`core:designsystem` 不依赖 `core:ui`** — 反过来 ok
3. **feature 模块互不依赖** — 共享走 core 层
4. **`core:native-bridge` 是 JNI 唯一通道** — feature / 其它 core 不允许 `System.loadLibrary` / `external fun`
5. **`third_party/` 隔离** — 业务代码不直接 import Berxel API，统一通过 NativeBridge 暴露的抽象方法

## Convention 插件

`build-logic/convention/` 提供 8 个约定插件，避免每个 `build.gradle.kts` 重复样板：

| 插件 ID | 用途 |
|---------|------|
| `gomob.android.application` | App 模块基础（Kotlin + AndroidX + 编译选项） |
| `gomob.android.application.compose` | App 模块叠加 Compose |
| `gomob.android.library` | 库模块基础 |
| `gomob.android.library.compose` | 库模块叠加 Compose |
| `gomob.android.feature` | feature 模块（library + hilt + compose 默认依赖） |
| `gomob.android.hilt` | Hilt + KSP |
| `gomob.android.native` | NDK + CMake + ABI 过滤 |
| `gomob.jvm.library` | 纯 JVM 库（domain 用例可选） |

## 版本目录

`gradle/libs.versions.toml` 是**唯一**版本真理源。新增依赖必须先在 `[versions]` 加版本号，
再在 `[libraries]` 引用。**不**在 `build.gradle.kts` 直接写版本字面量。

## 包名约定

| 模块 | namespace |
|------|-----------|
| app | `io.gomob.scan` |
| core:* | `io.gomob.<short>`（`io.gomob.common` / `io.gomob.model` / ...） |
| core:native-bridge | `io.gomob.nativebridge` |
| core:designsystem | `io.gomob.designsystem` |
| feature:* | `io.gomob.feature.<short>`（`io.gomob.feature.scan` / ...） |

## 构建变体

- `debug` — applicationIdSuffix `.debug`，可与 release 同设备共存；NDK 不裁剪
- `release` — minify + shrinkResources；签名待用户提供 keystore（不进库）

## 资源 / 配置文件归属

- 静态资源（标定板模板、demo 模型）→ `assets/`（app 模块打包）
- 标定参数等运行时数据 → Room（`core:database`）
- 扫描预设 / 设备元数据 → `configs/`（YAML，运行时由 `core:data` 读取）— 待 M1 引入
