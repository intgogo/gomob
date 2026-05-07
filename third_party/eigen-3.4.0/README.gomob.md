# Eigen 3.4.0 投放说明（gomob）

- 上游：https://gitlab.com/libeigen/eigen/-/archive/3.4.0/
- 主授权：MPL2（部分模块 LGPL，详见 `COPYING.*`）
- 仅保留 `Eigen/`、`unsupported/`、license 文件、`signature_*`，剔除测试 / bench / cmake helper

## 用法

`native/CMakeLists.txt` 已加 `include_directories(third_party/eigen-3.4.0)`，C++ 直接 `#include <Eigen/Dense>` 即可。

仓内常用模块：

- `Eigen/Dense` — 矩阵 / 向量 / SVD / QR / Cholesky（ICP 用）
- `Eigen/Geometry` — Quaternion / Transform / AngleAxis（位姿表达）
- `Eigen/Sparse` — TSDF 后处理可能用

## 不要做的事

- 不要从 master 升级 — 3.4.0 是稳定 release，自 2021 起没动
- 不要复制 Eigen 头出去散用，C++ 通过 include path 找
