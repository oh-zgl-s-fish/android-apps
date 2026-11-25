# 开游 PlayCheck

按「游戏」记录每天实际玩了多久的 Android 打卡应用。

系统自带的屏幕使用时间只给一个总数，看不出今天打开过哪个游戏、玩了多久、有没有达到自己定的目标。这个应用按游戏逐条记录首开时间与每次游玩段落，汇总成当天的打卡结果。

## 功能

- **按游戏统计**：读取系统的使用情况访问记录，只统计被选中的游戏。
- **首开与段落**：记录每天第一次打开的时间，以及每一段游玩的起止；切走后不发送暂停事件的机型也能正确收段。
- **打卡日分桶**：以凌晨 04:00 为界划分「今天」，熬夜玩的那一段不会被算到第二天。
- **常驻通知**：状态栏实时显示今日打卡进度，可单独静音。
- **漏卡提醒**：22:00 检查一次，只提醒当天没达标的游戏。
- **统计页**：热力图、趋势与每日明细，累计时长与系统统计对账。
- **桌面小组件**：不开应用也能看到今日进度。
- **外观设置**：浅色/深色/跟随系统，玻璃质感与主题色。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug
```

发布签名不随仓库分发：只有提供 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四个环境变量时，`assembleRelease` 才会签出可安装包，否则产物是未签名 APK。

SDK 路径写在 `local.properties`（`sdk.dir=...`）或用 `ANDROID_HOME` 指定；该文件不入库。

## 使用前提

首次使用需要在系统设置里授予「使用情况访问」权限，否则无法读取任何统计。

## 工程结构

```
src/       源码（core / data / engine / platform / ui 分包）
res/       资源
app/       Gradle 构建脚本
```

## 许可

GNU General Public License v3.0，见仓库根目录的 [LICENSE](../LICENSE)。
