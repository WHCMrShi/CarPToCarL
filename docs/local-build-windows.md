# Windows 本地构建

基线：`codex/audi-q4-etron` @ `bcef70f`（2026-11-05）。
本机环境已验证：Windows 11、Temurin JDK 17.0.20.1+1、Gradle 8.10.2、
Android SDK Platform 34、Build Tools 34.0.0、Platform Tools 37.0.1；
工程使用 AGP 8.7.3 + Kotlin 2.0.21。

## 一键准备

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1 -AcceptAndroidLicenses
```

脚本按以下顺序准备 `.tools/`（已被 `.gitignore` 排除，不入库）：

1. 已有组件：逐个校验，缺什么补什么。
2. 共享工具链：自动探测同级 `../CarPToCarL/.tools`，用 Windows 目录 junction 链接
   `jdk17`、`gradle-8.10.2`、`android-sdk`，不复制大文件。也可用 `-SharedToolsPath` 指定。
3. 下载安装（全部固定 SHA-256 校验）：
   - JDK 17：清华 TUNA 的 Adoptium 镜像，失败回退 GitHub Release；
   - Gradle 8.10.2：`mirrors.cloud.tencent.com`；
   - Android SDK：腾讯云 `AndroidSDK` 镜像，通过 `SDK_TEST_BASE_URL` 让 `sdkmanager` 走镜像。

其它参数：`-Offline` 只用本地已有工具链；`-NoSharedTools` 忽略自动探测的共享目录。

准备完成后会写入两处**本机配置**（都不入库）：

- `local.properties`：`sdk.dir` 指向 `.tools/android-sdk`；
- `.tools/init-mirror.gradle`：阿里云 Maven 镜像注入，供构建脚本自动 `-I` 加载。

## 构建与校验

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-local.ps1
```

默认依次执行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
只编译 APK：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-local.ps1 -Tasks ':app:assembleDebug'
```

其它选项：`-Offline`（加 `--offline`）、`-GradleArguments '--stacktrace'`、
`-IncludeFormatCheck`（在任务最前面加 `spotlessCheck`）、`-NoDaemon`。
自动化/捕获输出的场景请加 `-NoDaemon`：新建的 Gradle daemon 会继承调用方的 stdout
管道，父进程可能一直等不到 EOF（构建其实已完成）。

脚本只为本条命令设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_USER_HOME`、`GRADLE_USER_HOME`，
结束后恢复。依赖缓存优先级：显式 `GRADLE_USER_HOME` → `.tools/gradle-user-home` → `%USERPROFILE%\.gradle`。

### 本机实测结果（2026-11-05，`bcef70f`，未改动业务代码）

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 通过：76 个测试套件 / 447 个用例 / 0 失败 |
| `:app:lintDebug` | 通过 |
| `:app:assembleDebug` | 通过，`app-debug.apk` 约 15.1 MB |
| `spotlessCheck` | **失败**：66 个文件存在历史格式问题（基线既有，本次未改代码，故未修复） |

另做了一次**从零安装**验证：在隔离目录运行 `setup-local.ps1 -NoSharedTools
-AcceptAndroidLicenses`，JDK/Gradle/SDK 全部经镜像下载并按 SHA-256 校验，
随后 `assembleDebug` 成功 —— 说明脚本不是只对本机现成工具链有效。

`spotlessCheck` 不在默认任务里，避免工具链一跑就红。需要格式门禁时显式加
`-IncludeFormatCheck`；在获准改代码后先 `spotlessApply`，再把它并回默认任务。
**不要**在“只搭工具链”的任务里顺手跑 `spotlessApply`，那会产生大量无关改动。

### APK 与签名

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
交付副本放忽略目录 `dist/`，APK 不提交进 Git。Debug 使用本机 Android 调试证书；
仓库不含发布私钥，`keystore.properties` 缺失时 release 也回退 debug 签名。
证书与密码绝不入库。

## 本机踩过的坑

1. **Gradle wrapper 不可用**：`services.gradle.org` 在本机超时，wrapper 无法下载发行版。
   构建脚本因此优先用 `.tools/gradle-8.10.2/bin/gradle.bat`；wrapper 只作后备。
2. **JDK 目录名不能含 `+`**：`jdk-17.0.20.1+1` 会让 Kotlin 编译器报
   `Unexpected FirClassLikeSymbol null` 的 ICE。`setup-local.ps1` 统一安装为
   `.tools/jdk17`；`build-local.ps1` 遇到含 `+` 的目录会直接报错提示改名。
3. **镜像注入必须移除官方仓库**：`settings.gradle.kts` 里 `google()` 排在最前，
   直连超时时 Gradle 不会回退到下一个仓库。`.tools/init-mirror.gradle`
   先移除 `Google/MavenRepo` 再只放阿里云镜像；该文件属于本机配置，不入库。
4. **SDK 下载走镜像**：`dl.google.com` 直连超时；`sdkmanager` 通过
   `SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/` 读取
   `repository2-1.xml` 并下载安装包。首次安装需 `-AcceptAndroidLicenses`。
5. **执行策略**：部分机器 PowerShell 禁止运行脚本（`Restricted`），
   用上面的 `-ExecutionPolicy Bypass -File` 形式调用，不必改全局策略。
6. **代理/证书**：若公司代理拦截 TLS，只在本机 Gradle 配置里加
   `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT`、
   `systemProp.javax.net.ssl.trustStore=NONE`，不要写进仓库。

## 验证边界

- 本机 `adb devices` 为空：本次只验证了编译、单元测试与 lint，
  **没有**安装 APK，也没有 Q4 实车回归。
- 实车日志与分析见 `docs/vehicle-tests/2026-11-05-q4/`；
  构建通过不等于车机行为已验证。
- 日志、APK、`.tools/`、`dist/`、`local.properties`、签名材料均不入库。
