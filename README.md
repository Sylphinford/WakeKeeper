# WakeKeeper

一个极简的 Android 工具：两个互相独立的开关，分别让 CPU 保持运行、让屏幕保持常亮。

*Keep your phone awake on your own terms — two independent switches, one for the CPU, one for the screen.*

- **包名**：`io.github.sylphinford.wakekeeper`
- **技术栈**：Kotlin · AndroidX · Material 3（View 体系，未使用 Compose）
- **兼容性**：Android 8.0（API 26）及以上，targetSdk 35
- **不联网**：不收集任何数据，不含统计与广告

## 功能

### 保持唤醒

启动前台服务持有一把 `PowerManager.PARTIAL_WAKE_LOCK`：屏幕照常熄灭，CPU 继续运行，后台任务（下载、同步、导航记录等）不会被深度休眠打断。主界面实时显示已保持的时长。

### 保持屏幕常亮

用一个 1×1 像素的透明悬浮窗承载 `FLAG_KEEP_SCREEN_ON`。窗口位于所有应用之上，因此切换到任何应用时屏幕都不会自动熄灭。

这种方式不改动系统熄屏时间，开关关闭即刻失效，不会留下持久的系统设置变更。需要「显示在其他应用上层」权限。

> 「保持屏幕常亮」指不自动熄屏，而非点亮屏幕。屏幕已经熄灭时，应用无法主动点亮它。

### 常驻通知

服务运行期间显示一条低优先级通知，内容随当前状态变化，并提供对应的关闭按钮：

| 当前状态 | 通知按钮 |
| --- | --- |
| 仅保持唤醒 | 关闭保持唤醒 |
| 仅屏幕常亮 | 关闭屏幕常亮 |
| 两者都开启 | 关闭屏幕常亮 · 关闭保持唤醒 · 全部关闭 |

两个开关都关闭后服务自动停止，通知随之消失。

## 安装

从 [Releases](../../releases/latest) 下载最新 APK 安装即可，无需 root，无需 Xposed。

也可以自行编译，见[构建](#构建)。

## 权限

| 权限 | 用途 |
| --- | --- |
| `WAKE_LOCK` | 持有唤醒锁，普通权限，安装时自动授予 |
| `FOREGROUND_SERVICE`<br>`FOREGROUND_SERVICE_SPECIAL_USE` | Android 14 起，长时间持有唤醒锁需运行在声明了类型的前台服务中 |
| `POST_NOTIFICATIONS` | Android 13 起显示常驻通知，首次开启开关时请求 |
| `SYSTEM_ALERT_WINDOW` | 屏幕常亮所需的悬浮窗，需在系统设置中手动授予 |

## 兼容性

部分定制 ROM（MIUI / HyperOS、ColorOS、OriginOS 等）会对后台服务与悬浮窗施加额外限制，可能需要手动放行：

- 允许应用自启动与后台运行；
- 电池优化设为「无限制」或「不优化」；
- 部分 ROM 除系统级的悬浮窗总开关外，还有独立的「后台弹出界面」开关；
- 在最近任务列表中锁定本应用。

这些属于系统层面的策略，应用本身无法绕过。

## 构建

需要 JDK 17、Android SDK Platform 35 与 Build-Tools 35.0.0。

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

SDK 路径写在 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 签名

`app/build.gradle.kts` 会在项目根目录存在 `keystore.properties` 时启用 release 签名：

```properties
storeFile=keystore/wakekeeper.jks
storePassword=<store password>
keyAlias=wakekeeper
keyPassword=<key password>
```

`keystore.properties` 与 `*.jks` 均在 `.gitignore` 中。自行构建时可用以下命令生成自己的密钥：

```bash
keytool -genkeypair -v -keystore keystore/wakekeeper.jks -alias wakekeeper \
  -keyalg RSA -keysize 2048 -validity 10000
```

缺少 `keystore.properties` 时 release 变体仍可编译，但产物未签名，无法直接安装。

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/io/github/sylphinford/wakekeeper/
│   ├── MainActivity.kt          # 单页界面：开关、状态环、计时、权限流程
│   └── WakeKeeperService.kt     # 前台服务：唤醒锁、悬浮窗、通知按钮
├── assets/
│   └── third_party_licenses.txt # 第三方许可证全文（生成物）
└── res/
    ├── layout/                  # 主界面与许可弹窗
    ├── drawable/                # 渐变背景、状态环、通知与图标前景
    ├── mipmap-anydpi-v26/       # 自适应图标
    ├── mipmap-anydpi-v33/       # 主题化图标
    ├── values/                  # 颜色、主题、中文字符串
    └── values-en/               # 英文字符串

tools/
├── generate_notices.py          # 生成第三方许可清单与内置资产
└── licenses/Apache-2.0.txt      # Apache-2.0 全文
```

## 设计说明

- **状态单一来源**：用户意图存于 SharedPreferences（`keep_awake` / `keep_screen`），服务启动时据此对齐实际设备状态；界面读取进程内的实际持有状态，因此开关显示与真实生效情况一致。
- **通知与服务共用同一入口**：三个按钮对应 `ACTION_STOP_SCREEN` / `ACTION_STOP_AWAKE` / `ACTION_STOP_ALL`，服务处理后更新状态并刷新通知。
- **`START_STICKY`**：服务被系统回收后可重建，并从 SharedPreferences 恢复原有状态。
- **悬浮窗权限实时校验**：每次对齐状态都会重新检查 `Settings.canDrawOverlays()`，权限被撤销时同步关闭对应开关。

## 许可证

源码采用 [MIT 许可证](LICENSE) © 2026 Sylphinford。

### 第三方组件

运行时共 64 个第三方组件（4 个直接依赖 + 60 个传递依赖），全部为 Apache-2.0，不含 copyleft 许可。完整清单与许可证全文见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

为满足 Apache-2.0 第 4(a) 条，许可证全文同时打包进 APK，可从应用底部的「开源许可」入口离线查看。构建期工具链（Gradle、AGP、Kotlin 编译器、AAPT2、D8/R8）不进入产物，不构成分发义务。

依赖变更后可重新生成清单：

```bash
python tools/generate_notices.py
```

脚本从 `releaseRuntimeClasspath` 的实际解析结果读取各模块 POM 中的许可证信息，同时更新 `THIRD-PARTY-NOTICES.md` 与 `app/src/main/assets/third_party_licenses.txt`。遇到未声明许可证的模块会报错退出，要求在脚本的 `OVERRIDES` 中显式登记。
