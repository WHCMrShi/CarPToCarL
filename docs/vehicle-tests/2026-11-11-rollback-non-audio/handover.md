# 2026-11-11 回退与非音频修复交接

分支：`WHCMrShi/CarPToCarL` 的 `codex/audi-q4-etron`。本次按用户要求，先撤销 `44e66eb` 至 `2fe2802` 的全部 19 个提交，再重新实现重要的非音频缺口。没有继续 AAC 实验，没有修改版本号或默认配置。以下源码按提交和符号定位，不将新行号当作约定的 `bcef70f` 行号。

## 接手时先看这里

- 回退提交：`073bd2aac568`。这是一次聚合 revert，保留原始历史；回退完成时整棵树与 `44e66eb^`（`489d74b`）一致，已用 Git 比较验证。
- 新代码提交：`5bb7f7c`（热点接入、网络观察、失败提示）、`5f672ce`（蓝牙取消与代理生命周期）、`857a329`（独立崩溃证据与导出）。文档另行提交。
- 音频、AirPlay、CarLife、界面资源与 `489d74b` 相同；`SessionController` 只重新改了网络接入、生命周期观察和失败提示，`MainActivity` 只改了权限核对。本次没有重建 AAC 档位或恢复按钮。
- **回到旧实现不等于音频故障已经解决。** `44e66eb` 之前已有的音频代码及其回退行为仍然存在，不能把当前版本描述成“严格 PCM / 完全没有 AAC”。
- 本地单测、lint、APK 构建通过；没有安装到手机，没有 Q4 路测。最终 APK 的提交号、SHA-256、签名核验在交付包的 `build-info.txt`，不把 APK 或签名密钥提交到 Git。

## 对原提交的审查及处置

| 原提交 | 内容与处置 |
| --- | --- |
| `44e66eb` | 优化评审文档，撤销；不能把待验证推断当作修复依据 |
| `b889a82`、`9abc828`、`602f9ec`、`7c241b9` | 音频档位、帧处理、重采样及 44.1K 实验全部撤销，不挑回 |
| `d97112f`、`d9edaea` | AAC 隔离与手动/自动恢复全部撤销；本地 sender 隔离无法保证接收端不拆整个会话 |
| `cd26795`、`c4d3f7c`、`880281c` | 热点在场门禁、身份观察、旧 suggestion 清理的需求保留，但代码撤销后重新实现；迁移成功必须查询核验，观察缓存必须防迟到回调 |
| `9c45af9` | 崩溃留证需求保留，重新实现同步持久化、并发落盘顺序和系统处理器委托 |
| `b430271` | 诊断需求按范围重写：只补非音频标签和网络观察，不恢复 AAC 探针代码 |
| `e9bf486`、`0a2bb2f`、`5271024`、`4ab600c`、`2a8d4e2`、`81e4fa7`、`2fe2802` | 文档及归档一并撤销，历史仍可查阅；本文件重新记录当前代码、关键证据及限制 |

原恢复方案的主要错误是把“本地对象没有替换”当成“车机旧流安全”。实际两次恢复都在已有会话中重新 SETUP，随后出现 400 和其他通道断开。这个反例足以撤下该保证，但还不足以证明车机究竟拒绝哪个字段，也不能据此直接换成 TEARDOWN 重试。原诊断文档发现了蓝牙中断逃逸，却没有修复；这次将该具体缺口单独落实。

## 重新实现的非音频行为

### 热点接入、身份与提示

- `WifiCarPlayConnector.prepareLocalNetwork`：入网/复用前清理本包旧 `WifiNetworkSuggestion`，同时检查返回状态和剩余条目。失败可重试，但不放行本次本地入网；确认成功后，同一 connector 生命周期内不反复清理活跃网络。所有注册新 suggestion 的入口已删除。不会删除用户自行保存的网络。
- `VehicleNetworkPolicy`：旧密码不代表车机在场。缓存预连接等待当前目标的 ACL、就绪 iAP2 对端或精确 SSID 已关联证据；等待有固定上限，检查取消、目标切换、新凭据及会话代次。入场后不要求蓝牙持续连接，避免正常 WiFi 交接被蓝牙断开误杀。
- `LiveWifiIdentity`：带位置权限的被动能力回调，按实际 Network 和观察代次保存 SSID；丢失/隐藏/停止观察都会失效。它只帮助判断是否复用，不直接授权绑定。绑定仍走同一个有序能力/链路属性回调，核对目标 SSID，或基线已有的当前目标邀请＋精确本地接口证明。禁止用旧 IP 猜网络身份。
- `MainActivity`：支持的 Android 版本均核对精确位置权限和位置服务；13+ 仍核对附近 WiFi 权限。用途是读取 SSID，不读取手机位置坐标。覆盖升级后首次启动可能需要补授权。
- `WifiLinkWatchdog`：以 Network 为键更新接口信息，onLost 真正删除旧值，拒绝旧观察者回调；分别记录 `activeNetwork` 观察、进程绑定、INTERNET 和 VALIDATED。它们不是其他 App 实际联网的完整证明。
- 连续连接失败时，`phaseHint` 提示“连接 CarPlay 服务连续失败，可在车机上主动连接”。沿用现有有界叫醒和重试，不谎称检测到了“必须手动连接”。
- 不新增自建 WiFi 选择页。系统是否显示授权/选择 UI 仍取决于设备状态与 ROM，不能保证永远没有系统提示。

### 蓝牙取消与代理生命周期

`BtProfiles.proxy` 的 `CountDownLatch.await` 中断现在恢复线程中断标记并结束本次获取，连接循环和守卫也检查中断。主线程不发起阻塞获取。`ProfileProxyLease` 负责超时、取消及迟到结果的释放；旧 listener 只能移除自己拥有的缓存条目，不能删掉新 owner。并发获取时保留先成功安装的代理并关闭多余代理。

这修复了已知 `InterruptedException` 逃逸路径，不改变 HFP/A2DP 策略或媒体编码。没有通过吞掉所有未捕获异常来假装不闪退。

### 崩溃证据与导出

`FatalErrorStore` 保存时间、构建号、线程和完整异常链，内容上限 64K 字符；临时文件同步到磁盘后原子替换，文件名沿用 `last_uncaught_exception.txt` 以兼容旧证据。多个线程同时崩溃时，后来的线程等待第一份证据写完再委托系统处理，避免另一线程提前结束进程。磁盘失败仍交回系统，不能保证磁盘损坏或 native 崩溃也有 Java 栈。

`LogExporter` 即使只导出当前会话，也附独立异常文件及最近三条系统进程退出摘要；明确标注可能来自旧进程。历史异常不自动删除，多份导出里同一时间的异常只计一次。旧失败快照也标注旧会话边界。未实现 native tombstone/ANR trace 自动采集，不能将这些摘要当成全类型崩溃捕获器。

## 四份新日志保留的证据与未解问题

测试代码为 `81e4fa7035dd`，不是本次重写版。原日志留在第一台电脑桌面，未入库。重新核对 SHA-256 与历史归档一致：

| 文件后缀（均为 CarPToCarL-log-20261110-） | SHA-256 |
| --- | --- |
| `231213.txt` | `260b8b812407036e4cbd76237abf63b08bb8740d6455ee99b4e34ef0685d185a` |
| `232642.txt` | `4332d5f79ff9e32f8893d4ad34108fc0ceded1ee6bb840eeb9825985405212ad` |
| `235020.txt` | `7589b875afc70ba04e4a822ab2b6d1097efca831318ac8f433cce4032b5322d7` |
| `235114.txt` | `06331fb6e26e81fe561245daf1941fdfe933be84a4123731471dcf716b43f6d5` |

- **日志事实：**23:07:57 自动 AAC 恢复、23:09:51 手动恢复均遭 SETUP 400，随后控制/视频等断开。本次撤销该实验；不能将两次都写成“手动操作”。
- **日志事实：**23:19:13 `iap2-bt-client` 在 `BtProfiles.proxy` 等待代理时抛出 `InterruptedException`，系统退出 reason=4。后三份导出附的是同一历史异常。与 23:09 的手动操作之间缺连续证据，不能说两者紧接发生。
- **用户反馈：**两档 AAC 卡顿、导航/后台/长播后失声、后台连接无声，PCM 也更卡。日志确有音视频队列丢块、长墙钟停顿与发送样本覆盖不足；没有调度 trace 和精确操作标记，尚不能唯一归因编码器、后台限制、加密或网络。这里没有因回退就宣布已解决。
- **日志事实：**23:05:20 旧 suggestion 清理返回成功，23:06:26、23:07:21 仍出现未验证 wlan 默认网络观察。清理 suggestion 不是根治所有联网问题的充分条件；需要同时记录系统选择原因和其他 App 联网情况。
- **日志事实：**23:45:34 至 23:46:49 有 38 次 7000 连接拒绝，之后 mDNS/邀请出现，23:46:51 TCP 连通。用户反馈车机主动点连接才成功，但点击时间未知。新 UI 提示针对连续失败，不断言唯一原因。
- **启动先后：**23:05:22 CarLife 先握手，23:05:29 CarPlay TCP；另一轮 23:06:07 CarPlay RECORD 先于 23:06:26 CarLife；23:46:51 CarPlay 先于 23:47:04 CarLife。两条路径均存在。
- **日志事实：**23:50:00 CarLife 再次握手、03 秒收到首帧，同轮 CarPlay 已在 01 秒 EOF，后续清理停止附着源。不能据此宣称 Car+ 封锁；23:51 导出只有新进程头部约两秒，不能补造后续。
- **用户反馈：**未出现 WiFi 选择界面。保留为反馈，不把日志中的请求调用当作实际弹窗。

详细脱敏选段仍可从历史提交取出阅读，例如：

```powershell
git show 2fe2802:docs/vehicle-tests/2026-11-10-late-aac-failures/analysis.md
git show 2fe2802:docs/vehicle-tests/2026-11-10-late-aac-failures/crash-sanitized.log
```

同目录还有四份 `*-sanitized.log` 与 `input-manifest.json`。它们是历史证据，不是当前实现说明，也不是逐字节报文重放输入。若明天要重新深挖音频，需私下复制四份原日志到另一台电脑；不要加入 Git。

## 本机验证和明天的验收顺序

本机运行 `build-local.ps1 -NoDaemon`：175 个套件、987 项 JVM 单测，失败/错误/跳过均为 0；lint 为 0 errors、97 warnings、2 information；Debug APK 构建通过。没有运行会修改全仓格式的 spotlessApply。新增测试覆盖固定在场等待上限、取消、旧观察代次失效、迁移失败重试、代理迟到/旧 owner 清理、中断标记、崩溃并发顺序、异常链重启后可读与大小限制；诊断轮转测试增加非音频标签。它们不模拟整个 Android 网络栈或 Q4 接收端。最终构建信息见交付包记录。

1. 用本次 APK 覆盖升级测试；先确认日志构建号。检查旧 suggestion 迁移日志 `remaining=0 ready=true`，再用其他 App 验证蜂窝联网；不只看本 App 的 `defaultValidated`。
2. 家里仅有缓存凭据时，不应凭密码触发车机入网；到车旁再检查目标在场、SSID 可读和取消后不复活。记录是否出现系统 WiFi 页。
3. 前台连接/后台连接分别记录操作时刻。分别做 CarLife 先启动、CarPlay 先连接；区分 CarLife 尚未握手、握手后没有首帧、被 CarPlay 清理停止三个阶段。
4. 连接中取消/断开再连，检查蓝牙中断不成为未捕获异常。若真的崩溃，重启导出当前会话，核对异常时间和构建号，不把旧异常重计。
5. 当前音频基线先做同场景对照：本地音乐、导航前后、前后台切换、暂停恢复和连续播放。记录首次可听/失声时间。不要恢复已撤销的同会话重复 SETUP 按钮来试错。
6. 音频下一步是独立调查输入/排队/锁等待/codec/加密/UDP 的时间与调度，建立丢失样本和接收端时钟的证据。先验证接收端生命周期，再提出新恢复实验；不能只增加缓冲、重试次数或换采样率宣称修复。

## 换电脑继续

已有仓库先确认没有未提交工作，再执行：

```powershell
git fetch origin
git switch codex/audi-q4-etron
git merge --ff-only origin/codex/audi-q4-etron
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1 -AcceptAndroidLicenses
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-local.ps1 -NoDaemon
```

新电脑首次拉取可用 `git clone --branch codex/audi-q4-etron https://github.com/WHCMrShi/CarPToCarL.git`，再进入目录执行准备/构建。工具链固定 JDK 17 / Gradle 8.10.2 / SDK 34 / Build Tools 34.0.0，Windows 细节见 [本机构建说明](../../local-build-windows.md)。不要将 `.tools/`、APK、原日志或密钥提交。

APK 是 Debug 签名，applicationId 为 `io.github.shaonialife.carptocar`，versionCode `10231`、versionName `1.2.31-q4-debug`。用户已确认两台电脑共用同一份 keystore，无需再次迁移签名；继续使用该密钥构建，交付记录保留最终 APK 的签名核验。最终 APK 与交付记录在第一台电脑的 `dist/`，**不会随 Git 自动到另一台电脑**，需要另行复制。

后续继续在本分支提交、推送，不 force-push；文档与业务修复分开提交。每次实车新证据继续更新 [交互模型](../../q4-carplay-interaction-model.md)。
