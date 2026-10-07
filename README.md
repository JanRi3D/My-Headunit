# My Headunit

An Android phone app that manages an Android car head unit over ADB on the car's Wi-Fi:
installs and updates apps, manages the car's files, and runs shell commands. The ADB client
is built into the app; nothing has to be installed on the phone besides `My-Headunit.apk`.

| Car | Files | Commands |
|---|---|---|
| Connection, apps on the car, update card, Install/Update | Folder browsing, upload, download, delete over ADB SYNC | `adb shell` console with presets |

Design reference: the "ADB Car Manager – Phone App" canvas (main dark direction), renamed My Headunit.

## Build

| Tool | Version |
|---|---|
| JDK | 21 (Android Studio's bundled JBR works) |
| Gradle | 9.5.0 (wrapper) |
| Android Gradle Plugin | 9.3.3, built-in Kotlin 2.4.10 + Compose compiler plugin |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 (phone side only, see below) |
| Compose BOM | 2026.09.00 (Material 3 1.4.0) |

```bash
./gradlew :app:assembleDebug
```

The debug APK is copied to `My-Headunit.apk` in the project root. Checks:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

compileSdk is 37 only because Compose 1.12 requires it; `targetSdk` stays 36. Versions are
pinned in `gradle/libs.versions.toml`.

## Car setup

1. Enable ADB over the network on the head unit (port 5555). Depending on the unit: a
   "Wireless ADB"/"ADB over network" switch in developer options, `adb tcpip 5555` once over
   USB, or `setprop service.adb.tcp.port 5555` followed by restarting adbd (root).
   Android 11+ "Wireless debugging" with pairing codes is a different (TLS) mode and is not
   used; the app explains this if it meets such an endpoint.
2. Join the car's Wi-Fi with the phone. If Android asks, keep the connection although it has no
   internet.
3. Open My Headunit. It connects to `192.168.43.1:5555` (editable on the Car screen when not
   connected, and remembered).
4. On Android 4.2.2+ head units the car shows "Allow USB debugging?" with the phone's key
   fingerprint, which the app shows too. Tick "Always allow" so later connections need no prompt.

"Connected" is only shown after the ADB handshake and a successful `getprop` on the car.

### Car compatibility

The car only needs adbd. The client handles what older units do differently:

- 4 KiB maximum packet size and the 1.0 protocol version (Android 4.x), checksums sent always;
- no `shell_v2`: falls back to `shell:` (PTY output, CRLF) and appends a status line to get the
  real exit code; if that line is missing the console says the status is unavailable;
- SYNC v1 (`STAT`/`LIST`/`SEND`/`RECV`), which every adbd speaks;
- toolbox commands: no `grep` (output is parsed on the phone), `rm` without `-f` and one file
  at a time (Android 4.1's `rm` treats `-f` as a file name), `dumpsys`/`am`/`pm`/`monkey`
  options that exist since Android 4.0;
- ADB authentication (Android 4.2.2+) or none (4.1 and earlier).

## Connection, network routing, background

- **Identity**: a 2048-bit RSA key generated in the Android Keystore (`KeystoreKey.kt`); the
  private key never leaves it and only signs AUTH tokens. It is not backed up.
- **Routing**: the ADB socket is bound (`Network.socketFactory`) to the Wi-Fi network whose
  subnet contains the car; release checks and downloads use a validated internet network,
  requesting cellular if the default network is the car's internet-less Wi-Fi. The process is
  never bound to a network. If no Wi-Fi route matches (e.g. the phone is the hotspot), the
  socket uses normal routing.
- **States**: Disconnected, Connecting, Authorization required, Connected, Connection failed,
  Connection lost. Automatic retries back off 2 → 4 → 8 → 16 → 30 s, at most 8 attempts, and
  only while the app is visible. A Wi-Fi loss closes the connection immediately; an idle link is
  checked with `echo` after 20 s without traffic.
- **Foreground service** (`CarService`, types `connectedDevice` + `dataSync` while
  transferring): Android 15+ destroys the TCP sockets of apps that leave the foreground — this
  was observed on the Android 16 emulator 5 s after the system file picker opened. The service
  keeps the ADB link alive while the picker is open or the app is in the background, shows
  transfer progress with Cancel, and offers Disconnect. After 5 minutes in the background
  without transfers the app disconnects.

### Why an own ADB client

[dadb](https://github.com/mobile-dev-inc/dadb) (Apache-2.0) was evaluated: it supports shell,
push/pull and install, but its connection class is `internal` and it opens its own `Socket`, so
the socket can't be bound to the car's `Network` without binding the whole process; its
`shell()` always uses `shell,v2`, which Android 4–6 adbd refuses; and its key is a PKCS#8 file
rather than a Keystore key. The protocol subset needed here (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE, shell,
SYNC, plus the output parsers) is about 800 lines in `app/src/main/java/me/ri3d/adb/adb/`, tested against a fake adbd and
real adbd versions.

## The app catalog and update sources

The catalog is in `app/src/main/java/me/ri3d/adb/apps/Catalog.kt`:

| App | Package | Launch | Update source |
|---|---|---|---|
| OpenAuto | `me.ri3d.openauto` | launcher activity | `github:JanRi3D/OpenAuto`, asset `openauto-*-release.apk` |
| Welle | `me.ri3d.welle` | launcher activity | `github:JanRi3D/Welle`, asset `welle-*-release.apk` |
| FileExplorer | `com.estrongs.android.pop` | `com.estrongs.android.pop/com.estrongs.android.pop.view.FileExplorerActivity` | **not configured** |

Package IDs and asset names come from the two repositories (`applicationId` in
`app/build.gradle.kts`, the asset name in `.github/workflows/release.yml`). FileExplorer's
package and activity are the ones given in the requirements; it has no known public release
feed, so it shows "Source not configured" until you add one.

To change the catalog, edit the `ManagedApp(...)` entries (`packageName`, `name`, `source`,
`launchComponent`). Without code changes, tap an app row on the Car screen:

- **Update source**: `github:Owner/Repo` (or a `https://github.com/Owner/Repo` URL) for the
  latest non-prerelease GitHub release, or an `https://` URL of a JSON manifest. Saved per app.
- **Install APK file**: installs a local APK for that app.

JSON manifest format (single APK, or `apks` for per-ABI builds; values in an entry override the
top level):

```json
{
  "packageName": "com.estrongs.android.pop",
  "versionCode": 260,
  "versionName": "4.2.1",
  "minSdk": 16,
  "abis": ["armeabi-v7a", "x86"],
  "url": "https://example.com/fileexplorer-4.2.1.apk",
  "sha256": "…",
  "size": 6100000
}
```

If `versionCode`, `packageName` and `abis` are all present they are trusted; otherwise the app
reads them from the APK itself, as for GitHub.

### How update checks work

- Run once after each new connection (not again within 30 minutes) and on the refresh button.
  Results are cached, so the last known versions stay visible offline.
- For a GitHub asset the app reads only the APK's zip central directory and
  `AndroidManifest.xml` with HTTP range requests (a few KB) to get the package name,
  `versionCode`, `versionName`, `minSdk` and native ABIs. Nothing is downloaded until you
  confirm.
- Updates are decided by numeric `versionCode` only (`versionName` is for display). A release
  for a different package, or one that doesn't fit the car's Android version/CPU, is never
  offered as an update.
- A failed check shows "Check failed" and the reason; it never claims "Up to date".
- Updates never install by themselves: the update card's "Update on the car" or a row's
  Update/Install (with a confirmation dialog) starts download → SHA-256/size check → transfer →
  `pm install -r` → version check.

## Installing APKs

"APK from phone" uses the system file picker, copies the file into app storage, and reads it
(package, versions, minSdk, native ABIs; label and icon via the platform parser). It rejects
split bundles (`.apks/.xapk/.apkm`), single split APKs and bases that need splits, and blocks
installs that need a newer Android, another CPU, or would downgrade. Installation:

1. SYNC transfer to `/data/local/tmp/mhu-<uuid>.apk` with byte progress;
2. `pm install -r '<path>'` (keeps app data), result read from the output — the exit code of
   `pm` is not trusted (old versions exit 0 on failure);
3. the installed `versionCode` is read back with `dumpsys package`;
4. the temporary file is removed (leftovers from a lost connection are removed on the next
   connection).

Failures are explained (storage, signature conflict, downgrade, old Android, CPU, invalid APK,
missing splits, connection lost). An app is never uninstalled to resolve a failure.

"Already on the car" lists `pm list packages -3` and registers the chosen package with a name of
your choice; nothing is reinstalled.

## Files

Listing, upload and download use ADB SYNC; rename/delete use `mv`/`rm` with POSIX-quoted paths.
Uploads go to a hidden `.name.mhu-part` file that is renamed when complete, so an interrupted
upload never leaves a partial file under the real name. Downloads go to a file (one selected)
or a folder (several) chosen with the system picker; incomplete downloads are deleted and
reported. Unreadable folders are reported as such (adbd lists them as empty). Start folder:
`/sdcard`, then the last folder.

## Tests

`./gradlew :app:testDebugUnitTest` runs, without devices:

- `AdbProtocolTest` against `FakeAdbd` (a device-side ADB implementation on a loopback socket):
  legacy device with 4 KiB packets, shell v2 with stderr/exit code, signature accepted by an
  adbd-equivalent RSA check, unknown key prompt allowed/denied, TLS-only endpoint, non-ADB
  service, refused service, upload interrupted by FAIL, connection lost mid-download, key format;
- `ParsersTest`: numeric version comparison, quoting (including a round trip through a real
  POSIX shell when one is installed), `rm`/`mv` commands, `getprop`, `pm list`, `dumpsys package`
  (4.x and current), `pm install` results, foreground app (4.x and 10+), legacy exit status
  parsing, endpoints, sources, backoff;
- `ApkMetaTest`: the binary manifest of the OpenAuto 0.1.0 release, ranged zip reading,
  stored/deflated entries, split bundles, compatibility messages.

`RealCarTest` runs the same client against a real adbd (emulator or car), opt-in:

```bash
MHU_CAR=127.0.0.1:5555 MHU_APK=path/to/app.apk MHU_APK_PKG=its.package ./gradlew :app:testDebugUnitTest --tests '*RealCarTest*' --rerun
```

## Verification status

Verified on 2026-10-07 with emulators (Android 16 phone emulator running the app; the
`HeadUnit_API16` AVD, Android 4.1.2 x86, as the car at `10.0.2.2:5555`):

- connection, Wi-Fi socket binding, legacy shell with exit codes, car identity; reconnect after
  turning the phone's Wi-Fi off and on;
- update check against the real GitHub releases of OpenAuto and Welle (range reads), install of
  Welle 1.0.0 from its release with download/transfer/install progress and version check;
- APK from phone: inspection, signature-conflict refusal (`INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`)
  without uninstalling; invalid APK refusal; "Already on the car" registration;
- files: navigation, selection, download of two files through the system folder picker,
  upload (names with spaces and apostrophes, checksum compared), replace on conflict, cancel of a
  150 MB upload without leftovers, delete, permission-denied folder;
- commands: custom commands with exit status (legacy and shell v2 with stderr), Start
  FileExplorer (not installed → explained), Stop app on screen (Welle identified via
  `mCurrentFocus`, confirmed, force-stopped);
- ADB authentication with the Keystore key against the Android 16 emulator's own adbd over TCP:
  fingerprint matches the car-side dialog, Allow → connected, next connection signs without a
  prompt.

**Not verified — needs the physical head unit**: its real Wi-Fi/hotspot routing at
`192.168.43.1` with the phone's mobile data as internet, the 4 KiB packet limit on real
hardware (the emulator's adbd announced 256 KiB; 4 KiB is covered by the fake-adbd tests), the
unit's authorization dialog and key storage, transfer speed and timeouts on slow flash,
`pm install` behaviour on the unit's Android version (package verifier dialogs), the ARM
`abilist`, and FileExplorer (not installed on the emulator; needs its APK or a configured
source).

## Licenses

Geist and Geist Mono (`app/src/main/res/font`) are © The Geist Project Authors, SIL Open Font
License 1.1 (`app/src/main/assets/geist-OFL.txt`). Other dependencies are AndroidX/Kotlin
libraries under Apache-2.0.
