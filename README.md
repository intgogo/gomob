# gomob

Android 端 3D 扫描应用 — 把外接 Berxel iHawk 深度相机与手机主摄像头深度绑定，
做"双摄合一"的高精度移动 3D 扫描设备。

## 快速上手

```bash
./scripts/ensure-android-sdk.sh   # 检查并补装 SDK / NDK / CMake
./dev.sh doctor                   # 自检环境
./dev.sh build                    # 编译 debug APK
./dev.sh install                  # 安装到当前已连接设备
```

## 文档入口

- `AGENTS.md` — 所有协作 Agent 的统一入口
- `CLAUDE.md` — Claude Code 工作时遵循的项目规范（含第一性原则、UI 验证规范等）
- `docs/architecture.md` — 架构总入口
- `TODO.md` — 当前迭代任务

## 技术栈

Kotlin + Jetpack Compose + NDK(C++17) + Hilt + Room + CameraX + Filament + Coroutines

工程结构参考 [Now in Android](https://github.com/android/nowinandroid) 多模块约定。
