<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/wave-mark-dark.svg">
  <img src=".github/assets/wave-mark-light.svg" alt="Wave" width="220">
</picture>

# Wave

**A passive RF scanner for Android.**<br>
It listens to what the radios around you are already broadcasting into open air, names the
hardware behind each signal offline, and tells you when something appears to be travelling
with you.

[![Latest release](https://img.shields.io/github/v/release/IRAS-LABS/wave?label=release&color=00A97F)](https://github.com/IRAS-LABS/wave/releases/latest)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-00A97F)](#install)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-00A97F)](LICENSE)
[![Build](https://github.com/IRAS-LABS/wave/actions/workflows/build.yml/badge.svg)](https://github.com/IRAS-LABS/wave/actions/workflows/build.yml)

[Install](#install) · [What it hears](#what-it-listens-to) · [What it refuses to do](#what-it-does-not-do) · [Build from source](#build-from-source)

</div>

---

> **It never transmits.** It does not connect, pair, associate, probe, deauthenticate,
> inject, jam, or attempt to decrypt anything. Everything it knows, it learned by
> listening.

**Read [AUTHORIZATION.md](AUTHORIZATION.md) before you use it.** It is the scope
document — what this tool is for, and the things it deliberately refuses to do.

---

## Install

**[Download the latest APK](https://github.com/IRAS-LABS/wave/releases/latest)** —
`wave-1.0.2.apk`, 21 MB.

Requires **Android 10 (API 29) or newer**. There is no Play Store listing; sideload it.

1. Download the APK to the phone.
2. Open it. Android will ask permission to install from this source — grant it, then
   revoke it afterwards if you prefer.
3. Launch Wave. It shows the scope terms first, then asks for the permissions it needs.
   Location has to be granted for Android to return *any* Wi-Fi or Bluetooth scan result;
   that is a platform rule, not a choice this app made.

### Verify what you downloaded

Releases are signed with the project key. Check the certificate rather than trusting the
file:

```
apksigner verify --print-certs wave-1.0.2.apk
```

```
Signer #1 certificate DN: CN=Wave, OU=IRAS Labs, O=IRAS Labs, C=US
Signer #1 certificate SHA-256 digest: 84551b7f73b1bdaf5f32c6e7826e81b7f18722b5205be1ac91123790a1dc603e
```

The certificate digest is the thing that matters, and it does not change between
releases. The file hash does — this one is for `wave-1.0.2.apk`:

```
SHA-256  365c48bdd39fd9555d02aebbd60571e33cab089d2d504d34588f173e671a12cb
```

If the certificate digest does not match, do not install it — regardless of where you got
the file.

### Upgrading

A build you compiled yourself is signed with a different key than the release, so Android
will refuse to install one over the other (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
Uninstall first. Uninstalling clears the local database, so export anything you want to
keep from the Data tab beforehand.

---

## What it listens to

| Band | Source | Needs |
|---|---|---|
| **BLE** | advertisements, manufacturer data, service UUIDs | phone |
| **Wi-Fi** | APs, beacons, vendor information elements | phone |
| **Classic Bluetooth** | inquiry responses, class-of-device, SDP | phone |
| **Cellular** | GSM / WCDMA / LTE / NR serving and neighbour cells | phone |
| **Sub-GHz** | TPMS at 315 / 433.92 MHz and other bursts | RTL-SDR or HackRF |

The SDR lane speaks `rtl_tcp` over loopback, so a driver app owns the USB device and Wave
needs no root and no per-dongle quirk handling. See [RADIO-SETUP.md](RADIO-SETUP.md).

## What it does with them

- **Identifies.** MAC prefix to manufacturer, Bluetooth SIG company IDs, payload
  signatures for known trackers and surveillance hardware. All lookups run against
  databases inside the APK — nothing about a device you see is sent anywhere to name it.
- **Alerts.** When a device has stayed with you across enough separate positions to rule
  out coincidence, when known equipment comes into range, and on cell-network anomalies
  that can indicate a site simulator. Severity threshold and categories are yours to set.
- **Records.** Sightings, with the position *you* were at when you heard them, in a local
  database. Exportable as CSV, JSON or KML.

## What it does not do

These are scope decisions, not gaps. The full reasoning is in
[AUTHORIZATION.md](AUTHORIZATION.md).

- No movement history of anything you do not own
- No bearing, direction finding or "hotter/colder" targeting
- No routing around detected law enforcement
- No cross-session profiling of strangers

It is also **not an IMSI-catcher detector**. The signals that would positively identify
one live in the baseband, and Android's public API exposes none of them. Wave reports
cell-network anomalies and says exactly that much.

## Where the data goes

Nowhere. No account, no telemetry, no crash reporting, no analytics. Nothing Wave detects
is ever sent anywhere — every lookup that names a device runs against databases inside
the APK.

Two network calls exist and both are triggered by you. These are every remote host the
app can reach:

| Host | When | Carries |
|---|---|---|
| `tile.openstreetmap.org` | you open the map | the tile coordinates you are looking at |
| `overpass-api.de` | you run the camera-map import | a bounding box you chose |
| `overpass.kumi.systems` | as above, if the first is down | as above |

Cleartext is off for every one of them. It is permitted for `127.0.0.1` and `localhost`
only, because the SDR bridge is a raw TCP socket on loopback that never leaves the phone
— see [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml),
which says so in its own comment.

Cloud backup and device-to-device transfer are both excluded explicitly. Release builds
also drop the `version-control-info` block Android normally embeds, so the shipped binary
carries nothing about the machine that built it.

## Permissions, and why

Every permission in the manifest, not a selection. Two of them look alarming for an app
that claims never to transmit, so they are explained rather than buried.

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Android gates all Wi-Fi and Bluetooth scan results behind it, and the trail needs a position |
| `ACCESS_COARSE_LOCATION` | paired with the above; the system requires both to be requested together |
| `ACCESS_BACKGROUND_LOCATION` | scanning continues while the screen is off |
| `ACCESS_WIFI_STATE` | read scan results |
| **`CHANGE_WIFI_STATE`** | **`WifiManager.startScan()` requires it.** It is the permission that asks the radio to listen; nothing here turns Wi-Fi on or off, joins a network, or sends a frame |
| `NEARBY_WIFI_DEVICES` | the Android 13+ replacement for using location as a Wi-Fi scanning gate |
| `BLUETOOTH_SCAN` | passive BLE and classic discovery |
| **`BLUETOOTH_CONNECT`** | **required to read a device *name*, nothing more.** Android 12 put `getName()` behind this permission. Wave never pairs, bonds or opens a connection |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | the pre-Android-12 equivalents, capped at `maxSdkVersion="30"` so newer phones are not asked twice |
| `READ_PHONE_STATE` | cell identity for the anomaly checks |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | the scan runs as a visible foreground service, not in secret |
| `POST_NOTIFICATIONS` | the foreground-service notification, and alerts |
| `INTERNET` | the loopback `rtl_tcp` socket, plus the three hosts listed above |

`WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` were declared up to and including 1.0.2 and
never used — no wake lock is acquired anywhere and there is no boot receiver. They have
been removed from the source and will be absent from the next release. If you audit the
1.0.2 APK you will still find them declared; that is the reason.

---

## Build from source

```
git clone https://github.com/IRAS-LABS/wave.git
cd wave
./gradlew assembleDebug
```

Windows: use `gradlew.bat` in place of `./gradlew`. The APK lands at
`app/build/outputs/apk/debug/app-debug.apk`; install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The first build downloads Gradle and the dependency graph and takes a few minutes. Later
builds are fast, apart from release builds, where R8 does real work.

<details>
<summary><b>Prerequisites, tests, release builds and multi-profile installs</b></summary>

### Prerequisites

| | |
|---|---|
| JDK | **17** (Temurin, or the JBR bundled with Android Studio). Not 21 — the build targets 17. |
| Android SDK | platform **34**, build-tools **35.0.0** |
| Gradle | none needed — the wrapper pins **8.7** and downloads it |

Android Studio (Koala or newer) supplies all three; open the project folder and it will
prompt for anything missing. Everything here also works from a plain command line with
only the SDK command-line tools installed.

### Point the build at your SDK

Either export `ANDROID_HOME`, or create a `local.properties` in the repository root:

```
sdk.dir=/path/to/Android/Sdk
```

That file is gitignored — it is a machine-local path and does not belong in the
repository.

### Tests

```
./gradlew test
```

Unit tests only; they cover signature matching and tracker-payload parsing and need no
device.

### Release build

A release build is **unsigned** unless you supply your own signing material. There is
none in this repository and none in its history.

Generate a key:

```
keytool -genkeypair -v -keystore app/my-release.jks -alias wave \
  -keyalg RSA -keysize 4096 -validity 10950
```

Create `keystore.properties` in the repository root:

```
storeFile=app/my-release.jks
storePassword=...
keyAlias=wave
keyPassword=...
```

Then:

```
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Both `keystore.properties` and
`*.jks` are gitignored — keep them that way.

Release builds run R8 with resource shrinking, which matters more here than usual:
`material-icons-extended` alone is about 30 MB of dex and Wave uses seven icons from it.
Shrinking takes the APK from roughly 62 MB down to 21 MB. Expect a minute or two.

If `keystore.properties` is absent the build still succeeds and produces
`app-release-unsigned.apk`, which is the correct behaviour for anyone who is not
publishing releases.

### Installing to a phone with multiple profiles

`tools/install.sh` installs to every attached device, owner profile only. On phones with
work profiles, vendor app-cloning or secure folders, a bare `adb install` sprays the
package into all of them and leaves duplicate launcher icons.

</details>

---

## Licence

Apache-2.0 — see [LICENSE](LICENSE). Bundled reference datasets and their origins are
listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

**Security issues:** please report privately rather than opening a public issue.
