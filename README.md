# Moto 导航 · Android 手机端

配套 **Waveshare ESP32-S3-Touch-AMOLED-1.75C** 摩托车圆屏导航固件使用。

手机做三件事：拿定位 → 用 **OSRM**（开源路线引擎，免费无需 Key）算路线 → 通过 **BLE** 把转向和距离发给圆屏。
圆屏只负责显示，不需要联网、不需要 GPS 模块、不需要高德 Key、不需要自建网关。

---

## 一、怎么装

1. 打开仓库的 **Actions** 标签页
2. 点最新一次 `Build APK` 运行
3. 页面底部 **Artifacts** 里下载 `moto-nav-apk.zip`
4. 解压得到 `app-debug.apk`，传到手机安装（需允许「安装未知来源应用」）

> 每次你 push 代码到 `main`，GitHub 会自动重新编译。

---

## 二、怎么用

1. **先让圆屏进入广播状态**（板子已在跑 moto-gps 固件，上电即广播）
2. 打开 App，授予 **定位**、**附近设备**、**通知** 权限
3. 点「扫描设备」，列表里出现 `MOTO GPS` 后点它连接
   - 首次可能弹系统配对框，点「配对」（固件要求加密连接）
   - 状态栏显示「已就绪」= 握手完成
4. 输入目的地（如 `北京 故宫`），点「搜索地点」
5. 在候选列表里点一个 → 自动规划路线
6. 点「开始导航」→ 圆屏开始显示转向和距离

骑行前建议：手机屏幕常亮、把 App 加入电池白名单，避免后台被杀。

---

## 三、技术说明

### 定位
`LocationManager`，GPS + 网络双源，1 秒更新。
手机上没有 Google Play 服务也能用（不依赖 FusedLocationProvider）。

### 路线
- **OSRM** 公共服务器 `router.project-osrm.org`，driving profile
- 返回完整路线几何 + 逐段转向指令（含中文路名）
- 地址搜索用 **Nominatim**（OpenStreetMap 地理编码）

想换成自建 OSRM 或高德，只改 `RouteService.kt` 里的两个常量即可。

### BLE 协议
严格实现 `moto-gps-waveshare` 的 **BLE Navigation Protocol v1**：

| 项 | 值 |
|---|---|
| Service UUID | `7e57a000-b50c-4b6a-9c57-40a54e8e1000` |
| RX（手机→设备） | `7e57a001-…`，encrypted write |
| TX（设备→手机） | `7e57a002-…`，notify |
| CCCD | `00002902-…` |

帧格式（小端）：

```
0    magic = 0xB7
1    protocol_version = 1
2    message_type
3    flags  bit0 START, bit1 END, bit2 ACK_REQ, bit3 URGENT
4-5  sequence
6-7  fragment_offset
8-9  message_length
10.. fragment_payload
末尾 u16 CRC16（CCITT-FALSE，低字节先发）
```

- CRC-16/CCITT-FALSE：poly `0x1021`，init `0xFFFF`，无反射，xorout 0
- 自检串 `"123456789"` → `0x29B1`（`Proto.selfTest()`）
- 握手：Phone Starting → Device Ready → Phone Ready → Device Ready
- 心跳 1 秒；3 秒收不到帧判定链路异常
- 分片按协商后的 `maxFrameSize = min(MTU-3, 512)`

### 代码结构

```
app/src/main/java/com/motonav/app/
├── Proto.kt         帧编解码、CRC、各消息负载编码
├── BleClient.kt     扫描、连接、握手、分片发送队列
├── RouteService.kt  OSRM 路线 + Nominatim 地址搜索 + 地理计算
├── NavEngine.kt     进度/偏航/下一个转弯，OSRM→协议枚举映射
├── NavCore.kt       定位 + 导航循环 + 组装快照
├── NavService.kt    前台服务（息屏保活）
└── MainActivity.kt  界面与权限
```

---

## 四、已知限制

- **只发 NavigationSnapshot**，没发 RouteGeometry / MapScene → 圆屏上不会画出路线线条，只有转向箭头、距离、路名
- **不控制音乐**（MediaState 未实现）
- OSRM 公共服务器**不保证稳定性**，也没有实时路况
- 路线是**普通机动车**，不保证避开摩托车禁行路段
- 目前是 debug 签名，可自用；分发需自行配置正式签名

---

## 五、下一步可以加

1. 发 `RouteGeometry`，让圆屏画出路线线条
2. 接收 `DeviceCommand`（圆屏触摸切页）
3. 换成 Valhalla 做摩托车专用路线
4. 离线路线缓存（出发前预下载）

---

协议规范详见 `moto-gps-waveshare` 仓库的 `shared/protocol/ble-navigation-v1.md`。
