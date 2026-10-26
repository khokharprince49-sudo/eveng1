# G1 Text Bridge (Python/Kivy)

A small independent Android companion-app prototype for entering plain text on a phone and sending it to Even Realities G1 glasses over Bluetooth LE.

## What it does

- Python/Kivy screen for entering text.
- Scans for the two G1 lens devices and connects over the public Nordic UART-style BLE service.
- Sends text protocol packets to both lenses and provides a clear-display action.
- Sends periodic heartbeat packets while the app is open.

## Important status

This project was assembled in a workspace without the Android SDK/NDK, Buildozer/python-for-android, or network access to install them. **No APK has been built or tested in this workspace.** The app is an initial source implementation and has not yet been tested against actual glasses. Bluetooth device names and protocol behavior can vary by firmware; if a send doesn't display, the protocol/connection flow needs hardware testing and adjustment.

The phone should have the glasses paired in the official Even G1 app before testing. Close the official app before connecting from this app, since multiple Bluetooth clients may compete for the connection.

## Build on a Linux machine with internet access

Install Python 3, Java 17, Buildozer and its OS build dependencies (see the Buildozer documentation), then from this directory run:

```bash
python3 -m pip install --user buildozer
buildozer android debug
```

First build downloads Android SDK/NDK and Python-for-Android dependencies. On success, the APK is usually produced under `bin/`.

If your Buildozer version doesn't copy Java files from `android.add_src`, add `android_src/com/example/g1text/G1BluetoothBridge.java` to the generated Android app's Java source tree (package `com.example.g1text`) using the custom-source option for your Buildozer/p4a version.

## Notes

- This is an unofficial community project and is not affiliated with Even Realities.
- Bluetooth permissions are needed at runtime.
- The exact G1 text packet has been derived from public protocol notes and a public BSD-2-Clause community implementation, not copied from the official app. The transport is a small Android Java bridge, while the UI and packet generation live in Python.
- You need to test with your specific G1 firmware before relying on it.

## References

- https://github.com/emingenc/even_realities_g1 (BSD-2-Clause; G1 BLE/text protocol implementation)
- https://github.com/AGiXT/mobile/blob/main/Even%20Realities%20G1%20BLE%20Protocol.txt (community protocol notes)
