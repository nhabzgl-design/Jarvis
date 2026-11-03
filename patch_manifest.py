# Nilalagay ang mga permission, queries at ang ZeroService sa AndroidManifest.xml (para sa always-on build)
import sys
import xml.dom.minidom

path = sys.argv[1]
s = open(path, encoding="utf-8").read()
if "ZeroService" in s:
    print("manifest already patched")
    sys.exit(0)
perms = ["RECORD_AUDIO", "INTERNET", "ACCESS_FINE_LOCATION", "MODIFY_AUDIO_SETTINGS", "POST_NOTIFICATIONS",
         "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "WAKE_LOCK", "SYSTEM_ALERT_WINDOW",
         "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"]
extra = "".join('<uses-permission android:name="android.permission.%s"/>' % p for p in perms)
extra += '<uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>'
extra += ('<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>'
          '<intent><action android:name="android.speech.RecognitionService"/></intent></queries>')
svc = ('<service android:name=".ZeroService" android:enabled="true" android:exported="false" '
       'android:foregroundServiceType="microphone" android:stopWithTask="false"/>')
assert "<application" in s and "</application>" in s, "walang <application> sa manifest"
s = s.replace("<application", extra + "<application", 1)
s = s.replace("</application>", svc + "</application>", 1)
xml.dom.minidom.parseString(s.encode("utf-8"))  # siguraduhing valid pa ang XML
open(path, "w", encoding="utf-8").write(s)
print("manifest patched")
