# 小米 11:51 导出：无线连接与 CarLife 源先就绪

2026-11-18，小米 Android 17。运行标识为 `3573bd218651-dirty`，未提交差异未知。用户反馈可正常连接使用，并推测选用了 Wi-Fi Direct，导出未记录手机端选择操作。以下按时间合并本进程重复分段，排除末尾 11 月 16 日的旧快照。

| 时间 | 消息类型与观察 |
|---|---|
| 11:46:43.316 | CARLIFE 等待本机引擎。WIFI 采样为默认蜂窝，进程尚未绑定网络。 |
| 11:46:44.338–44.724 | WIFI-MEMORY 确认目标在场，WIFI_SPECIFIER 请求耗时 382 ms。车机网络和进程绑定为 wlan0，默认蜂窝仍通过系统验证。 |
| 11:46:44.820–46.936 | CARLIFE 本机控制口接通、下发 98386（CONNECT_TYPE=5）、版本匹配，随后本地间隔结束并下发 98311。 |
| 11:46:47.044–47.107 | CARLIFE 编码参数和实际 SPS 均为 1412×654，收到首个关键帧。 |
| 11:46:48.385–50.819 | AIRPLAY 首次 RECORD 成功，MIB3 控制就绪后补发 RECORD。CARLIFE 缓存回放 21/21 帧，转实时直通；音频加入后再次 RECORD。 |
| 11:46:51.352–59.088 | HANDOVER-DIAG 在蓝牙关闭时保留视频。CARLIFE 记录触控按下、48 kHz 双声道格式和非零 PCM。 |
| 11:51:10.748–11.004 | VIDEO-PATH 累计 5136 帧、队列待发 0、累计丢弃 16；源端最近 5 秒实测 20 fps，随后仍有关键帧写出。 |

这轮覆盖车机网络绑定后接本机引擎、源先就绪的缓存交接，以及蓝牙结束后继续送帧。反向顺序见[11 月 17 日样本](../2026-11-17-q4-171917/analysis.md)。客户端本地写出和 PCM 电平不提供车机解码、触控实际响应或扬声器出声的确认。

Q4 实现的 CarLifeHijack.workerLoop 固定连接回环。上游 [main 的同一入口](https://github.com/jjuugk/CarPToCarL/blob/b7506d3/app/src/main/java/io/github/shaonialife/carptocar/carlife/CarLifeHijack.kt) 也使用回环。[switch-pool 的 P2P 连接器](https://github.com/jjuugk/CarPToCarL/blob/429a7d5/app/src/main/java/io/github/shaonialife/carptocar/net/WifiP2pCarPlayConnector.kt) 加入提供 iAP2 Wi-Fi 凭据的 CarPlay 接收端；该分支的 CarLife 仍访问本机。CONNECT_TYPE 作为特征字段在 TCP 建链后发送。

如需验证手机端 Wi-Fi Direct 入口，需将实际选择、系统 P2P 组状态变化与本机 7240、首个真实关键帧按时刻对齐，再观察退出重进或掉组后的恢复。现有网络心跳记录车机网络、默认网络和进程绑定，不能据此排除手机端另有 P2P 活动。本次未安装、未新增路测。

以 11:46:43.313 轮次 BEGIN 为统一起点，本机控制口 +1.507 s、源关键帧 +3.794 s、首次 RECORD +5.072 s、开始向车机送帧 +7.185 s。首次 RECORD 至首帧写出约 2.11 s，其中约 2.01 s 后 MIB3 控制就绪并开始补发 RECORD。接入缓存至首帧写出约 77 ms。两端建链并行，各阶段耗时不能直接相加。该样本复用了已验证的热点凭据，未测首次配对或 Wi-Fi Direct 建组耗时。
