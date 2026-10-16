# PvtDate

A lightweight, offline‑first attendance tracker for students, packaged as a real
installable Android app.

PvtDate began life as a single‑page web app and is now a signed Android
application: the UI runs inside a hardened `WebView` while a small native layer
handles downloads, backups, sharing and update checks.

| | |
|---|---|
| **Package** | `com.pvtdate.app` |
| **Version** | 1.1.1 |
| **Min Android** | 7.0 (API 24) |
| **Target Android** | 15 (API 35) |
| **Size** | ~80 KB |
| **Internet** | only for the optional weather lookup + update check |
| **Data** | stored locally on your device, never uploaded |

## Features

- Track daily attendance for **School**, **Mathematics**, **Chemistry** and **Biology**
- Mark each subject **Present / Absent / Not marked** for any date
- **Analysis** tab: weekly overview, weekly/monthly calendar heat‑view, per‑subject
  percentages and insights
- Local weather for the day (Rangpur by default) — works offline and degrades
  gracefully
- Light / dark theme
- **Backup & restore** your records to a JSON file
- **Check for updates / download the latest APK** from inside the app
- 100% offline data — nothing is sent anywhere except the weather and GitHub
  update lookups

## Install (for users)

1. Download the APK:
   - **Latest:** <https://github.com/rakib-rk09/PvtDate/releases/latest/download/pvtdate.apk>
   - or open the [Releases page](https://github.com/rakib-rk09/PvtDate/releases) and pick a version.
2. On your phone, open the downloaded `pvtdate.apk`.
3. If prompted, allow **Install unknown apps** for your browser / file manager
   (Settings → Apps → *your app* → Install unknown apps → Allow).
4. Tap **Install**, then open **PvtDate**.

The app is signed; future updates install over the top of the existing app and
keep your data. To verify the download manually:

```bash
apksigner verify --print-certs pvtdate-1.1.1-release.apk
# SHA-256: 39420458bbc5909fde9f34295635814f2da5ef98d02f7b3db093eb190564a622
```

## Repository layout

```
index.html                     the whole web UI (single source of truth)
app/
  build.sh                     build + zipalign + sign (Termux‑native, no Gradle)
  src/main/AndroidManifest.xml
  src/main/java/com/pvtdate/app/MainActivity.java
  src/main/res/                icons, theme, colors, strings
  tools/
    zipalign.py                4‑byte aligns STORED entries before signing
    check_align.py             verifies alignment
    verify_release.py          static verification of a built APK
    test_web.js                runs the real UI code under a DOM shim (QuickJS)
    gen_icons.py               regenerates the launcher icons
```

`app/src/main/assets/index.html` is **generated** by `build.sh` from the root
`index.html`, so the web app only ever lives in one place.

## Build from source (Termux)

```bash
pkg install aapt2 openjdk-17 d8 apksigner zip python quickjs-ng
# Android SDK platform 35 (android.jar) is required; set ANDROID_SDK_ROOT if not ~/android-sdk

# signing secrets live OUTSIDE the repo:
mkdir -p ~/.pvtdate
keytool -genkeypair -v -keystore ~/.pvtdate/pvtdate.keystore \
  -alias pvtdate -keyalg RSA -keysize 2048 -validity 10000
# put the keystore password (no newline) in ~/.pvtdate/keystore.pass

./app/build.sh
# -> app/output/pvtdate-1.1.1-release.apk  and  app/output/pvtdate.apk
```

Override signing without touching the repo:

```bash
PVTDATE_KEYSTORE=/path/to/app.keystore \
PVTDATE_KEYSTORE_PASS='…' \
PVTDATE_KEY_ALIAS=pvtdate \
./app/build.sh
```

## Tests & verification

```bash
# 1) run the real web app code under a DOM shim and assert its data layer
qjs --std app/tools/test_web.js index.html

# 2) static verification of a built APK (permissions, alignment, contents…)
python3 app/tools/verify_release.py app/output/pvtdate-1.1.1-release.apk index.html

# 3) signature
apksigner verify -v app/output/pvtdate-1.1.1-release.apk
```

The web test suite covers persistence, legacy‑data migration, input validation,
corrupt‑data recovery and version comparison.

## Privacy

All attendance data is stored in this app's private on‑device storage. Nothing is
uploaded. The app performs exactly two optional network requests: the daily
weather (`wttr.in`) and the GitHub releases API for update checks. Both fail
silently when offline.

## License

MIT — see [LICENSE](LICENSE).
