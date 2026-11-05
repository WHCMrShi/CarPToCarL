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

在项目根目录用 PowerShell 执行：

```powershell
./scripts/build-local.ps1
```

默认执行格式检查、Debug 单元测试、lint 和 APK 编译。只编译 APK：

```powershell
./scripts/build-local.ps1 -Tasks ':app:assembleDebug'
```

脚本优先使用 `.tools` 中的 Gradle，否则使用 wrapper。
脚本仅为当前命令设置 JAVA_HOME、ANDROID_HOME 与 GRADLE_USER_HOME，结束时恢复。
若不使用脚本，也可自行配置 JDK 17 和 Android SDK 后运行 `gradlew.bat`。

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
