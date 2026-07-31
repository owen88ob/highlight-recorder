# 高光回录(Highlight Recorder)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)]()
[![Website](https://img.shields.io/badge/Website-宣传页-orange.svg)](https://owen88ob.github.io/highlight-recorder/)

Android「游戏高光回录」App,类似一加游戏相机:后台持续循环录制屏幕(环形缓冲),
按下悬浮球立即把**按下前 N 秒**的画面保存为 MP4。

> 宣传页:https://owen88ob.github.io/highlight-recorder/

## 功能

- 悬浮球全局可用:单击 = 保存前 N 秒回放;长按 = 停止录制(未录制时拉起 App 开始)。
- 环形回录:MediaProjection + VirtualDisplay → MediaCodec 硬件编码(H.264/H.265),
  按关键帧切 1 秒分片,只保留最近 N+2 秒,保存时从 IDR 边界拼接,无花屏。
- 音频:内录(AudioPlaybackCapture,Android 10+)/ 麦克风 / 静音。
- 设置:回退时长(15–120s)、分辨率、帧率、码率、编码器、音频来源、悬浮窗外观。
- 视频库:缩略图、时长、大小,支持播放 / 分享 / 删除。
- 前台服务 + 常驻通知(通知栏可直接"保存回放 / 停止");
  低电量 / 低内存自动降码率,内存危急时暂停录制并在通知提示。

## 构建

环境:JDK 17+(开发用 21),Gradle Wrapper 自带。

```bash
./gradlew assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 环形缓冲 / PTS 重定基单元测试
```

SDK 路径在 `local.properties`(本仓库使用项目内 `.android-sdk/`,已在 .gitignore 排除)。
在自己机器上构建:改成你的 SDK 路径或用 Android Studio 打开自动生成。

## 使用流程

1. 安装 APK,首次打开进入引导页,逐项授权:
   悬浮窗 → 了解屏幕录制机制 → 通知 → 存储(仅 Android 9-)→ 电池优化白名单(建议)。
2. 主页点「开始循环录制」,在系统弹窗中确认屏幕录制(Android 14+ 每次都要确认,正常)。
3. 切到游戏,正常游玩——App 在后台持续缓冲最近 N 秒。
4. 打出高光后,**单击悬浮球**(或通知栏「保存回放」),几秒后 Toast 提示保存成功。
5. 回 App「视频库」查看 / 播放 / 分享;文件在 `Movies/高光回录/`。
6. 长按悬浮球或主页「停止录制」结束循环录制。

## 权限说明

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 游戏全屏上显示悬浮球 |
| MediaProjection(每次会话同意) | 采集屏幕画面,系统强制每次确认 |
| POST_NOTIFICATIONS(13+) | 前台服务常驻通知 |
| RECORD_AUDIO(可选) | 音频来源选「麦克风」时 |
| WRITE_EXTERNAL_STORAGE(仅 ≤28) | 老系统保存视频 |
| 电池优化白名单(可选) | 防后台被杀 |

## 技术要点

- **环形缓冲**(`buffer/RingSegmentBuffer`):编码输出按实际到达的关键帧切片
  (不假设 `KEY_I_FRAME_INTERVAL=1` 一定生效),容量 N+2 秒,旧分片自动逐出;
  快照起点必为 IDR。
- **保存**(`buffer/ClipWriter`):快照只拷引用,录制不中断;PTS 重定基为 0;
  MediaMuxer 封装,经 MediaStore 落盘 `Movies/高光回录/`。
- **零原始帧**:VirtualDisplay 直送编码器 inputSurface,全程无 Bitmap/帧拷贝。
- 单元测试:`RingSegmentBufferTest`(逐出/快照/IDR 对齐)、`AudioRingBufferTest`、
  `PtsRebaserTest`(重定基单调性、音视频裁剪)。

## 已知限制

- DRM 保护内容(部分视频 App)无法录制,画面为黑屏,这是系统限制。
- Android 14+ 每次开始录制都要点一次系统确认,无法跳过。
- 内录取决于目标 App 的音频捕获策略(`allowAudioPlaybackCapture`),被拒绝时自动静默为无音频。
- 部分机型编码器不严格遵守 1 秒 I 帧间隔,此时分片略长于 1 秒(功能不受影响,
  因为分片边界按实际关键帧切)。
- 保存的回放时长可能比 N 略短:起点要回溯到最近一个关键帧边界。

## 目录结构

```
app/src/main/java/com/highlightrecorder/
├── capture/    采集编码:CapturePipeline / VideoEncoder / AudioCaptureEncoder
├── buffer/     环形缓冲:RingSegmentBuffer / AudioRingBuffer / PtsRebaser / ClipWriter
├── service/    前台服务:RecordingService(生命周期/通知/降级)
├── overlay/    悬浮窗:OverlayManager / FloatingButtonView
├── ui/         Compose 页面:引导/主页/设置/视频库 + MainViewModel
└── data/       设置(DataStore)/ 视频库(MediaStore)
```
