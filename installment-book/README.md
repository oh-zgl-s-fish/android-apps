# 分期簿 (Installment Book)

一个记录信用卡分期的本机账本 Android 应用。

把每张信用卡上的分期拆到「逐期」：每月要还多少、还剩几期、利息花了多少，一眼能看到；明细拆得开，汇总和明细的账对得上。

## 功能

- **多卡、多分期并存**：一张卡可以同时挂多个分期项目，互不干扰。
- **逐期明细**：每期金额、还款日、剩余期数；改期数或还款方式时整表重排，本息总额守恒。
- **利息与年化**：支持等额本息与等本等息两种口径，给出每期利息与综合年化。
- **汇总页**：待还金额、已付利息、剩余期数；占比环形图，以及按月排列分期区间的自绘甘特图（历史与未来以斜纹/实色区分，今天线按日期比例绘制）。
- **账户与收入**：记录储蓄账户与月收入，用来估算还款余力。
- **AI 助手**：可接入任意兼容 OpenAI `chat/completions` 的接口，基于账本里的真实数据回答，不编造数字。
- **MCP 只读服务**：可选开启，对外提供 JSON-RPC 的只读查询；默认只监听本机，需要时才放开到局域网。
- **数据只存在本机**：落盘为本机 JSON 存档，不上传、不联网同步。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug
./gradlew :core:test      # 计算层的单元测试
```

发布签名不随仓库分发：只有提供 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四个环境变量时，`assembleRelease` 才会签出可安装包，否则产物是未签名 APK。

SDK 路径写在 `local.properties`（`sdk.dir=...`）或用 `ANDROID_HOME` 指定；该文件不入库。

## 工程结构

```
app/                入口与清单
core/               数据模型与计算（纯 Kotlin，含单元测试）
ui/                 主题、自绘控件（环形图/趋势/甘特图）、本地存储、MCP 服务
feature/dashboard/  首页
feature/plans/      分期列表、详情、甘特图、月度
feature/newplan/    新建分期
feature/ai/         AI 助手
feature/accounts/   银行卡与账户
feature/profile/    设置与收入
```

依赖方向：`app → feature:* → ui → core`，feature 之间互不依赖。

## 权限

只申请网络权限（`INTERNET`、`ACCESS_NETWORK_STATE`），用于可选的 AI 接口与 MCP 服务；不启用时不联网。

## 许可

GNU General Public License v3.0，见仓库根目录的 [LICENSE](../LICENSE)。
