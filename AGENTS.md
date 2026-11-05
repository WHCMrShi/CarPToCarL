# 开发约定

## 分支与推送

- 开发基线：`WHCMrShi/CarPToCarL` 的 `codex/audi-q4-etron`。开工前先 `git fetch` 并快进到最新。
- 完成的改动提交并推送到 `origin/codex/audi-q4-etron`。不推 `main`，不 force-push。
- 提交信息写清改了什么、验证了什么；未验证的实验内容不要混进同一个提交。

## 当前阶段（2026-11-05 起）

- 处于“工具链 + 证据”阶段：没有明确要求时**不改 app 业务代码**、不改默认配置与版本号。
- Q4 实车材料归档在 `docs/vehicle-tests/`；脱敏日志保留时间顺序与消息类型，
  但不能当作逐字节报文重放依据。
- 源码行号引用以基线 `bcef70f` 为准。

## 工具链

- 固定版本：Temurin JDK 17、Gradle 8.10.2、Android SDK Platform 34、
  Build Tools 34.0.0、Platform Tools；工程用 AGP 8.7.3 + Kotlin 2.0.21。
- 准备：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1 -AcceptAndroidLicenses`
- 构建：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-local.ps1`
- 细节与本机踩坑（JDK 目录不能含 `+`、镜像注入、SDK 镜像、wrapper 不可用）
  见 `docs/local-build-windows.md`。

## 不得入库

`.tools/`、`local.properties`、`build/`、`dist/`、`.gradle/`、`.kotlin/`、
`keystore.properties`、`*.jks` / `*.keystore`、APK、原始（未脱敏）车测日志。

## 校验与表述

- 提交前至少跑一遍 `build-local.ps1`（单元测试 + lint + Debug APK）。
- `spotlessCheck` 在当前基线有 66 个文件的既有格式问题；未获准改代码时不要
  顺手 `spotlessApply`，也不要把格式检查加回默认门禁。
- 明确区分“本机编译/单测通过”与“Q4 实车验证”。无设备时如实写明未安装、未路测。
