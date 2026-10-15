# FrameCast – Android companion for the ESP32-C3 photo frame (firmware v4)

Features: screen cast, notes (text + emoji), pixel-art editor (web page), share-to-frame, auto-discovery (mDNS),
auto-send of new camera photos, home-screen widget (Prev / Next).

Phone and frame must be on the same WiFi. Panel is 128x160 portrait.

## Update the app
Upload the changed files to your GitHub repo (Add file -> Upload files, drag the `app` folder and the root files,
commit to main). The "Build APK" workflow runs automatically; download the artifact "FrameCast-apk".

## Notes
- The frame's WiFi sleeps to save battery; with firmware v4 it wakes every WIFI_AUTO_WAKE_MIN minutes
  (default 10) for ~40 s. Photo/notes/widget requests only work while it is awake (a failed auto-send retries later).
- Widget buttons work only while the frame's WiFi is awake.
- Animated GIFs are sent as they are; the frame rejects GIFs bigger than 128x160.
