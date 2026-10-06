# 拾光扫码 · MHY Scanner Android

一个面向 Android 的米哈游游戏登录二维码扫描工具。项目是对 [DSVVA/MHY_Scanner](https://github.com/DSVVA/MHY_Scanner) 思路的 Android 原生重写，使用 MediaProjection、Media3 和 ZXing 系列库完成屏幕/直播画面取帧与二维码识别。

> 非米哈游官方应用，与米哈游、HoYoverse、哔哩哔哩或抖音没有隶属关系。请只在自己的设备、自己的账号或获得明确授权的测试场景中使用。

## 特性

- Android 原生实现，支持 Android 8.0+（API 26）。
- 支持崩坏3、原神、崩坏：星穹铁道、绝区零中国官服登录二维码。
- 支持两类账号来源：
  - 米游社社区扫码登录，保存明确标记的 SToken + MID 社区凭据。
  - 手动导入用户自己的 game_token。
- 使用 Android Keystore + AES-GCM 加密本地账号保险库。
- 支持屏幕监视、图片识码和公开直播流识码。
- 支持 B站公开房间、实验性的抖音公开接口以及 HTTPS FLV/HLS 直链。
- 直播画面支持 1.0x、1.15x、1.25x、1.5x 播放速度；倍速直播扫码会同步使用新帧触发识码。
- 优先使用支持 `FEATURE_LowLatency` 的硬件解码器；ZXing-C++ 不可用时回退 ZXing Java。
- 手动确认是默认行为；自动确认必须由用户对当前会话单独授权。
- 不依赖 root、Shizuku、无障碍服务或第三方登录 Cookie。

## 安装

从 GitHub Releases 下载 APK 后，在 Android 系统中允许对应的安装来源，再进行安装。Debug APK 使用测试签名，不是 Play 商店发行包。

覆盖安装同一签名版本不会主动清除应用数据，但发布前仍应自行备份重要资料。不要安装来源不明的重签名版本。

## 使用流程

1. 打开「账号」→「米游社扫码添加账号」，用自己的米游社确认社区登录二维码。
2. 或导入自己持有的通行证账号 ID + game_token。不要把 token、二维码票据或签名直播 URL 发到聊天或提交到仓库。
3. 在「监视」中选择账号和目标游戏。
4. 选择「开始屏幕监视」、图片识码，或输入公开 B站房间号/HTTPS 视频流地址。
5. 直播扫码时可选择 `1.25x` 追帧。高于 `1.0x` 会自动使用新帧触发识码；出现卡顿或追到最新位置后恢复 `1.0x`。
6. 识别到二维码后，默认需要回到应用手动点击确认。自动确认只应在本人设备或明确获授权的会话中使用。

倍速只能消化播放器已经积累的可播放内容，不能消除主播推流、转码、CDN 分发或普通 HLS 分片产生的源端延迟，也不能保证抢码成功。

## 隐私与安全边界

- 账号凭据保存在本机加密保险库，不上传到本项目开发者服务器。
- 应用不会读取其他应用的登录数据、Cookie、密码或短信。
- 屏幕监视使用 Android 系统录屏授权，并尊重系统的安全窗口限制；不会绕过 `FLAG_SECURE`。
- 性能测试只访问公开直播源，不读取保险库，也不会发送游戏扫码或确认请求。
- 项目不实现验证码、风控、签名、限流、平台登录或录屏限制绕过。
- 请不要将 `local.properties`、Keystore、token、账号数据、抓屏图片、直播签名 URL 或设备日志提交到 Git。

## 已知限制

以下能力不在项目承诺范围内：

- B服、海外服以及密码/短信登录。
- SToken 自动刷新。
- 绕过验证码、风控、签名、限流、登录要求或平台限制。
- 后台直播解码、悬浮窗和保证抢码成功。
- 通过客户端配置消除主播到手机的绝对直播延迟。

出现直播流 HTTP 错误、平台接口变化或二维码无法识别时，可改用系统屏幕监视或图片识码，并查看应用内的脱敏错误信息。不要把完整错误日志、账号标识或带签名的 URL 公开。

## 项目结构

- `app/src/main/java/com/partner/mhyscanner/AccountStore.java`：Keystore 加密账号保险库。
- `app/src/main/java/com/partner/mhyscanner/ApiClient.java`：米游社社区登录及官方扫码接口。
- `app/src/main/java/com/partner/mhyscanner/QrPayload.java`：二维码解析、游戏匹配和有效期检查。
- `app/src/main/java/com/partner/mhyscanner/ScanCoordinator.java`：会话、去重、确认和脱敏日志。
- `app/src/main/java/com/partner/mhyscanner/LivePlayback.java`：直播播放、解码器选择和缓冲策略。
- `app/src/main/java/com/partner/mhyscanner/LiveResolver.java`：公开直播地址解析。
- `app/src/main/java/com/partner/mhyscanner/ScreenCaptureService.java`：前台屏幕捕获。
- `app/src/test/`：协议、二维码输入和取帧门控测试。
- `scripts/`：构建、公开接口探测和本地样例工具。
- `docs/`：历史构建报告、低延迟调研和脱敏测试资料。

## 开源许可与致谢

本项目采用 [GPL-3.0-only](LICENSE) 发布。第三方依赖及其许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、`app/src/main/assets/THIRD_PARTY_NOTICES.txt` 和 `licenses/`。

项目参考了：

- [DSVVA/MHY_Scanner](https://github.com/DSVVA/MHY_Scanner)
- [loqwe/MHY_Scanner2](https://github.com/loqwe/MHY_Scanner2)
- [ZXing](https://github.com/zxing/zxing)
- [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp)
- [AndroidX Media3](https://github.com/androidx/media)

问题反馈请不要附带账号凭据、二维码内容、Cookies、签名 URL、屏幕截图或完整设备日志。提交前请先脱敏。
