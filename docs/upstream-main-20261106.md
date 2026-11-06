# 上游 main 合并记录（2026-11-06）

## 固定基线

- Q4 分支：`240fdc940be56f3132cc336ce0818d15dd423768`。
- 上游：`jjuugk/CarPToCarL` 的 `main`，`1f676fa09c695078e9b07bc5fe643867365b56d5`（1.2.24）。
- 共同祖先：`20f7b31f12f4fe802140a124b3ed42524620a2e4`。
- 使用保留双方历史的 merge；目标分支仍是 `codex/audi-q4-etron`。

## 接入内容与冲突处理

1. iAP2 TCP 隧道输出按链路帧拆成 comm 包。未知包头、脏长度或残帧整段退回旧发送方式；写入失败停止后续发送。现有 VirtualIap2Link 调用由链路锁串行化，加密计数器与 socket 写入仍受同一写锁保护。此变更只作用于 TCP 隧道，控制通道 POST /command 与旧事件通道未改变。
2. 无线 AP 解析优先按参数 ID 识别信道和安全类型，保留 0 基、1 基与兜底路径。
3. 解析显示 features / primaryInputDevice 并写入诊断日志；不据此修改选屏或触摸路由。上游 features 注释与标签表存在矛盾，不能将标签解读当作已经过 Q4 实测的输入能力结论。
4. 版本采用上游 1.2.24，保留本分支 `-q4-debug` 后缀及构建配置。
5. 上游 RTSP dump 与本分支有等价功能，RtspDump.kt 出现 add/add 冲突；保留本分支完整实现，避免退回同步日志与重复记录。SessionController / AirPlayClient 的既有 RTSP 调用未重复添加。

Q4 默认 1412×654、48 kHz PCM 优先及兼容重试、批量异步日志、音频和视频转发优化均保持原实现。未顺带修改媒体进度、封面、语音或 EA 状态机。

## 明确保留的行为差异

上游通过 rebase 删除了 `7a2243a`，不是追加 revert，因此普通 merge 不会撤回我们已继承的 Wi-Fi 存活检查。上游 README 报告该提交涉及“车机取消连接后再次连接卡在加入”，但这不是本分支 Q4 日志已经证实的结论。

本次保留当前 Q4 网络代码，不把上游历史删除隐式等同于适用于 Q4 的重连修复，也不声称已经解决其报告的重连问题。直接撤回会重新允许使用系统已不承认的 Network，原先的 ENONET 风险需要一起评估。后续应采集取消连接→再次连接、热点消失→恢复的专项日志，核对 Network、SSID、回调释放和会话状态后再单独调整。上游 README 中“移除 7a2243a”描述的是上游发行版，不能套用于此 Q4 合并版。

## 验证

- `scripts/build-local.ps1 -NoDaemon -Offline -GradleArguments '--max-workers=2'`：执行单元测试、lintDebug 和 assembleDebug。
- 单元测试 464 项，0 失败、0 错误、0 跳过；包括上游新增 17 项分帧、无线参数和显示元数据测试，以及本分支原有 447 项测试。
- lintDebug 通过（0 Error、109 Warning、2 Information）；assembleDebug 通过。构建保留已有编译警告，不据通过结果声称无警告。
- 未执行全仓格式重排；基线的历史格式问题不属于本次范围。
- 本地验证不等于实车验证。本次上游协议变化及取消后重连尚未安装到 Q4 验证。
