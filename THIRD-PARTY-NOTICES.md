# Third-party data and notices

Wave identifies hardware offline. That is only possible because it ships copies of public
reference datasets inside the APK. They are listed here with their origin so anyone can
check what is in the binary and where it came from.

Nothing here is scraped from a private source, and none of it contains information about
any individual.

---

## `app/src/main/assets/master_oui.db` — ~19 MB

MAC address prefix to manufacturer, covering the IEEE MA-L (24-bit), MA-M (28-bit) and
MA-S (36-bit) registries.

- **Origin:** the IEEE Registration Authority public listings
  (<https://standards-oui.ieee.org/>), reformatted into SQLite.
- **Terms:** IEEE publishes the assignment listings for public use. The data is factual
  registry information; the SQLite packaging is this project's.
- **Note:** the listings change as blocks are assigned. The bundled copy is a snapshot,
  not a live feed, and an unrecognised prefix usually means the snapshot is older than
  the assignment.

## `app/src/main/assets/bt_company_ids.csv` — ~120 KB

Bluetooth SIG company identifiers, used to turn a manufacturer-data company ID into a
vendor name.

- **Origin:** the Bluetooth SIG Assigned Numbers list
  (<https://www.bluetooth.com/specifications/assigned-numbers/>).
- **Terms:** published by the Bluetooth SIG for public use. "Bluetooth" is a registered
  trademark of Bluetooth SIG, Inc. This project is not affiliated with or endorsed by
  Bluetooth SIG.

## `app/src/main/assets/rf_protocols.json` — ~220 KB, 692 entries

Sub-GHz protocol and device descriptors used by the SDR lane.

- **Origin:** the RF-Protocol-Database aggregation (Ringmast4r), which is itself compiled
  from open projects including URH, rtl_433, wmbusmeters, rc-switch and several open
  Flipper Zero firmware collections. The per-entry `sources` field in the file records
  which upstream each descriptor came from.
- **Terms:** the upstream projects carry their own licences, and the aggregation's terms
  should be confirmed against its repository before redistributing this file outside
  this project.

## OpenStreetMap — network, not bundled

- Map tiles are fetched from <https://tile.openstreetmap.org> and the ALPR camera import
  queries the Overpass API. Both are triggered by the user, never automatically.
- OpenStreetMap data is © OpenStreetMap contributors, available under the Open Database
  Licence (ODbL). Tiles are served under the OSMF tile usage policy; Wave sends the
  User-Agent that policy requires and caches tiles to keep request volume low.
- Nothing Wave has detected is included in either request.

---

## Reporting a problem with this list

If a dataset here is misattributed, or its terms do not permit the use above, that is a
bug worth reporting and it will be fixed or the dataset removed.
