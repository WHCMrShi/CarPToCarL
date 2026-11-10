# E8连接修复关键Review后的修正

基线1035322。用户授权仅修复关键Review确认的两个问题，合并为独立修复commit；不调整Wi-Fi固定记忆、认证、音视频、分辨率、UI、等待/退避预算，不构建APK。

## 取消setup不再遗留静默门禁

原cancelSetup先结束setupWork，旧finally因此跳过BtSessionGate.sessionActive清除和重试。现在cancelBtSetup在用户/所属setup票据保护内捕获旧client及晚到端点，清currentClient、静默门禁和本轮阶段状态，再使setup票据失效并开放互斥；随后只dispose捕获的client。取消局部setup不置全局connectCancelled、不清用户sessionWanted、不结束pending邀请用户代次。这样旧配对立即退出，而新邀请、已有3/10/30秒有界重试仍可进入。已有晚到端点按原优先级接续，用户终态、旧工作、后继setup或已活动控制/视频不会被旧取消清理。

## BT事件携带建立时的原始连接票据

入站accept和出站session在发布owner前捕获BtSessionCallbacks。它保留该连接的不可恢复票据，凭据、端点、晚到端点、闭链和入站接入均沿此上下文递交。控制器在可能阻塞的Wi-Fi记忆选择之后，于状态提交处重新检查原始用户票据与BT来源；不再在旧事件终于到达时获取当前新票据。原始凭据/首次端点/晚到端点三条入口均覆盖。

输入一旦被接纳，后续Wi-Fi setup仍由其独立setup票据保护，不把BT owner存活并入attemptCancelled。因此正常handover关闭BT、同一用户轮次出现下一BT owner，不会自动杀掉已接纳的Wi-Fi工作。没有把网络IO放入全局BT owner锁，保留CarLife温源和CarPlay先连的晚到源路径。

## 回对E7与E8的适用边界

- E7热重连第一段：23:12:02.928 BT关闭，之后相同client在05.252完成TCP，06.570完成verify。当前目标Wi-Fi与近期邀请有效时仍保留一次5秒TCP前机会；本修正不因BT来源退役而结束已接纳的setup，正常TCP活跃后继续既有配对窗口。
- E7后续7000连续拒绝：若5秒仍无有效控制则释放旧setup与静默门禁，允许现有预算内重试及新邀请，避免手机端取消后自锁。但不能证明APP能让Q4主动开放端口；不增加预算、不改叫醒报文字节。两段故障分别处理，不能用第一段成功推出第二段消失。
- E8冷车：监听代次拒绝“stop后accept返回”的旧接入，原始连接票据拒绝旧回调冒用新轮。长心跳间断后的2秒事件整理及收尾复核保持。对日志中约百毫秒的靠近事件交错有覆盖，但合法新RFCOMM自身不完成iAP2协商的内部原因仍未证实，60/90秒握手上限未改；不能保证所有冷启动都不再需要车机操作。
- 两种启动顺序：没有修改CarLife缓存/停止条件、首帧和资源接管。CarLife先起保持温源；CarPlay先连仍可提示并等待真实源。新的BT来源门禁只限制输入接纳，不延伸至已接纳setup的整个生命周期。

## 测试范围

新增JVM回归使用实际BtSessionCallbacks与WorkEpoch，以屏障控制“旧检查已过→用户连接重启→旧提交继续”、同轮BT owner替换、五类事件退役及正常BT交接后setup继续。另提供ConnectionReviewInstrumentation，直接调用实际控制器取消与回调入口：立即取消、5秒窗口到期取消、旧取消不得清后继client，以及凭据/首次端点/晚到端点在记忆读取前暂停并跨连接重启后拒绝发布。它同时检查静默门禁、全局取消标志、client退役、重试排队、互斥和旧finally失效。

没有Android设备执行这些控制器测试，本轮仅编译它们；JVM辅助/回调回归通过不能冒充Android控制器运行或Q4实车验证。后续需在独立测试安装上运行设备fixtures，再分别实测冷车、热车、两种源先后和主动取消/离车边界。


本轮最终本机验证：952项JVM单测通过（0 failures/errors/skipped），lint无Error、97项既有Warning、2项Information；6个实际控制器设备fixtures编译通过，未在Android设备执行。任务仅testDebugUnitTest、lintDebug和compileDebugAndroidTestKotlin，没有构建/更新APK，未安装或实车验证。
