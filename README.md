# Android Apps

三个自用的 Android 应用，源码放在同一个仓库里，各自独立构建。

| 项目 | 应用 | 做什么 |
|---|---|---|
| [`installment-book/`](installment-book/) | 分期簿 | 记录信用卡分期，逐期明细、利息年化、按月甘特图 |
| [`playcheck/`](playcheck/) | 开游 | 按游戏记录每天玩了多久，做打卡统计与漏卡提醒 |
| [`recstt/`](recstt/) | 录音转写 | 扫描本机录音，送自建服务转写，边听边读并导出 SRT |

每个目录都是独立的 Gradle 工程，自带 README 说明功能与结构。

## 共同前提

- JDK 17
- Android SDK，compileSdk 36，minSdk 29
- SDK 路径写在各自目录的 `local.properties`（`sdk.dir=...`）或用 `ANDROID_HOME` 指定

## 构建

```bash
cd installment-book && ./gradlew assembleDebug
cd playcheck        && ./gradlew assembleDebug
cd recstt           && ./gradlew assembleDebug
```

发布签名不随仓库分发：只有提供 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四个环境变量时，`assembleRelease` 才会签出可安装包，否则产物是未签名 APK。

## 数据与隐私

三个应用都把数据留在本机：

- 分期簿的账本存在本机 JSON 存档；AI 助手与 MCP 只读服务默认关闭，启用时才联网。
- 开游只读取系统使用情况记录，结果存在本机。
- 录音转写只与你自己的转写服务通信，服务地址由使用者填写。

仓库中不含任何密钥、签名文件或真实账单数据；示例账本里的卡号尾号与金额都是占位值。

## 许可

GNU General Public License v3.0，见 [LICENSE](LICENSE)。
