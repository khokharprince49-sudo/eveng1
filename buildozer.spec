[app]
title = G1 Text Bridge
package.name = g1textbridge
package.domain = org.openai
source.dir = .
source.include_exts = py,kv,txt,md,java
version = 0.1.0
requirements = python3,kivy,pyjnius
orientation = portrait
fullscreen = 0
android.permissions = BLUETOOTH,BLUETOOTH_ADMIN,BLUETOOTH_SCAN,BLUETOOTH_CONNECT,ACCESS_FINE_LOCATION
android.api = 35
android.minapi = 26
android.ndk = 27c
android.accept_sdk_license = True
android.add_src = android_src
android.enable_androidx = True

[buildozer]
log_level = 2
warn_on_root = 1
