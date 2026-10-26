"""Встраивает нативный код (Bluetooth, виджет, плитка) в сгенерированный Android-проект Capacitor."""
import os, re, shutil
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "android", "app", "src", "main")
PKG_DIR = os.path.join(APP, "java", "com", "vova", "budsair")

os.makedirs(PKG_DIR, exist_ok=True)
for f in os.listdir(os.path.join(ROOT, "native", "java", "com", "vova", "budsair")):
    shutil.copy(os.path.join(ROOT, "native", "java", "com", "vova", "budsair", f), os.path.join(PKG_DIR, f))
    print("java:", f)
for sub in ("layout", "xml", "drawable"):
    src = os.path.join(ROOT, "native", "res", sub)
    dst = os.path.join(APP, "res", sub)
    os.makedirs(dst, exist_ok=True)
    for f in os.listdir(src):
        shutil.copy(os.path.join(src, f), os.path.join(dst, f))
        print("res:", sub, f)

mf_path = os.path.join(APP, "AndroidManifest.xml")
mf = open(mf_path, encoding="utf-8").read()
perms = """
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
"""
if "BLUETOOTH_CONNECT" not in mf:
    mf = mf.replace("<application", perms + "\n    <application", 1)
comps = """
        <receiver android:name=".BudsWidget" android:exported="false" android:label="Buds Air">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/buds_widget_info" />
        </receiver>
        <receiver android:name=".BudsAutoConnect" android:exported="true">
            <intent-filter>
                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
                <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
                <action android:name="android.bluetooth.adapter.action.STATE_CHANGED" />
            </intent-filter>
        </receiver>
        <service android:name=".BudsTile" android:exported="true" android:label="Наушники"
            android:icon="@drawable/ic_buds_tile"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
"""
if "BudsWidget" not in mf:
    mf = mf.replace("</application>", comps + "    </application>", 1)
open(mf_path, "w", encoding="utf-8").write(mf)
print("manifest patched")
