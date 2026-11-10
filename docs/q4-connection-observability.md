# Q4 连接收敛：观测与判读

## 目的与边界

用于逐轮回答“自动连接停在哪一阶段”“哪个恢复动作有真实收益”“是否有旧会话干扰”。本次仅补诊断，不改变报文顺序、等待窗口、重试次数、固定Wi-Fi记忆或7000兜底策略。0x4e0e提前发送仍未实验。

这些字段是本进程内的关联标识，不能跨手机进程比较；合并导出时仍按原始时间戳与整行内容去重。不能把两份重叠导出当作两次独立试验。车机手动点击时间目前不可直接观测，需要车主反馈或外部对照。

## 从哪里读起

| 日志 | 含义 |
| --- | --- |
| IAP2-FLOW session / connection / btOwner / direction | 每条BT会话的协议证据，关联原始用户连接；不是“当前最新会话”代号 |
| FLOW-DIAG SETUP_ACCEPTED connection / setup / inviteGeneration | 每次交接尝试的起点，附带来源、是否有凭据/地址、端口来源、CarLife当时是否已推流 |
| IAP2-FLOW PHASE | 链路就绪等待、识别、认证、设备状态、可选HID、Wi-Fi、可用性与保活阶段 |
| IAP2-FLOW TX_SUBMITTED | CSM已交给本地链路处理；可能因窗口满排队，不证明已经写出、对端ACK或接受 |
| IAP2-FLOW RX_PARSED | 本地解析到该消息；不代表已经采用。0x4301的暂存、晚到转交与无凭据忽略另有DECISION |
| FLOW-DIAG BT_INPUT_IGNORED | 原连接或BT owner已失效的回调，明确保留它自己的归属 |
| FLOW-DIAG CTRL_INVITE | 收到邀请时的目标匹配、交接、控制连接、视频与网络状态；不是服务可用确认 |
| FLOW-DIAG CTRL_WAIT_RESULT / CTRL_WAIT_SKIPPED | 区分新邀请、旧计数入口、mDNS放行、端点完整跳过、超时继续、取消及中断。waitResult只是等待函数返回值，false超时仍可能按既有逻辑继续 |
| ENDPOINT-DIAG / CONNECT-DIAG | 原有端口选择、TCP结果，AirPlay关键记录追加同一connection/setup关联 |
| FLOW-DIAG SETUP_EXIT | 当前尝试结束时控制/视频/源状态；已退役时省略继任者状态，避免误归因 |

## 链路快照不能过度解释

关键发送及会话小结包含linkState、peerFrames、peerAck、engineTxFrames、lastTxSeq、streamWrites及alive。

- engineTxFrames是引擎产生的帧数；streamWrites是本地流write成功返回的次数，一次write可包含多帧。不能相减推算丢帧。
- peerAck是最后看到的有效ACK字段，序号会回绕；它是整条链路的快照，不能自动当作当前0x4300或0x4e0e的确认。
- streamWrites增加也不证明车机的应用层接受，更不证明画面解码。
- RX 0xaa03只说明收到签名响应；本项目沿用原有不验签行为，不能把该日志当作密码学验证成功。
- phase1-returned只说明握手函数返回，后续仍要看端点、TCP、pair-verify、SETUP和实际画面证据。
- CTRL-DIAG response-written只证明本地write/flush完成，不能证明车机收到或开放了AirPlay服务。

## 如何评估每个fallback

先按connection/setup/btOwner整理正常步骤和前置证据，再记录fallback触发时缺少什么，以及触发后第一个新证据何时出现。不能只按最终“连上了”认定fallback有效。

优先对照：TX 0x4e0d/0x4e0e/0x5702/0x4300，RX 0x5703/0x4301；同一尝试的网络绑定、ctrl公告结果、mDNS首次发现、邀请收到/实际应答、端口选择和TCP结果。注意“重新公告请求”可能被冷却限制；公告成功不证明车机发现了手机。

尚无端点就进入默认端口、没有新邀请就超时继续、控制连接建立前BT结束等路径均保留原行为，通过日志识别其触发与效果。CarLife先起和CarPlay先起分别核对，不能合并为同一实验。

## 日志量与隐私

新增协议明细仅限16种建链消息，每个方向/消息号最多3条；其后仅累计计数。阶段最多10条、决策最多12条；正常一次BT生命周期输出Phase1和保活结束两个汇总。不会为音视频包、高频触摸数据增加逐包明细。非关键发送不构建链路快照。

新增字段不记录SSID、密码、MAC、证书、公钥、挑战或签名内容。这里仅描述新增诊断，不宣称既有原始SessionTrace已脱敏；原始车测文件仍不得入库。

## 本机验证

972项JVM单测通过（0 failures/errors/skipped）；lint无Error，97项既有Warning及2项Information；设备测试源码编译通过。执行testDebugUnitTest、lintDebug、compileDebugAndroidTestKotlin，无设备执行或实车验证，未执行APK打包任务。旧APK最后修改时间仍为2026-11-10 08:15:18。

新增回归覆盖消息观测顺序、原始归属、重复日志上限及汇总计数、logger异常隔离、旧回调不交付，以及mock配件完整握手中的关键消息/链路字段与敏感字段缺席；没有用日志测试替代Q4实车验证。
