# 跑者节拍器（RunMetronome）

一款原生 Android 跑步节拍器 App，满足：
- 柔和耐听音效（共 4 款：气泡·柔 / 气泡·轻 / 气泡·缓 / 脚步·沉），为长时间（马拉松）设计；**点音色卡片即时试听**，无需开启节拍器
- 步频 170–200 BPM 无级调节（底层 120–220）
- 播放时不打断 QQ音乐 / 喜马拉雅 等其他音频（媒体流 + 不抢音频焦点）
- 锁屏/后台持续播放（前台服务 + 常驻通知 + PARTIAL_WAKE_LOCK）
- 可选倒计时，到点提示

目标机：OPPO Find X8s（Android 15 / ColorOS 15）。

> 音效素材：取自 BigSoundBank（CC0 公有领域）的水泡破裂与跑步脚下触地声，已提取单瞬态并转为 16bit PCM 内置。

## 目录结构
```
metronome/
  app/src/main/java/com/example/runmetronome/
    MainActivity.kt          # Compose 主界面 + 倒计时
    MetronomeEngine.kt       # 精确节拍调度（绝对时基，无漂移）
    MetronomePlayer.kt       # 音频播放（媒体流，不抢焦点→不打断其他App）
    Tone.kt                 # 音效种类（对应 res/raw 内的柔和气泡/脚步音色）
    TempoMath.kt             # 步频纯数学（BPM⟷ms，供单测精确验证）
    MetronomeService.kt      # 前台服务（锁屏后台 + 唤醒锁 + 倒计时）
  app/src/test/...           # JVM 单元测试（验证步频精度）
  .toolchain/                # 本机构建工具链（JDK/Gradle/SDK）
```

## 本机（这台机器）构建
```bash
source /home/hy/DSH/Run/.toolchain/env.sh
cd /home/hy/DSH/Run/metronome
gradle assembleDebug testDebugUnitTest --no-daemon
```
- 关键：本机沙箱只允许写工作目录，因此把 `HOME`/`GRADLE_USER_HOME`/`ANDROID_USER_HOME`/`TMPDIR` 都重定向到 `/home/hy/DSH/Run`（见 `env.sh`），否则 Gradle/SDK 写 `$HOME` 会被拒绝。
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`；正式签名版 `gradle assembleRelease`，产物在 `app/build/outputs/apk/release/app-release.apk`，交付命名为 `app-<versionName>-release.apk`（如 `app-1.3-release.apk`）。

## 安装到 OPPO Find X8s
1. 推荐安装正式签名版 `app-<versionName>-release.apk`（用 `adb install` 或传到手机安装）。
2. 同签名 + 版本号递增即可**覆盖安装**；若此前装的是 debug 签名版，需先卸载再装 release 版。
3. 首次启动会请求通知权限（Android 13+），请允许，否则前台服务通知可能被隐藏。

## ColorOS 适配要点（务必在手机设置里操作）
为避免锁屏/后台被 ColorOS 杀掉、导致节拍中断：
1. 设置 → 应用管理 → 跑者节拍器 → **允许后台运行**；
2. 同一页开启 **自启动**；
3. 电池 → **无限制**（不要智能省电/限制后台）。
（App 内的首次启动引导会提示这些。）

## 验证清单（真机）
- 锁屏后台节拍是否持续
- 与 QQ音乐 / 喜马拉雅 同时播放、互不打断
- 蓝牙耳机下听感 / 是否仍混音
- 倒计时到点是否响铃
- 步频 170/180/200 与外部标准节拍器是否吻合
