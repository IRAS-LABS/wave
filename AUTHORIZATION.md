# Authorization and acceptable use

Wave is a **passive** receiver. It listens to what radios around it already broadcast into
open air — BLE advertisements, Wi-Fi beacons, classic Bluetooth inquiry responses, cell
broadcast identity, and (with an external SDR) sub-GHz bursts such as TPMS.

It does not transmit. It does not connect, pair, associate, probe, deauthenticate, inject,
jam, or attempt to decrypt anything. There is no code in this repository that does any of
those things, and pull requests adding them will not be merged.

Read this before you use it.

---

## Use it only where you have authority over the scope

One of the following must be true:

- The devices and networks are **yours**, or
- You have **written authorization** to test them (a pentest engagement, a CTF, a lab), or
- You are checking **your own surroundings** for surveillance directed at you — a tracker
  in your car, a camera in a rental, a device that has been following you.

Your authorization covers your scope. It does not extend to the people, vehicles, or
officers who happen to pass through the air around you. They are not parties to your
engagement and they did not consent to anything.

---

## What Wave deliberately does not do

These are scope decisions, not missing features. They are not on a roadmap.

- **No movement history of people or vehicles.** Wave does not build a timeline of where a
  device it does not own has been. Positions are recorded against sightings so *you* can
  see where *you* were when something was heard; the app has no "show me everywhere this
  device went" view and will not grow one.
- **No live bearing or direction-finding.** There is no compass arrow, no "hotter/colder"
  targeting mode, no triangulated fix on a third party. Distance is estimated from signal
  strength for proximity alarms only, and it is displayed with its error, because a
  path-loss estimate is not a position.
- **No routing around detected equipment.** Wave will not tell you where law enforcement
  is so you can avoid it. Mapped ALPR positions come from OpenStreetMap and are public
  civic data about fixed infrastructure; they are not a live feed of anybody's location.
- **No cross-session identity profiling.** Payload-derived identity keys exist to stop one
  rotating tracker from appearing as forty devices in a single session. They are not for
  recognising a stranger's phone across days.

What Wave **does** do is the narrow, defensible half of that: it **identifies** equipment
in range, including surveillance equipment that broadcasts a known signature, and it
**alerts** when something appears to be travelling with you. Presence and identification —
not tracking.

The distinction is the whole design. A tool that tells you a tracker is in your car is a
safety tool. The same tool pointed outward, following a stranger, is the thing it was
built to defend against. Wave is built so the second use is awkward and unsupported, and
that is on purpose.

---

## Data handling

- **Everything stays on the device.** Detections are written to a local database and never
  uploaded. There is no account, no telemetry, no crash reporting, no analytics.
- **Identification is offline.** Vendor and protocol lookups run against databases bundled
  inside the APK. Nothing about a device you see is sent anywhere in order to name it.
- **Two network calls exist**, both public, both triggered by you: the OpenStreetMap
  Overpass API for the ALPR camera-map import, and OpenStreetMap tile servers for map
  tiles. Neither carries anything about what you have detected. Requests identify Wave in
  the User-Agent because those services require it.
- **Exports are yours to control.** CSV/JSON/KML files are written to app storage. They may
  contain MAC addresses and the coordinates at which you heard them. Think before you
  share one — a wardrive log is a log of your own movements as much as anything else's.

---

## Your responsibility

Radio law varies by jurisdiction. Passively receiving unencrypted transmissions is broadly
lawful in many places, but **recording, retaining, and acting on** what you receive can be
regulated separately, and the rules differ for cellular, for aviation, and for anything
identifying a person. Some jurisdictions treat a MAC address as personal data.

You are responsible for complying with the law that applies to you. Nothing here is legal
advice, and the presence of a feature in this app is not a claim that using it is legal
where you are.

---

## Reporting a problem

If you find a way to use Wave that crosses the line above, that is a bug in the design and
worth reporting. If you find a security issue in the app itself, report it privately rather
than opening a public issue.
