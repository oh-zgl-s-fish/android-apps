# 录音转写 RecSTT

把手机里的录音批量送到自建的语音转写服务，然后边听边读、整理并导出字幕的 Android 应用。

录音（包括通话录音、会议录音）散落在本机各个目录里，格式和命名五花八门。这个应用负责扫描入库、按文件名提取时间与号码等元数据、排队送转写，最后在阅读器里把文字和音频对齐。

## 功能

- **扫描本机录音**：遍历本机录音目录，读取时长、大小、修改时间后入库。
- **元数据提取**：用可编辑的模板把文件名解析成时间、号码、类型等字段，解析不出来时明确标注来源（文件名 / 回退时间戳 / 修改时间）。
- **批量转写**：任务队列 + 前台服务，逐个上传、轮询进度，失败必报错而不是静默停在半路。
- **边听边读**：播放器与转写正文联动，当前句整行高亮并自动滚动跟随。
- **整理与导出**：按模板重排文案，导出 SRT 到 `Download/Transcribe`。
- **两条线路**：局域网地址与隧道地址两条，用健康检查实测决定走哪条；都不通时报真实错误，不悄悄换地址。
- **外观**：玻璃质感、自定义背景，跟随系统深色。

## 服务端

应用需要自备 HTTP 服务端（whisper 风格的 v2 接口：`/v2/health`、`/v2/jobs`、任务状态与结果查询）。地址在设置页填写，默认值是占位地址，开箱需要改成你自己的服务器。

仓库不含隧道二进制：启用「隧道中转」需要把 `libwireproxy.so`（arm64-v8a）放到 `native/arm64-v8a/`，否则该功能不可用，其余功能不受影响。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug
```

发布签名不随仓库分发：只有提供 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四个环境变量时，`assembleRelease` 才会签出可安装包，否则产物是未签名 APK。

也可以用不依赖 Gradle 的手工流水线（aapt2 → javac → d8 → zipalign → apksigner）：`./build-legacy.sh`，需要 `ANDROID_SDK_ROOT`、`JAVA_HOME`、`RELEASE_STORE_PASSWORD`。

SDK 路径写在 `local.properties`（`sdk.dir=...`）或用 `ANDROID_HOME` 指定；该文件不入库。

## 工程结构

```
src/com/zglinus/recstt/
  model/   录音、分段、模板
  tpl/     模板引擎：人话格式 ↔ 正则
  store/   SQLite（rec / seg / kv / tpl）
  scan/    本机目录遍历与入库
  net/     转写服务客户端、线路选择
  svc/     前台转写服务与通知
  ui/      列表 / 阅读 / 模板 / 外观 / 设置
tools/     开发期用的测试脚手架，不参与打包
```

纯 framework Java 实现（minSdk 29），不依赖 AndroidX 或第三方 UI 库。

## 权限

网络、前台服务、通知、读取音频（含 Android 10 及以下的外部存储权限）。扫描全盘录音目录需要「所有文件访问」权限；不授权时列表为空并提示去开启。

## 许可

GNU General Public License v3.0，见仓库根目录的 [LICENSE](../LICENSE)。
