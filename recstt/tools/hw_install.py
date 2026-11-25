#!/usr/bin/env python3
"""华为/鸿蒙手机上装 APK：自动过华为的安装确认链，把"必须人来点"的那一关明确报出来。

背景（EBG-AN10 / HarmonyOS 4.2 / Android 12 实测）：
`adb install` 在这台机器上不是静默的，会依次弹三关：

  1. com.android.packageinstaller/.InstallStaging
     「风险提示 … 来历不明的应用很可能损害您的设备和个人数据」→ [取消] [继续安装]
  2. com.huawei.appmarket/…InstallDistActivity
     「安全提示 / 应用备案状态未知」→ 勾选「已了解此应用未经检测…」→ [继续安装]
  3. com.huawei.coauthservice/…UnifiedAuthenticationDialogActivity
     「请输入锁屏密码继续安装」← 这一关脚本过不去，必须人输密码

本脚本自动点掉 1、2，遇到 3 就停下来告诉你怎么继续。
退出码：0=装上 3=停在输密码 4=超时 1=安装失败

用法：
  tools/hw_install.py recstt.apk            # 自动点前两关
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ANDROID_SERIAL", "")
if not SERIAL:
    sys.exit("请用 ANDROID_SERIAL=<host:port|序列号> 指定设备，例如 adb devices 里的那一行")
TIMEOUT = int(os.environ.get("HW_INSTALL_TIMEOUT", "180"))
DUMP = "/sdcard/_hw_install.xml"
LOCAL = "/tmp/_hw_install.xml"


def adb(*args, timeout=60):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def ui():
    adb("shell", "uiautomator", "dump", DUMP)
    r = adb("pull", DUMP, LOCAL)
    if r.returncode != 0:
        return None
    try:
        return ET.parse(LOCAL).getroot()
    except Exception:
        return None


def center(node):
    nums = [int(x) for x in re.findall(r"\d+", node.get("bounds", "[0,0][0,0]"))]
    return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)


def nodes(root):
    return list(root.iter("node")) if root is not None else []


def has_text(root, needle):
    return any(needle in (n.get("text") or "") for n in nodes(root))


def find_button(root, label):
    """找可点击、已启用的按钮/文字（华为有些"按钮"其实是 TextView）。"""
    for n in nodes(root):
        txt = (n.get("text") or "").strip()
        if txt == label and n.get("enabled") == "true" and n.get("clickable") == "true":
            return n
    for n in nodes(root):
        if (n.get("text") or "").strip() == label and n.get("enabled") == "true":
            return n
    return None


def find_unchecked_box(root):
    for n in nodes(root):
        cls = n.get("class", "")
        if cls.endswith("CheckBox") and n.get("checked") == "false":
            return n
    return None


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print(f"找不到 APK：{apk}")
        return 2

    proc = subprocess.Popen(
        ["adb"] + (["-s", SERIAL] if SERIAL else []) + ["install", "-r", apk],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    started = time.time()
    last = ""
    while time.time() - started < TIMEOUT:
        if proc.poll() is not None and last not in ("stage3",):
            out = proc.stdout.read()
            print(out.strip())
            ok = "Success" in out
            return 0 if ok else 1

        root = ui()
        if root is None:
            time.sleep(0.5)
            continue

        if has_text(root, "锁屏密码"):
            print("\n停在第三关：华为要求「请输入锁屏密码继续安装」。")
            print("这一关脚本没有权限替你过（不是常规输入框，注入按键也进不去）。")
            print("请手动输密码；安装会在你输完后自己继续，本脚本退出。")
            return 3

        done = find_button(root, "完成") or find_button(root, "知道了")
        if done is not None and has_text(root, "安装成功"):
            tap(done)
            print("已安装。")
            return 0

        box = find_unchecked_box(root)
        if box is not None:
            print("第 2 关：勾选「已了解此应用未经检测…」")
            tap(box)
            time.sleep(0.6)
            continue

        btn = find_button(root, "继续安装")
        if btn is not None:
            print("过一关：继续安装")
            tap(btn)
            time.sleep(0.8)
            continue

        time.sleep(0.5)

    print(f"超时（{TIMEOUT}s）仍未装完，最后停在：{last}")
    return 4


if __name__ == "__main__":
    sys.exit(main())
