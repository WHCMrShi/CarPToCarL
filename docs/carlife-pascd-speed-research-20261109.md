# PASCD → CarLife 独立车速：公开材料核验

2026-11-09，只研究，未修改业务代码或构建。当前源码72adfdd；车测输入仍为48c7899的17:21日志。

## 来源与可信度

公开镜像qiyunjzz/carlifeLIB固定fdf8310bf1b6f0c4c247d848793832c016607561，包含百度署名《百度CarLife车机手机互联方案接入详细指南new.pdf》（174页）、《CarLife动态链接库使用说明.docx》及车机SDK源码。它不是目前官方开发门户发布的最新SDK，不能保证适用OPPO Car+。原PDF仅下载到忽略目录，使用PDFBox提取文本；不在本仓库复制原文/源码。

- [指南](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/百度CarLife车机手机互联方案接入详细指南new.pdf)：第12章，车速定义12.1.1.1（打印页144），单位km/h，时间戳可选、单位ms；车辆模块与订阅见后续小节。
- [速度protobuf](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/carlifelibsourcecode/trunkbin/protobuf/proto/CarlifeCarSpeedProto.proto)：此较旧文件只有required int32 speed=1；指南及另一MD实现增加optional uint64 timeStamp=2。不可因旧文件缺字段就断言新端不支持。
- [SDK封包实现](https://github.com/qiyunjzz/carlifeLIB/blob/fdf8310bf1b6f0c4c247d848793832c016607561/carlifLib/carlifelibsourcecode/trunkbin/core/CTranRecvPackageProcess.cc)：sendCmdCarVelocity使用0x1800F并写Cmd通道；cmdCarDataGear及订阅回调同属Cmd。
- [MD实现](https://github.com/Doris-duo/CarLife-MD-Lib/tree/b3cc871f7c5c4b41947a3c512caee76624eaffd9)：README称移植自ApolloAuto/apollo-DuerOS，该上游地址本次不可访问，故不能称已核验现行官方源码。
- [PC模拟器](https://github.com/MeshHun/carlife_pc_tool/blob/main/core/car_data_sender.py)与[常量](https://github.com/MeshHun/carlife_pc_tool/blob/main/protocol/constants.py)：独立速度98319，发Channel7/9440；send_speed注释误写98322，实际调用常量为98319。与老SDK的Cmd通道有差异，不能照搬为本机Car+的事实。
- [PASCD发送方实现说明](https://github.com/Boring206/DiPlay-T8/blob/t8-api27/docs/BYD_NAVIGATION.md)：支持速度m/s、挡位和采样时间的解释，但非本车字段的官方确认。

## 已确认的协议能力

| 信息 | HU→MD消息 | 字段/单位 | PASCD可提供范围 |
| --- | --- | --- | --- |
| 独立车速 | 0x1800F，98319，CarlifeCarSpeed | speed=1，整数km/h；可选timeStamp=2，ms | 本车末尾数值是合理候选速度，须验证m/s与仪表对照 |
| 挡位 | 0x18029，98345，CarlifeGearInfo | gear=1；N/P/D/L/R=1/2/3/4/5 | 本车可见D；不是航向角，也不能认为所有端均支持五种挡位 |
| GPS | 0x18010，98320 | 含位置及速度等 | 本车样本无坐标，不应伪造位置 |

指南另说明挡位用于标示倒车及功能启停，不应仅当作无副作用的导航传感器。本阶段优先速度，挡位待独立验证。

车辆数据订阅与HUD订阅不同：手机0x10031请求→车机0x18032答复→手机0x10033开始/0x10034停止。车辆模块GPS=0、VELOCITY=1、GYROSCOPE=2、ACCELERATION=3、GEAR=4、OIL=5。字段和请求列表形式需按目标协议版本核验，频率为Hz；不可把HUD模块0/1当作车辆模块，也不可无条件宣称具有GPS/陀螺仪。

CarLife没有在这些公开定义中直接接收原始PASCD字符串的入口；可行方向是适配语义后的protobuf。独立速度不需要经纬度，和现有GPS的有效坐标门禁应分开。禁止通过(0,0)或手机旧坐标补成假车机GPS。

## 对当前项目与实车的判断

VirtualIap2Link持续接收0xFFFB；NmeaLocation.parsePascd仅识别无位置样本、不提取速度或挡位；SessionController.onCarLocation因hasPosition=false跳过GPS发送。CarLifeProtocol/HuClient没有独立速度消息及车辆订阅处理；9440虽然列入连接端口，不能据此声称已在其上发送车速。

去重17:21日志未找到0x10031/33/34车辆订阅；HUD订阅成功并不能证明车辆订阅可用。日志为有界归档，没看到请求不等于证明Car+永远不请求。公开SDK证明协议表达能力，不证明OPPO Car+内部采用，更不证明高德地图采用；之前车速同步来源仍不能追溯确认。

## 建议分阶段验证

1. 先加入低频PASCD有效性/样本数/最新样本年龄/候选速度观测，校验checksum、有限数字、样本数量、时间单调性和换链失效；与实际仪表速度对照。不得把缺失样本变成0车速，不外推停驶。
2. 记录车辆订阅请求及模块/频率，核验Car+会话是否请求VELOCITY。若订阅正常，仅报告确实拥有的能力并按请求响应。上游PASCD约1Hz，不能凭重复上次值冒充10Hz新样本；时间戳不能直接把车机单调秒数当Unix毫秒。
3. 明确授权后加入可关闭的独立速度实验，不改变GPS门禁；对Cmd与Data传输分开做单变量验证，不能同时双发或因有service ID就猜其通道。默认先依据目标版本及接入指南选择，保留不支持时恢复手机定位的行为。
4. 本地写成功只代表发出；消费验证需Car+/地图日志、测试端接收或严格A/B。可先停车台架注入受控值确认输入能力，明确禁止测试数据混入行驶会话。最终观察定位来源、速度刷新/延迟及断链后过期处理，不能只以偶然接近仪表速度判断成功。

结论：独立速度适配具有明确公开协议依据，值得实验；原始PASCD直通、航向/加速度消费、Car+与高德实际融合都尚未证明。本轮没有开始发送新消息。
