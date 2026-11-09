# 音视频分段诊断（2026-11-09）

本项只记录耗时与计数，不调整音视频格式、发送顺序、锁、缓存预算、丢帧策略、恢复条件或协议内容。新增 `MEDIA-STAGE` 汇总，不输出负载、路线、设备地址。使用单调纳秒时钟，无新增线程、文件或逐包字符串。

- 音频每个输入块记录锁等待和包含发送的处理耗时，慢处理阈值50ms。只抽样该输入块首个实际发送包的封包/加密与UDP发送，不能将采样最大值当成所有包的最大值；原AUDIO-DIAG的全包发送统计保留。静音保活不计入此输入块采样。
- 视频每次frame/config/keepalive写操作记录锁等待及包含封包、发送的整体耗时，慢处理阈值100ms。真实frame另记录加密/封包与write+flush耗时；config/keepalive不计入这两个分段。原写超时看门狗及关闭所属socket的行为保留；替换密集的VIDEO-WRITE慢写/锁等待提示，超时错误仍保留。
- 每个发送器正常最多每30秒汇总一次；有慢操作或discontinuity时最多每5秒汇总一次。音频停止时可额外输出尾部统计。没有数据时不定时唤醒写日志。
- `workInclusiveMaxUs`包含seal/send，三者不能相加；不同最大值可能来自不同操作。锁等待不包含在work中，slow按wait+work判定。count/slow/discontinuities均为本窗口计数，输出后清零，不是整个会话累计。
- discontinuity仍执行清除不完整组帧与设置marker，RTP/nonce连续性策略不变；仅移除逐次文字，改为窗口总数。

实现中每块/帧仅更新固定计数器；音频不为其余UDP包新增时钟采样。输出字符串仅在汇总时构造。调用日志在本次诊断的媒体临界区外；已有调用方的外层锁及原日志行为不受改变。JVM限频测试不等于设备上的CPU/调度开销测量，下一轮仍需实车对照，特别关注日夜切换时的锁等待、处理、加密与网络发送分布。失败操作可能只有整体耗时，seal/send为0不能据此判断该阶段从未执行。

用户明确本次不构建APK；验证只执行单元测试与lint。构建脚本末尾可能显示已有APK路径，不代表新安装包生成。既有ingress、连续性及视频队列日志继续提供互补证据。

本机验证：`scripts/build-local.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug') -NoDaemon -Offline -GradleArguments @('--max-workers=2')` 通过；903项JVM测试，0 failures/0 errors，lint 0 errors/97 warnings（既有baseline另过滤3 errors/94 warnings）。新增限频、连续异常汇总与重置、锁等待判定回归；归档轮转测试确认MEDIA-STAGE保留。没有执行assemble/package APK，既有APK时间仍为2026-11-09 00:19:32。未安装、未实车，也未测量手机端实际CPU开销。
