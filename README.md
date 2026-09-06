# Wave

A passive RF scanner for Android. It listens to what the radios around you are already
broadcasting into open air, names the hardware behind each signal offline, and tells you
when something appears to be travelling with you.

It never transmits. It does not connect, pair, associate, probe, deauthenticate, inject,
jam, or attempt to decrypt anything.

**Read [AUTHORIZATION.md](AUTHORIZATION.md) before you use it.** It is the scope
document — what this tool is for, and the things it deliberately refuses to do.

---

## What it listens to

| Band | Source | Needs |
|---|---|---|
| BLE | advertisements, manufacturer data, service UUIDs | phone |
| Wi-Fi | APs, beacons, vendor information elements | phone |
| Classic Bluetooth | inquiry responses, class-of-device, SDP | phone |
| Cellular | GSM / WCDMA / LTE / NR serving and neighbour cells | phone |
| Sub-GHz | TPMS at 315 / 433.92 MHz and other bursts | RTL-SDR or HackRF |

The SDR lane speaks `rtl_tcp` over loopback, so a driver app owns the USB device and Wave
needs no root and no per-dongle quirk handling. See
[RADIO-SETUP.md](RADIO-SETUP.md).

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

## Where the data goes

Nowhere. No account, no telemetry, no crash reporting, no analytics. Two network calls
exist and both are triggered by you: OpenStreetMap tiles, and the Overpass camera-map
import. Neither carries anything you have detected. Cleartext traffic is disabled, and
backup and device-to-device transfer are both excluded.

---

## Building

Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 29).

```
./gradlew assembleDebug
```

A release build is **unsigned** unless you supply your own signing material. Create a
`keystore.properties` in the repository root:

```
storeFile=app/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and `*.jks` are gitignored, and there is no signing material anywhere in
this repository or its history.

## Permissions, and why

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Android gates all Wi-Fi and Bluetooth scan results behind it, and the trail needs a position |
| `ACCESS_BACKGROUND_LOCATION` | scanning continues while the screen is off |
| `BLUETOOTH_SCAN` / `NEARBY_WIFI_DEVICES` | passive discovery |
| `READ_PHONE_STATE` | cell identity for the anomaly checks |
| `FOREGROUND_SERVICE_LOCATION` | the scan runs as a visible foreground service |
| `INTERNET` | the loopback `rtl_tcp` socket, OSM tiles, Overpass import |

## Licence

Apache-2.0 — see [LICENSE](LICENSE). Bundled reference datasets and their origins are
listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Security issues: please report privately rather than opening a public issue.
