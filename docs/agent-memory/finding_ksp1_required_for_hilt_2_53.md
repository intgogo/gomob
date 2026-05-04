---
name: KSP2 + Hilt 2.53.x 不兼容，必须用 KSP1
description: Hilt 2.53.x 在 KSP2 模式下 @AndroidEntryPoint / @HiltAndroidApp transform 不生效, 编译报 "Expected to have a value. Did you forget to apply the Gradle Plugin?"
type: finding
---

# KSP2 + Hilt 2.53.x 不兼容

## 现象

`gradle.properties` 里设 `ksp.useKSP2=true`，构建 :app 报：

```
e: [ksp] [Hilt] Expected @AndroidEntryPoint to have a value.
       Did you forget to apply the Gradle Plugin? (com.google.dagger.hilt.android)
e: [ksp] [Hilt] Expected @HiltAndroidApp to have a value. ...
```

实际 Gradle Plugin 已经应用（在 `build-logic` convention 里
`pluginManager.apply("com.google.dagger.hilt.android")`），插件 jar 也在 build-logic 的
`compileOnly` 依赖里。问题不在 plugin 配置 — 是 Hilt 2.53.x 的 KSP2 路径未正确触发
其字节码 transform 给 `@AndroidEntryPoint` 注解填值。

## 解决

```properties
# gradle.properties
ksp.useKSP2=false
```

改完立刻 BUILD SUCCESSFUL。

## 何时可去掉这条限制

- Hilt 升到一个明确支持 KSP2 的版本（关注上游 release notes）
- 那时把 `ksp.useKSP2=true` 改回，跑一遍 `:app:assembleDebug` 验证

不要为了"用新功能"提前打开 KSP2 — 没新功能值得这个返工。
