# 3D 扫描工位管理台

这个目录是一个静态网页应用，用来管理激光 3D 扫描工位。

## 启动

```bash
cd server
go run ./cmd/laserstationweb
```

浏览器打开：

```text
http://127.0.0.1:5177/
```

默认 Gateway 是 `http://当前服务主机:18808`。动态密码登录后，网页会自动从 `/station/session` 获取短期 access token 并填入连接面板，实时点云通过 `/v1/ws?token=...` 接收。

网页入口使用动态密码登录，规则是 `3d` + 当天月日。例如 6 月 8 日密码为 `3d0608`。密码按服务本机日期计算，登录 cookie 当天有效。

可选环境变量：

- `GOMOB_LASER_STATION_ADDR`：监听地址，默认 `0.0.0.0:5177`。
- `GOMOB_LASER_STATION_GATEWAY`：覆盖 Gateway 地址。
- `GOMOB_LASER_STATION_USER_ID`：自动签发 token 的用户 id，默认 `1`。
- `GOMOB_LASER_STATION_ROLE`：自动签发 token 的角色，默认 `admin`。
- `GOMOB_LASER_STATION_COOKIE_SECRET`：固定 cookie 签名密钥；不设置时每次启动随机生成。

## 功能

- 工位管理：新增、删除工位。
- 相机管理：给每个工位维护多个激光相机，默认镜头 A 为 `192.168.9.101`，镜头 B 为 `192.168.9.102`。
- 扫描控制：按当前工位的 A/B 相机 IP 发起扫描，实时显示两路原始点云。
- 相机控制：刷新状态、守望、停止、回零、清错、软重启。
- 扫描配置：下发当前 App 中使用的扫描运动配置，支持“初始位置 + 扫描角度”。
- AB 标定点：依次在 A 点云、B 点云标注同一个物理点，至少 3 对非共线点后计算 `B -> A` 刚体变换。

## 标定建议

手动标定时建议放 5 到 8 个尖锐、稳定、分布开的物体。每一对标注点都必须是同一个物理点，且不要全部在一条直线上。计算结果会输出两份：

- `b_to_a_mm`：网页和端侧调试使用，平移单位是毫米。
- `b_to_a`：当前 native C-ABI 的 site 外参格式，平移单位是米。网页会把已翻转的显示坐标矩阵按 `F*T*F` 转回 native 原始坐标后再传给 worker。
