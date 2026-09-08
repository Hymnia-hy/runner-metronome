# 跑者节拍器（RunMetronome）

一款原生 Android 跑步节拍器 App：

- 柔和耐听的音色（共 3 款：气泡·柔 / 气泡·缓 / 脚步·沉），为长时间（马拉松）设计；**点音色卡片即时试听**，无需开启节拍器
- 步频 110–230 BPM 无级调节（滑块 + ±1 步进器，步进器支持长按连续调节）
- **运行中可实时改步频 / 音色 / 音量 / 倒计时**，不必先停止再开始
- 播放时不打断 QQ音乐 / 喜马拉雅 等其他音频（媒体流 + 不抢音频焦点），顶栏明示「♪ 与音乐共存」
- 锁屏 / 后台持续播放（前台服务 + 常驻通知 + PARTIAL_WAKE_LOCK），通知栏可直接暂停 / 继续 / 停止
- 可选倒计时（0–300 分钟），暂停时倒计时同步停走，每 15 分钟报时，到点提示并发出可清除的结束通知
- 设置（步频 / 音量 / 音色 / 倒计时）自动记住，冷启动沿用上次配置

目标机：OPPO Find X8s（Android 15 / ColorOS 15），minSdk 26。

> 音效素材：取自 BigSoundBank（CC0 公有领域）的水泡破裂与跑步脚下触地声，已提取单瞬态并转为 16bit PCM 内置。

## 目录结构
```
metronome/
  app/src/main/java/com/example/runmetronome/
    MainActivity.kt          # Compose 主界面（节拍柱脉冲 / 卡片 / 长按交互 / 引导）
    PlaybackStore.kt         # 服务 → UI 的单向状态回传（运行态 / 已跑时长 / 剩余时间）
    SettingsStore.kt         # 设置持久化 + 全局参数范围常量
    MetronomePlayer.kt       # 音频层（长 PCM 无缝循环 + SoundPool 短音，含错误处理）
    BeatPcmBuilder.kt        # 节拍长 PCM 烘焙（整数拍循环 + 尾部环绕写入）
    Tone.kt                  # 音色枚举（对应 res/raw 内的柔和气泡 / 脚步音色）
    MetronomeService.kt      # 前台服务（锁屏后台 + 唤醒锁 + 倒计时 + 通知操作）
  app/src/test/...           # JVM 单元测试（验证节拍精度与循环正确性）
  .toolchain/                # 本机构建工具链（JDK/Gradle/SDK）
```

## 本机（这台机器）构建
```bash
source /home/hy/DSH/Run/.toolchain/env.sh
cd /home/hy/DSH/Run/metronome
gradle assembleDebug testDebugUnitTest --no-daemon
```
- 关键：本机沙箱只允许写工作目录，因此把 `HOME`/`GRADLE_USER_HOME`/`ANDROID_USER_HOME`/`TMPDIR` 都重定向到 `/home/hy/DSH/Run`（见 `env.sh`），否则 Gradle/SDK 写 `$HOME` 会被拒绝。
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`；正式签名版 `gradle assembleRelease`，产物在 `app/build/outputs/apk/release/app-release.apk`，交付命名为 `app-<versionName>-release.apk`（如 `app-1.12-release.apk`）。
- release 已开启 R8 + 资源压缩。签名口令默认走 `local.properties` 的 `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`，未配置时回退到内置默认值。

## 安装到 OPPO Find X8s
1. 推荐安装正式签名版 `app-<versionName>-release.apk`（用 `adb install` 或传到手机安装）。
2. 同签名 + 版本号递增即可**覆盖安装**；若此前装的是 debug 签名版，需先卸载再装 release 版。
3. 首次启动会请求通知权限（Android 13+），请允许，否则前台服务通知可能被隐藏。
4. 首次启动还会弹出后台保活引导（见下）。

## ColorOS 适配要点（务必在手机设置里操作）
为避免锁屏/后台被 ColorOS 杀掉、导致节拍中断：
1. 设置 → 应用管理 → 跑者节拍器 → **允许后台运行**；
2. 同一页开启 **自启动**；
3. 电池 → **无限制**（不要智能省电/限制后台）。
（App 内的首次启动引导会提示这三步。）

## 音频实现要点
- 节拍不是"逐拍触发播放"，而是把节拍点**烘焙进一段长 PCM**（默认 10 秒、整数拍长度），再用 `AudioTrack(MODE_STREAM)` + 常驻写入线程把 PCM 首尾相接连续喂给音轨：循环无缝、间隔绝对均匀、零调度抖动。
- 之所以不用 `MODE_STATIC`：它需要一次性分配整段 PCM 的共享缓冲，Android 上存在约 1MB 的实现上限（部分设备更低），10 秒循环即 960KB，实测在该限制边缘会创建失败；流式写入没有这个限制。
- 循环末尾的音效尾部**环绕写入开头**，避免"最后一拍被丢弃"造成的空拍（BPM ≥ 174 时旧实现每轮少一拍）。
- 所有音色先做峰值归一化再统一增益，保证响度一致且放大后不削波。
- 试听 / 报时 / 到点提示走 `SoundPool`，与节拍同一媒体通道，响度观感一致。

## 验证清单（真机）
- 锁屏后台节拍是否持续
- 与 QQ音乐 / 喜马拉雅 同时播放、互不打断
- 蓝牙耳机下听感 / 是否仍混音
- 倒计时到点是否响铃并弹出结束通知
- 运行中拖动步频滑块、切换音色、调音量、改倒计时是否即时生效
- 步频 170/180/200 与外部标准节拍器是否吻合（180 时不应出现每 10 秒一次的空拍）
