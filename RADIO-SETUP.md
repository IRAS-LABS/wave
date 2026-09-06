# Wave — connecting a radio

Wave adds a sub-GHz lane when an SDR is attached. An **RTL-SDR dongle** and a **HackRF
One** both work. This is how to get either one feeding Wave, and what changes on screen
once it does.

Wave never opens the USB device itself. It connects to an **rtl_tcp server on
`127.0.0.1:1234`** — a local socket on the phone. A driver app owns the USB device and
speaks that protocol. That is why Wave needs no root, no custom kernel, and no per-device
quirk handling: the driver app already solved that.

---

## What the Radio page does

- **Nothing plugged in** → setup guidance, which is what you're reading here.
- **Dongle attached and a scan running** → the page becomes a live instrument: tuner chip,
  current band, burst count, link state. The buying guidance disappears entirely.

So: yes, the page turns into something else. It is not only a shopping list.

---

## Route 1 — RTL-SDR dongle (easiest, do this first)

1. **Plug in** through a USB-C OTG adapter. If the phone browns out, use a powered
   adapter — an RTL-SDR pulls around 300 mA and not every phone supplies it.
2. **Install a driver app** that exposes rtl_tcp. The standard one is
   **"RTL-SDR Driver"** by Martin Marinov (free, on Play).
3. **Open it**, allow USB access when Android asks, start the server on **port 1234**.
4. **Return to Wave** and start a scan. The Radio page flips to live within a second or two.

### Antenna
This matters more than the radio does. Use the telescopic dipole, set each leg to:

| Band | Leg length | Where |
|---|---|---|
| 315 MHz | ~23 cm | North America TPMS |
| 433.92 MHz | ~17 cm | Europe TPMS, most sub-GHz IoT |

Lay the dipole **horizontally**. TPMS sensors are horizontally polarised in a rotating
wheel; a vertical whip loses most of the signal.

---

## Route 2 — HackRF One

The HackRF is a much better radio than the RTL-SDR — 1 MHz to 6 GHz, and it transmits —
but it does **not** speak rtl_tcp natively. You need a shim.

**Option A — `hackrf_tcp` in Termux (no root):**
1. Install Termux (F-Droid build; the Play version is stale).
2. `pkg install hackrf` then build or install a `hackrf_tcp` shim that presents the
   rtl_tcp wire protocol.
3. Run it bound to `127.0.0.1:1234`.
4. Start a scan in Wave.

**Option B — SDR driver apps with HackRF support:** some USB SDR driver apps on Android
enumerate the HackRF and expose rtl_tcp. Check that a driver lists HackRF before buying
anything.

**Honest assessment:** for the sub-GHz work Wave does today — TPMS at 315/433.92 MHz —
the RTL-SDR is the path of least resistance and performs identically. The HackRF's range
matters if you later want to look above 1.75 GHz, which is where the RTL-SDR stops. Use
the RTL-SDR for wardriving; keep the HackRF for wideband survey work.

**Power:** the HackRF draws far more than an RTL-SDR. A phone OTG port will likely not
sustain it. Use a powered hub.

---

## What the radio buys you

| Capability | Phone alone | With SDR |
|---|---|---|
| Wi-Fi / BLE / cell | Yes | Yes |
| TPMS (vehicle identification) | **No** | Yes |
| Sub-GHz IoT, remotes, sensors | **No** | Yes |
| Above 1.75 GHz | No | HackRF only |

A phone's radios cover 2.4 GHz, 5 GHz, 6 GHz and the cellular bands. None of them tune
anywhere near 315 or 433 MHz. This is the antenna and the transceiver, not the software —
no app works around it.

A **TPMS sensor ID is stable for the life of the sensor**. That is what makes it useful
here: a vehicle that keeps reappearing behind you shows up in the sub-GHz list even if the
driver never carries a phone.

---

## Also worth doing: unthrottle Wi-Fi

Costs nothing, no hardware, and it is the single biggest improvement available.

1. Settings → About phone → tap **Build number** seven times.
2. Settings → System → **Developer options**.
3. Turn off **"Wi-Fi scan throttling"**.

Android otherwise limits an app to four scans every two minutes. At 50 km/h that is one
scan every 400 metres, and most of a street goes unheard. It costs battery.

---

## Hardware notes

| Item | Cost | Note |
|---|---|---|
| RTL-SDR Blog V4 | ~$40 | R828D tuner, 500 kHz–1.75 GHz, TCXO so it doesn't drift. The reference device every decoder is tested against. |
| USB-C OTG adapter | ~$10 | Powered version if the phone browns out. |
| Telescopic dipole | usually included | Length matters more than the radio. |

An ESP32 or a Flipper Zero can receive these bands, but neither exposes raw samples to a
phone over a standard protocol, so Wave cannot decode from them without a custom firmware
bridge.
