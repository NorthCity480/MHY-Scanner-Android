# 直播低延迟：方法与插件调研

日期：2026-10-06。对象：拾光扫码 Android 0.1.7 / Media3 1.5.1。仅研究与记录，没有安装插件、修改 APK、读取账号凭据或提交游戏登录确认。

## 结论

有进一步优化空间，但不存在能把任意 B站/抖音 FLV/HLS 链接变成零延迟源的通用插件。需要区分：

1. **源端已经发生的延迟**：编码、转码、分片、CDN 发布延迟。播放器不能取到服务器尚未提供的帧。换协议只有拿到真正不同的低延迟源才可能减少这些部分。
2. **本地接收与播放积压**：缓冲、解码、按时间戳呈现及追帧策略。这是现有 APK 能继续优化的部分。
3. **取帧与识码耗时**：TextureView GPU 回读、RGB 转亮度、ZXing。减少这些只会让已经收到的二维码更早被处理，不等于提前获得直播内容。

当前没有实际可用直播间、源端可比时间戳或现场时钟，没有测得任何插件在此手机上的真实端到端改善。官方说明、项目宣称或缓冲参数均不是实测结果。

## 已核对的项目现状

- `app/build.gradle` 使用 Media3 ExoPlayer/HLS 1.5.1。
- `MainActivity.startLive()`：150–650 ms LoadControl、无回看缓冲、禁用音轨；只在 URL 字符串包含 `.m3u8` 时设置约 2 秒 live target 和 0.97–1.08 倍速。
- `captureNewVideoFrame()` 仍从 TextureView 获取 Bitmap；0.1.7 已按新渲染帧触发，且在复制前预留单帧处理门控。
- `LiveResolver` 对 B站优先 FLV，并取首个符合 CDN 域名限制的地址；抖音优先 ORIGIN/FULL_HD1/HD1/SD1。目前没有比较多个合法 CDN 节点与画质的实际内容新鲜度。
- 没有自定义硬件低延迟解码 Renderer，也没有 SLDP/WebRTC 输入。HLS 格式识别与 `.m3u8` 字符串绑定，缺扩展名 URL 应以可信来源的格式/MIME 信息正确建源。

## 一、优先在现有原生架构内验证

### A. Android 硬件低延迟解码模式

Android API 30 提供 `MediaFormat.KEY_LOW_LATENCY`。官方描述：只适用于解码器，默认关闭；开启后不超出编解码标准所需地保留输入/输出数据。[1]

应先按实际 MIME/codec 检查 `MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency`，只有支持且系统 API 合适才启用，失败保守回退。[2] 这不是修改一个 App 设置即可完成，需要适配 Media3 Renderer/codec configuration。检查 Media3 **固定版本 1.5.1** 的 `MediaCodecVideoRenderer`，存在 `getMediaCodecConfiguration()` 扩展点；该文件未找到 KEY_LOW_LATENCY 的显式设置。[3] 此处的源码检索不等于已证明整套依赖或设备默认解码器绝不启用该能力。

预期减少的是解码器内部等待，无法消除编码源的 B 帧重排或上游直播延迟。尚未检查本机各视频 codec 能力，也未运行 A/B 测试。

### B. HLS 稳定追帧与自适应目标

官方 Media3 支持目标/最小/最大 live offset、倍速追帧和自定义 `DefaultLivePlaybackSpeedControl`；缓冲后默认可以扩大直播落后以减少卡顿。[4] 可增加协议感知的自适应设置，而不是无限缩小 BufferDurations。

需要区分普通 HLS 与 Apple LL-HLS；官方支持表列出 Apple LL-HLS。[5] LL-HLS 必须由后台生产与分发系统提供 partial segments、blocking reload 等机制；只有改客户端 target 不会把普通分片变成 LL-HLS。[6] 应在获得实际流后检查 `EXT-X-PART`、`EXT-X-SERVER-CONTROL`、PART-HOLD-BACK 等。当前约 2 秒设置仅是客户端目标，不是源端到识码的绝对延迟。

### C. FLV 内容积压策略与节点/画质比较

官方格式表注明 FLV 不支持常规 seek。[5] 因此不能照搬 HLS 的 seek-to-live-edge 思路。可根据可观测的样本积压/缓冲领先量尝试受限倍速追赶或受控重连，但需要处理关键帧、可见二维码持续时间和误丢帧；不要随意丢压缩字节或非独立帧。

对解析器返回的合法节点、相同画质做有限比较，观察内容时钟与稳定性；不能把更低 TCP RTT/TTFB 当作更鲜活内容。原画带宽最大不一定最快；画质过低又会导致二维码无法解码。需要在源内容延迟、卡顿和 QR 成功率之间测量取舍。

### D. 识码链路与预览分离（较大改造）

研究自定义解码输出直接提供亮度/YUV 给二维码识别，减少 TextureView→Bitmap→RGB→亮度复制。不能承诺任何 Android 硬件解码器都能输出可直接读取的 Image；ByteBuffer、Surface/GPU 路径与预览可能需要不同适配。直接分析帧应是独立性能原型，先测量抓帧 P50/P95，再决定是否替换稳定播放链路。这是工程建议，尚未验证与当前机型兼容。

## 二、确实存在的插件/库及适用边界

| 方案 | 一手依据 | 当前 APK 能否直接用 | 结论 |
|---|---|---|---|
| **Softvelum SLDP Media3 DataSource** | 官方博客明确发布 SLDP 接收库、Media3 DataSource 插件、ExoPlayer 示例；插件与接收库 LICENSE 为 MIT。[7][8] | 需加入 Gradle 依赖、核对 Media3 兼容性并重编译；服务器必须提供 SLDP。现有 HTTPS-only 输入也需要独立受控适配。 | 最贴近现有 Media3 架构的真实协议插件，但没有可用 SLDP 源时不能降低公共 FLV 的源端延迟。 |
| **WebRTC / WHEP** | SRS 文档提供 WHEP 播放、WHIP 推流和 RTMP→RTC 转换。[9] | 需要 WebRTC 客户端/信令、视频帧处理适配，不能把 FLV URI 直接交给 WebRTC。 | 若可控制推流端或平台提供授权低延迟入口，是潜力最大的架构路线之一；已有延迟的 CDN FLV 转 RTC 不能恢复迟到前的帧。 |
| **mpegts.js** | 项目支持 HTTP-FLV/TS，API 提供 `enableStashBuffer=false`、`liveBufferLatencyChasing`、`liveSync` 等；关闭 stash 可能更易因抖动卡顿。[10] | HTML5/MSE 浏览器库，不是 Android Media3 插件；需要 WebView/网页播放链路与独立取帧、CORS 适配。 | 适合 PC/浏览器对照实验。可借鉴积压追帧设计，但不能据项目“最佳情况低于一秒”的宣称保证本 App 速度。 |
| **Streamlink + 外部播放器** | 官方 CLI 有 HLS live-edge 调节，明确分片长度由提供商决定、缩短会增加卡顿风险。[11] | 独立进程/外部播放器方案，不是现有 App 的嵌入式低延迟插件。 | 可以作为 PC/Termux 测试工具；额外转发并不必然快于 App 直拉，不能凭工具名证明降延迟。 |
| **ijkplayer / LibVLC 等替换播放器** | ijkplayer 是独立播放器项目。[12] | 需替换播放与取帧层，JNI/原生依赖、许可证、构建维护成本增大。 | 没有同一手机/同一源的 A/B 证据证明其优于目前的小缓冲 Media3，不建议无测量替换。 |
| **Media3 FFmpeg decoder extension** | 固定 1.5.1 README 提供的是 `FfmpegAudioRenderer`。[13] | 增加解码扩展不等于改变视频传输协议。 | 当前已禁用音轨；这个官方 FFmpeg 扩展不是本项目视频低延迟解决办法。 |

RTSP/SRT 同样要求服务端提供对应源，不能把公共 HTTPS FLV 的 scheme 改写来获得该协议。任何所谓低延迟加速插件都应验证输入协议、内容时钟、设备瓶颈和许可证，而不是只比较启动时间。

## 三、B站/抖音适用结论

本次没有找到足够可信的一手依据，证明任意公开 B站/抖音直播间都提供可供本原生 App 接入的开放 WebRTC/WHEP/SLDP 播放入口。不能承诺通过隐藏参数、域名改写或第三方插件获得这类入口。官方客户端若对特定直播提供低延迟开关，可以作为对照路径，再通过本 App 正常录屏授权监视；是否更快仍需测量，也不会绕过平台验证、签名、登录要求或 FLAG_SECURE。

对于现有公共 FLV 方案，优先次序：

1. 请求一个可公开访问且得到授权的直播间号，不要求用户提供 signed URL、cookie 或游戏票据。
2. 加入纯播放/识码基准模式，禁止扫码登录网络调用；用源端时钟或本人的测试时间码比较收到帧、复制、解码与成功识码时间。
3. 对硬件 low-latency 能力、缓冲/追帧、节点/画质各做单变量 A/B；报告 P50/P95、卡顿次数和二维码识别率。
4. 只有源提供 SLDP/WHEP 时再做协议插件；否则先改 Renderer 和分析路径，不贸然换播放器。

真正的端到端基准需要源端可比时间/时间码。`getCurrentLiveOffset()` 是媒体 live window 的时间估计，不自动等于相机采集到识码的总延迟，且 progressive FLV 可能无法提供它。[4]

## 一手资料

[1] Android MediaFormat.KEY_LOW_LATENCY：
https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY

[2] Android codec low-latency 能力：
https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_LowLatency

[3] Media3 1.5.1 Renderer 源码：
https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java

[4] Media3 live streaming / offset / speed control：
https://developer.android.com/media/media3/exoplayer/live-streaming

[5] Media3 supported formats：
https://developer.android.com/media/media3/exoplayer/supported-formats

[6] Apple LL-HLS 生产与分发要求：
https://developer.apple.com/documentation/http-live-streaming/enabling-low-latency-http-live-streaming-hls

[7] Softvelum 官方插件发布说明：
https://softvelum.com/2025/08/sldp-exoplayer-media3-open-source/

[8] SLDP 插件及接收库、示例、LICENSE：
https://github.com/Softvelum/sldp-media3-exo-plugin
https://github.com/Softvelum/sldp-playback-library
https://github.com/Softvelum/sldp-media3-exo-example
https://raw.githubusercontent.com/Softvelum/sldp-media3-exo-plugin/main/LICENSE
https://raw.githubusercontent.com/Softvelum/sldp-playback-library/main/LICENSE

[9] SRS WebRTC / WHEP / RTMP→RTC：
https://ossrs.io/lts/en-us/docs/v6/doc/webrtc

[10] mpegts.js README 与 API：
https://raw.githubusercontent.com/xqq/mpegts.js/master/README.md
https://raw.githubusercontent.com/xqq/mpegts.js/master/docs/api.md

[11] Streamlink CLI：
https://streamlink.github.io/cli.html

[12] ijkplayer 项目：
https://github.com/bilibili/ijkplayer

[13] Media3 1.5.1 FFmpeg 扩展：
https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/decoder_ffmpeg/README.md

检索说明：默认搜索提供商未配置；匿名搜索用于发现线索，结论尽量落到官方文档/固定版本源码。部分搜索命中、低延迟优化失效页面与不可用 GitHub 地址未作为证据。当前工具没有后台研究 agent，本轮直接进行并行一手资料抓取。研究文件不改变已交付 0.1.7 源码 ZIP 的内容，也未发布新的 APK。
