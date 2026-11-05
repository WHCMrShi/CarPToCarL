# Windows 本地构建

项目使用 JDK 17、Gradle 8.10.2、Android SDK Platform 34；构建工具通过 SDK Manager 安装。
共享配置不绑定开发者电脑路径，Gradle wrapper 使用官方发行源与 SHA-256 校验。

## 本项目本地目录

- `.tools/jdk-17*/`：Temurin JDK 17。
- `.tools/gradle-8.10.2/`：已校验的 Gradle 发行版，可避免 wrapper 重复下载。
- `.tools/android-sdk/`：Android SDK，包含 `platforms;android-34`、`build-tools;34.0.0`、`platform-tools`。
- `.tools/gradle-user-home/`：依赖缓存与仅用于本机的代理/证书配置。

这些文件已被 `.gitignore` 排除。工具下载来源：
[Adoptium](https://adoptium.net/temurin/releases/?version=17)、
[Android Developers](https://developer.android.com/studio#command-line-tools-only)、
[Gradle](https://services.gradle.org/distributions/)。
下载后按官方校验值验证，SDK 许可通过 SDK Manager 接受。

## 编译与检查

首次安装（需要联网，`-AcceptAndroidLicenses` 表示接受 Android SDK 许可）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/setup-local.ps1 -AcceptAndroidLicenses
```

安装脚本从 Adoptium 和 Google 官方来源获取工具及校验值，校验下载包后解压。
已具备 JDK 17、SDK 34、Build Tools 34.0.0 和 adb 时可直接复用。
若 Windows 证书吊销服务离线，可显式增加 `-RevocationBestEffort`；仍验证证书链与下载校验值。
Gradle 由现有本地发行版或带 SHA-256 校验的 wrapper 提供。
系统禁止执行 PowerShell 脚本时，构建也可使用上述进程级 `-ExecutionPolicy Bypass -File scripts/build-local.ps1` 方式。

在项目根目录用 PowerShell 执行：

```powershell
./scripts/build-local.ps1
```

默认执行格式检查、Debug 单元测试、lint 和 APK 编译。只编译 APK：

```powershell
./scripts/build-local.ps1 -Tasks ':app:assembleDebug'
```

脚本优先使用 `.tools` 中的 Gradle，否则使用 wrapper。
脚本仅为当前命令设置 JAVA_HOME、ANDROID_HOME、ANDROID_USER_HOME 与 GRADLE_USER_HOME，结束时恢复。
若不使用脚本，也可自行配置 JDK 17 和 Android SDK 后运行 `gradlew.bat`。

需要独立 Gradle 进程，或希望单项检查失败后继续收集其他检查结果：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-local.ps1 -NoDaemon -GradleArguments --continue
```

本机依赖源配置保存在 `.tools/gradle-user-home/init.d/`；Gradle 会自动加载该目录中的初始化脚本。
使用镜像时需同时配置插件源与依赖源，不能只在不可达的官方源后追加镜像。

## APK 与签名

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
交付副本与校验信息放在忽略目录 `dist/`，APK 不提交进 Git。
Debug 使用本机 Android 调试证书；没有原安装包的签名私钥时，无法保证覆盖安装。
证书应保留用于后续测试更新，不能提交进 Git。Release 未配置发布密钥时也会回退到 debug 签名。

## 公司代理

代理与证书配置仅写入 `.tools/gradle-user-home/gradle.properties`，不提交本机网络信息。
若 Java 无法信任 Windows 已信任的公司代理证书，可在本机 Gradle 配置使用
`systemProp.javax.net.ssl.trustStoreType=Windows-ROOT` 和
`systemProp.javax.net.ssl.trustStore=NONE`。SDK Manager 可通过 JAVA_OPTS 设置对应 JVM 参数。
HTTPS 证书校验保持开启。

## 2026-11-05 本地验证

基于分支 `codex/audi-q4-etron` 的 `1b293e8`，在新工作目录复用本机工具及依赖缓存：
Temurin 17.0.20.1、Gradle 8.10.2、SDK Platform 34 rev 3、Build Tools 34.0.0、Platform Tools 37.0.1。
Gradle 发行包 SHA-256 与 wrapper 声明一致。本机 Maven 镜像仅位于忽略目录的 `init.d`。

| 检查 | 结果 |
| --- | --- |
| 安装脚本复用已有工具 | 通过；全新下载与安装路径尚未端到端验证 |
| `:app:testDebugUnitTest` | 447 个用例通过，0 失败、0 错误、0 跳过 |
| `:app:lintDebug` | 通过；0 errors、109 warnings，另有基线豁免的 3 errors、95 warnings |
| `:app:assembleDebug` | 通过，生成 `app/build/outputs/apk/debug/app-debug.apk` |
| `spotlessKotlinGradleCheck` | 通过 |
| `spotlessKotlinCheck` | 失败：分支原有 66 个 Kotlin 文件不符合当前格式规则 |

完整命令使用 `-NoDaemon -GradleArguments --continue`，最终退出码因上述格式问题为 1。
本次不批量格式化业务源码，不更新 lint 基线，不更改协议、UI 或版本号。
以上为本机验证，不代表 Q4 实车测试通过。
