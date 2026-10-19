## Test setup

Record the exact build tested. For the rolling release, copy the plugin version,
source commit, pipeline ID, and APK SHA-256 before starting; later pipelines
replace its APK.

| Field | Value |
| --- | --- |
| Plugin version / pipeline / source commit / SHA-256 | |
| Phone model / Android version | |
| ATAK version | |
| Watch model / firmware / WearTAK app version | |
| Pairing mode and purpose | |
| Tester / date | |

Use a test TAK server and a second ATAK device. Run emergency tests only on an
isolated test network, never an operational network. Do not attach credentials,
certificates, or sensitive locations to this issue.

## Core acceptance checklist

Check an item only after it passes. For unavailable hardware or unsupported
features, leave it unchecked and record **N/A** with the reason in the results.

- [ ] **Connect and recover:** connect, move out of Bluetooth range and return,
  then toggle phone Bluetooth off/on. Status matches the actual connection.
  Traditional BLE reconnects to the same bonded watch without changing modes.
- [ ] **Intentional disconnect:** Disconnect stays disconnected until deliberately
  reconnected. Android/Galaxy Wearable bonds remain intact.
- [ ] **Pairing-mode changes:** switch among available BLE, Samsung Wearable, and
  Garmin modes, including during connection and settings operations. Only the
  selected transport stays active; no stale connected status or old-watch data.
- [ ] **Settings:** change callsign and supported settings, save, reconnect, and
  reopen settings. The watch applies them; a failed sync is not shown as success.
- [ ] **Managed/standalone:** exercise Bond Single Watch and Configure Single Watch
  on BLE/Samsung. With compatible firmware, intentional disconnect releases phone
  management. Standalone settings and certificates persist. On Traditional BLE,
  loss of the managed session releases the edit lock after the documented
  30-second grace period.
- [ ] **Live points:** create a supported ATAK marker, move/rename it, and create a
  point from the watch. Supported changes reach the paired device without
  duplicates/echo loops. Do not expect BLE reconnect to replay old map points.
- [ ] **Local shared-point deletion (BLE):** delete an unchanged point exchanged
  in the current session from each device. Eligible paired copies are removed;
  unrelated/newer-edited points survive. A third TAK device keeps its copy and
  receives no deletion. Repeat after reconnect to confirm stale deletions do not
  remove points.
- [ ] **Direct/broadcast chat:** receive and reply to direct and All Chat Rooms
  messages. Recipients are correct; private replies never become broadcasts.
  Watch-send echoes do not create incoming duplicates/unread notifications on
  the phone; genuine remote messages still notify with the conversation closed.
- [ ] **Named group chat (BLE/Wear OS):** use an existing group containing the
  paired phone and two remote senders, alongside a direct conversation. The watch
  shows one group room with both senders; preset/keyboard replies reach group
  members, not outsiders. Delete/empty the group before replying: no broadcast
  fallback. Repeat after reconnect.
- [ ] **Emergency lifecycle:** on an isolated network, trigger and cancel alerts
  from phone/watch and receive a remote ATAK alert. Supported alerts/cancellations
  propagate without duplicates. Stopping tracking does not cancel an alert.
- [ ] **Bloodhound:** start/stop from both devices using an existing valid point,
  replace targets quickly, then disconnect/reconnect. Both track the intended
  target; stopping preserves the marker/alert. No old shared session is replayed.
- [ ] **Remote users/location:** move a second ATAK user and check the supported
  watch-map position/callsign updates. Repeat with unavailable phone location;
  positions must not be fabricated.
- [ ] **Lifecycle/endurance:** lock the phone, background and restart ATAK, then
  exercise chat/map updates and reconnects over 30-60 minutes. No crashes,
  duplicate relays, stale settings, or false connection status. Record unusual
  heat or battery drain.

## Conditional features

- [ ] **Garmin:** test direct/broadcast chat, entity/emergency updates, and
  Bloodhound. Named groups must not fall back to broadcast. Garmin does not
  provision TAK servers/P12 or promise reliable end-to-end delivery.
- [ ] **Garmin routes (compatible watch app):** add, rename, edit, and remove an
  ATAK route; verify ordered vertices and watch refresh. Route-vertex tracking
  stays local to the watch; it does not start native ATAK Bloodhound.
- [ ] **Multiwatch Gateway (compatible Wear OS builds):** connect multiple
  watches, relay supported traffic, disconnect/reconnect one, and switch purpose.
  Gateway stays in BLE mode, does not provision settings, and leaves each watch's
  configuration unchanged.
- [ ] **Location diagnostics (compatible Traditional BLE watch):** enable/disable
  diagnostics, disconnect, and deny location permission. Diagnostics stop when
  disabled/disconnected, report unavailable permission/provider state, and do not
  change ATAK's location source or interfere with operational traffic.

## Results

**Overall:** PASS / FAIL / BLOCKED

**N/A items and reasons:**

**Failures:** for each failure, include steps, expected/actual behavior,
timestamp, pairing mode/purpose, and a screenshot or short recording. Attach
sanitized logs where available.

**Release blockers:** wrong-recipient chat, unintended deletion, incorrect
emergency cancellation, false connection/sync success, or crashes preventing use.
