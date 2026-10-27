Weartak ATAK Companion Plugin

PROJECT SUMMARY

Unlimited Rights are granted to the TAK Product Center. For questions or
project coordination, contact Alex Gorsuch on chat.tak.gov or Signal.
The TAK Forge repository is canonical; GitHub is secondary.

PLUGIN SUMMARY

WearTAK is an ATAK companion plugin that connects ATAK with supported
smartwatches. It relays Cursor-on-Target (CoT) data over Bluetooth Low
Energy between ATAK and Samsung/Wear OS watches, including map markers,
emergency alerts, and GeoChat messages. This integration branch includes
the Garmin Connect IQ relay and an exclusive three-mode connection selector.
The optional Garmin Connect IQ
transport uses Garmin Connect Mobile on the phone rather than the
Samsung/Wear OS BLE connection.

The phone-to-WearOS point relay forwards all non-user `a-*` atoms (including
aircraft, surface and detailed ground types) plus SPI `b-m-p-s-p-i`, preserving
the exported CoT and its affiliation/type. User/contact PLI remains on
`PhoneUserRelay`; self/watch exclusions, bounds, duplicate suppression,
stale handling, revision-gated deletion and capacity limits are unchanged.
Invalid-source-lifetime user warnings are limited to once per UID per minute
using a bounded 512-entry diagnostic cache. Recovery, removal or relay stop
clears the corresponding warning state.

### Phone-owned position actions and Garmin field relay

Garmin alerts and legacy WearOS/Companion PLI and alerts use the phone's newest
available position, never a fabricated 0,0 point or fixed 10 m accuracy.
Every self-position action starts an active GPS/network location request without
blocking the action: the newest last-known fix is published immediately, then the
same entity UID is updated when a new fix arrives. Active emergencies continue
receiving location updates until cancellation or transport-mode teardown. Other
actions acquire for at most 30 seconds. Permission/provider failures are logged;
the plugin does not change permissions or ATAK's location-source selection.
CoT `ce`/`le` retain source uncertainty (9999999 when unknown), and
`detail/phone_location` records the original fix UTC time, age and stale flag.
Unknown fix time is empty with age -1; stale positions are not discarded.

If no real fix has ever been available, positional writes wait for a real fix.
An emergency immediately sends an All Chat Rooms text alert:
`EMERGENCY from <callsign>: location unavailable, acquiring GPS`.
A local notification explicitly reports a text-send failure. The pending
emergency publishes its position as soon as the first usable fix arrives.

Watch actions can request the same nonblocking refresh using
`msgType: location_request`, with `payload.action` (`map`, `navigation`, `identity`,
`self_marker` or `pli`) and `payload.requestId`. The hint refreshes phone PLI, not
a second marker. A self-position marker carries `payload.locationSource: phone`;
its UID is retained on the fresh-fix update. Unflagged explicit watch/map-tap
coordinates remain intentional. Garmin receives a normal `entity` response for
a phone-resolved self marker using the original marker UID. Phone/self contacts
remain excluded from Garmin entity snapshots; Garmin owns its local navigation GPS.

Garmin negotiates `relay_status.payload.reliableDelivery: true` and
`phoneLocation: true` (the capability gate for coordinate-free phone-owned markers). Operational
messages carry a stable `payload.messageId`; retries receive
`relay_result.payload: {messageId, relaySession, ok}` using the incoming retry's
session. One shared, bounded 4096-ID cache suppresses repeated TAK writes across
Connect IQ/BLE routes and reconnects for this plugin run. ACK means Companion
accepted the action, not remote teammate delivery; the cache is not a persistent
exactly-once guarantee across process death. Legacy WearOS envelopes without IDs
remain supported. Generic Garmin entities include all `a-*` affiliations,
`b-m-p*` spots and `u-d*` shapes, source stale time, nested `link.uid/relation`,
radius and `shape.ellipse.major/minor`. ATAK video-alias markers (`b-i-v`) relay as
points, and any relayed entity whose CoT has a `<__video>` detail carries a
presence-only `__video: {}` camera classification; stream URLs and other video
attributes are never sent to Garmin. Coordinate-free `t-x-d-d` deletion events
are relayed; map removals delete only entities previously published by the phone.
The MR-only `field_report_regressions` job runs these transport-independent JUnit
regressions without signing an APK. The existing protected-`develop` signed
release pipeline remains unchanged.

### ATAK display units

Change units in **ATAK Settings > Display Preferences > Unit Display Format**.
Range Units selects Feet/Miles, Meters/Kilometers or Nautical Miles. The feet-to-miles
and meters-to-kilometers thresholds, speed, altitude, bearing units and north
reference remain independent ATAK choices. The current WearOS display offers one
Metric/Imperial toggle: it consumes the range preference (`1` metric; `0` or `2`
imperial), uses 5280 feet for the miles transition, and ignores the other six
preferences. Nautical range therefore displays as imperial on that watch build;
transmitting the remaining keys does not claim watch-side support for them.

Companion sends all seven preferences with the existing WearOS settings upload
in setup and phone-managed modes. It also observes ATAK's host SharedPreferences
and sends units-only updates to the ready bonded single watch, including after
reconnect. Gateway sessions do not receive units. Display choices do not change
CoT coordinates, reporting intervals, server configuration or management ownership.
The watch may change its own units; the next phone update wins, and disconnect
does not restore previous units.

The additive `set_settings.payload.unitPrefs` object and live `unit_prefs.payload`
use ATAK's seven preference keys verbatim with string values: `rab_rng_units_pref`
(`0` English, `1` metric, `2` nautical), `rng_feet_display_pref` (default `5280`),
`rng_meters_display_pref` (`2000`), `speed_unit_pref` (`0` mph, `1` km/h, `2` knots,
`3` m/s), `alt_unit_pref` (`0` feet, `1` meters), `rab_brg_units_pref` (`0` degrees,
`1` mils) and `rab_north_ref_pref` (`0` true, `1` magnetic, `2` grid).
Missing ATAK preferences use ATAK 5.6 defaults: metric range, mph, feet altitude,
degrees and magnetic north. Invalid values are reported instead of silently
substituting another unit; thresholds must be between 1 and 1000000.
Older WearOS builds ignore the unknown field/message safely.

Garmin uses the existing `relay_status` configuration path only when the exclusive
current watch advertises numeric `relay_hello.payload.unitPrefsVersion: 1`.
The status adds `unitPrefsVersion: 1` and the same `unitPrefs` dictionary, retaining
all negotiated capabilities and its relay session on live updates. Older Garmin
builds receive no new unit fields; their profile-settings transactions are unchanged.

### Companion logging (WEARTAK-274)

Tap the small **log** icon beside the WearTAK Companion title (it turns green while on) before
reproducing a problem. This opt-in is off by
default, saved in Companion preferences across ATAK restarts and in-place updates,
and remains on until tapped off. The small **send** icon next to it manually opens ATAK's
existing data-package recipient workflow for all retained logs; nothing is sent
automatically. The files can also be retrieved from ATAK's `companion-logs`
storage directory when using an authorized local debugging connection.

Capture stores only UTC timestamps, severity, fixed `WTK/*` component names and
coarse event categories (such as transport, location, report, negotiation, sync,
command, lifecycle and failure). Raw log messages, exception details, coordinates,
callsigns, device identifiers, Bluetooth addresses, credentials, server addresses
and payloads are never written. Metadata is limited to plugin version, ATAK
version and Android API level; Android logcat is not read. Duplicate events are
rate-limited and a bounded 256-record queue drops excess events instead of
delaying BLE/UI work.

At most three UTF-8 logs are retained, each at most 2 MiB (6 MiB total). The
active log rotates at its size bound; older excess Companion logs are removed,
never unrelated files or ATAK-managed data packages. Disabling capture closes
the current file but does not export or delete it. File creation, writes,
rotation, cleanup and finalization use a single writer worker. Opening ATAK's
recipient workflow is not proof of delivery.

### Bonded Location Diagnostics (WEARTAK-165)

Traditional BLE handles WearTAK's opt-in location diagnostics as version 1, session-fenced JSON messages distinct from CoT, gateway forwarding, and phone/watch location authority. The phone accepts negotiation only for a ready bonded single-watch Companion session; setup, gateway, Garmin, and Samsung routes do not start observers. The watch's session-scoped stop message immediately unregisters phone callbacks when diagnostics are disabled; a watch or phone disconnect also stops the callbacks. Unsupported versions/peers are ignored without periodic renegotiation.

The phone checks location permissions and provider state explicitly using ATAK's host context, not the plugin resource context's nullable application context. When allowed, it registers GNSS status/raw-measurement observers and a passive location listener; the passive provider does not initiate fixes or alter ATAK's location source. Snapshots contain only fix age, accuracy, provider, optional motion accuracy, and aggregate satellite/CN0/AGC measurements. Coordinates, callsigns, device IDs, and satellite identifiers are excluded. Diagnostics use a small conflated best-effort queue and are dropped while operational BLE writes are active.

TRADITIONAL BLE AUTOMATIC RECONNECT

Issue #16: a watch restarting its companion GATT service can report Services
Changed before the first notification subscription completes. Traditional BLE
now closes that stale GATT session and permits one fresh connection to the same
already-bonded, manually selected peer after two seconds. This setup-only retry
has a 30-second watchdog (normal setup remains limited to 25 seconds) and does
not persist or arm ongoing reconnect until notification subscription succeeds.
Failure or another service change before readiness ends that one-shot recovery;
Connect can be retried manually. Scan, Disconnect, trust loss, and mode teardown
cancel it. Samsung retains its own retry owner.

Services Changed on a still-connected, still-bonded GATT (Traditional or Samsung,
during setup or after readiness) first rediscovers services on that same connection
and promptly re-enables A11B notifications, without changing the watch's four-second
CCCD watchdog. The refresh starts a new session generation, tears down the settings
session (`connected=false`), clears the RX/TX queues and characteristics, re-arms the
25-second setup timeout, and drains any outstanding MTU, discovery, CCCD or TX
completion before issuing fresh discovery. A pending pre-change discovery result
is discarded, not reused. This serial handoff prevents its completion from answering
a new request even if Android reuses characteristic objects or handles. Android has
no request token; this relies on one completion per issued operation, not protection
against arbitrary duplicate platform callbacks. A missing completion fails closed at
the setup timeout; waiting for it may still exceed the watch's unchanged watchdog. The
Samsung owner is not reset by that transient disconnect, but any refresh failure or
timeout still reaches it. Each GATT connection gets one in-place refresh; readiness on
the same GATT does not restore it, so a second Services Changed falls back to the
bounded close/retry behaviour above. An unbonded peer still fails closed.
This recovery does not address the separate, unproven service-disable case ("Case A");
watch enablement, managed-configuration policy and service lifecycle are unchanged.

A user-initiated Traditional BLE Connect whose link never comes up (GATT status
133, 8, 62 or 147 before STATE_CONNECTED) is retried automatically on the same,
still-bonded address at most twice, after 1 s and then 2 s, closing the old GATT
first. The pane shows "Retrying connection (n/2)…"; only the final failure is
reported as an error. Disconnect, Scan, a new Connect, stop, Bluetooth off, trust
or bond loss, and any session reset cancel a pending retry. Failures after the link
came up, other statuses, Samsung-owned connects and automatic reconnects are not
retried here. A new GATT connection waits 1 s after a previous GATT close and
300 ms after an active scan stops.

WEARTAK-275: New Companion watch bonds explicitly request `TRANSPORT_LE` using
Android's non-SDK `createBond(int)` overload. The public no-argument
`createBond()` selects AUTO and can incorrectly attempt Classic paging of a
watch's LE-only private address. If Android hides/blocks the LE overload, rejects
the request, or denies permission, pairing reports an error; it never falls back
to AUTO/Classic. A successful request still waits for `BOND_BONDED` before GATT
setup. Existing bonds are reused without requesting another bond, and all GATT
connections continue to use `TRANSPORT_LE`. Samsung-owned connects still require
an existing system bond. This does not bypass pairing confirmation or repair an
independent SMP confirmation timeout.

WEARTAK-276: While a single watch is connected over BLE with phone-managed
identity enabled, ATAK preference/self-marker changes push the current phone
callsign, team and role through `set_callsign`, `set_team` and `set_role` without a
reconnect. Events are coalesced on the main thread and unchanged identity is not
resent. Changes during the initial management handshake are deferred until it
finishes, using the latest phone identity. Disconnected, standalone/setup,
Gateway, Garmin, management-release and failed-handshake sessions do not send
these automatic BLE updates. Observers unregister on plugin stop.

Each live command contains only its matching changed `callsign`, `team` or `role` field.
They never include reporting intervals, servers, certificates, management mode
or the watch's standalone callsign. Reconnect-time full settings sync remains
unchanged. Live sends do not block subsequent edits waiting for an acknowledgement;
existing settings acknowledgements report apply failures. The CoT bridge also
reads the phone callsign when constructing messages rather than caching it at
plugin startup. Live watch updates require WearOS support for these three commands;
older watches ignore unknown commands and retain reconnect-time identity sync.

WEARTAK-271: Bond/Configure Single Watch BLE scan and selected rows show five
alphabetically ordered labeled lines:

```text
ATAK Callsign: <local ATAK callsign> (<current panel status chip text>)
Bluetooth Address: <watch address>
Bluetooth Strength: <last observed RSSI> dBm
WearTAK Callsign: <watch callsign>
WearTAK Product Name: <Android Bluetooth device name>
```

The first line mirrors the active BLE/Samsung panel status chip verbatim, including
sync/error/disconnected states, whenever that chip updates. Missing identity,
address or signal values display `Unavailable` with their labels retained.
No watch callsign is invented from a Bluetooth product name or alias. The old
unlabeled MAC, RSSI badge, and secondary bond-name text are hidden in those rows;
the identity block can expand without truncating the five fields.

WEARTAK-279: Samsung Wearable and Garmin panels use this same five-line block
under their status chip for their existing supported purposes. Samsung uses its
identified bonded candidate, current BLE standalone identity, and Android product
name; absent scan RSSI is `Unavailable`. Garmin shows a separate, scrollable
block per SDK-known watch (or a placeholder block if none is known). Connect IQ's
friendly name supplies the product label; its numeric device identifier is not
a Bluetooth address, and the SDK supplies neither MAC nor RSSI, so both Bluetooth
fields show `Unavailable`. Garmin's WearTAK callsign is shown only when explicitly
reported as `standaloneCallsign`, or observed in `watch_settings` with
`phoneManagedSettings: false`; effective managed callsigns are never substituted.
Observations stay scoped to the SDK watch and disappear when removed/stopped.

Gateway admitted-watch and scan-discovery rows use the four remaining labels in
the same order, omitting the ATAK Callsign line entirely. Gateway protocol watch
identity may supply the callsign if no standalone/advertised identity is available;
no local ATAK name or bond suffix is added. Existing purpose/transport restrictions
remain unchanged: Gateway is BLE-only and Garmin Configure Single Watch remains
unsupported. This is presentation only, not a new transport or pairing behavior.

WEARTAK-273: The `WearTAK Callsign` line always identifies the watch's standalone
(unbonded) identity, never the effective phone-managed `callsign`/`cs`. A new string
`standaloneCallsign` in `watch_info`, `settings_request` or `settings_changed` payloads
takes precedence over the A11A advertised callsign, then `Unavailable`. Older watches
without that field remain compatible through their advertisement. The watch must
advertise its standalone identity even while managed. Explicit standalone strings
are accepted only from a ready session and cached by peer address for this client
run so disconnects/resets do not replace them with phone identity. Sparse messages
retain the observation; a blank string clears it back to advertisement fallback,
and non-string/null values are ignored with a log. Observations never cross peer
addresses and are not persisted across plugin restart. Other row fields/order and
effective settings/PLI identity semantics are unchanged.
The product
label comes from Android's Bluetooth device name, not its alias. Android cannot
guarantee this is the factory product name if the device name was changed elsewhere.
The Bluetooth address remains separately labeled for disambiguation.
No Bluetooth/device names or system settings are changed. A nameless
packet retains an earlier advertisement for the same address within that scan.
Names are display context only; device selection and trust remain address-based.
WEARTAK-269: A11A service data in the scan response supplies the watch's chosen
WearTAK callsign before connection: byte 0 is version 1, followed by 0..12 UTF-8
bytes, truncated by the watch at a complete code point. Only version 1 and strict,
nonblank UTF-8 within that limit are accepted. Both A11A service-UUID advertisements
and A11A service-data-only responses are recognized. A valid callsign is the primary
scan label; missing/invalid/unknown-version data falls back to the device name,
then address. The last advertised callsign is cached by address for this client
run (including new scans and connection resets) so primary-only packets do not
erase it. A present empty, invalid or unsupported payload clears that address's
advertised callsign; a new valid payload replaces it. Scan, selected, bonded and
gateway watch labels use this identity, with the address still shown separately.
No Bluetooth name or system settings are changed; advertised labels do not prove trust.
WEARTAK-270: During an active scan, results not seen for 10 seconds expire, checked
once per second using monotonic elapsed time, including when no new results arrive.
Seeing the same address refreshes its existing row and RSSI rather than adding a row.
Different addresses remain separate even if callsigns or truncated labels match:
private-address rotation cannot safely prove that two advertisements are one watch.
Scan completion supplies a final fresh snapshot; after scanning stops, that snapshot
is not a live reachability indicator. Expiry affects scan choices only, not selected/
connected peers, bonds, reconnect targets or the address-keyed callsign cache.
After handshake, an observed full callsign is primary. Callsigns are bound to
the ready peer address and session generation;
they are not persisted for future scans or borrowed from another watch. The
separate advertised callsign is not persisted across plugin restart. These
labels cannot recover a historical product name that Android/the watch no longer
exposes, and no product model or callsign is inferred from device names.

Setup errors clear the device row's Connecting state even if transport readiness
was never reached. No bond is created/removed by service-change recovery and no
different watch is selected by name. Scan results still require a live A11A
advertisement; this phone-side fix cannot make a stopped watch advertise.
Hardware acceptance: toggle WearTAK companion mode off/on, select the watch, and
verify readiness or an actionable error within the bounded setup/recovery window,
then retry Connect without rescanning. Repeat with Disconnect, Scan, Bluetooth
off/on, and mode changes while recovery is pending; ensure no late reconnect and
the original Android/Galaxy Wearable bond remains intact.

Canonical develop at c6cd9d1 had no Traditional BLE user-toggle automatic reconnect:
`WearTakBleClient.start()` explicitly initialized with no auto-connect, and
`main_layout.xml`/`WearTakPlugin` had no automatic-reconnect checkbox. Samsung
already had its own `SystemBondedWatchConnection` retries; selecting that mode
is not a Traditional BLE automatic-reconnect toggle.

Traditional BLE now reconnects automatically as part of the selected mode; there
is no user-toggle checkbox or reconnect explanation in the device pane.
The last services-ready peer persists in ATAK's host-owned preferences using
the existing `wtk_traditional_reconnect_v1` namespace. The removed opt-in value is
ignored, including previously saved OFF values. Experimental MR15 and Samsung peer
preferences are not imported. No other preference storage is migrated.

After a bonded, services-ready connection in the current plugin run, unexpected
loss starts a retry after 2 seconds; failed attempts retry after 60 seconds, with
a bounded 30-second setup watchdog (normal BLE setup still times out at 25 seconds).
Retries address only that same already-bonded peer, never scan by name to adopt a
different watch or create/remove a system bond. A changed address or explicit unpair
requires manual selection/pairing. The previously ready peer
survives plugin restart; a Traditional BLE run can resume retries to that
same peer, but data still requires a fresh bonded/services-ready session. Selecting
a new peer clears the previous reconnect target until the new peer reaches readiness.
Manual Disconnect and genuine trust loss disarm retries until another ready session.
Bluetooth OFF pauses retries and ON resumes an eligible armed peer. User scans pause
background retries; completion resumes them without racing a manual connection.

While Traditional BLE is ready, a lightweight `request_watch_info` probe runs every
30 seconds (no settings/P12 polling). Two missed 10-second responses or a failed
probe reset the transport and begin retry. All timers are cancelled on stop,
manual Disconnect, or mode ownership changes, and generation fences reject stale callbacks.
The existing session-qualified data writes and current chat/local deletion/alerts/
Bloodhound/provisioning policies are unchanged. The status listener is rebound on
each BLE mode start so a previous mode token cannot own a restarted connection.

Samsung Wearable retains its existing retry owner; it never uses Traditional BLE retries or the
health loop. Garmin retains its separate Connect IQ lifecycle, with named groups
blocked, `reliableDelivery:false`, and no external point deletion.

Reconnect never changes
the selected connection mode or falls back to a different mode.
Mode teardown explicitly revokes Traditional BLE reconnect ownership before
stopping the transport; stopped/generation-fenced probe and retry callbacks cannot
reconnect after switching to Samsung or Garmin. The three-choice connection-mode
dialog uses ATAK's activity context so it has a valid host window token.

LEGACY BLE IDENTITY METADATA

Legacy JSON PLI intentionally continues to represent the phone/EUD: its UID,
plugin callsign and ATAK location are unchanged. Its group comes from ATAK's
current `deviceTeam` map data and host `atakRoleType` preference (existing
Blue / Team Member defaults), never from watch JSON. Runtime `takv` uses ATAK's
brand, version, device description and OS helpers, respecting the host's
`dispatchTAKVersionNumber` preference; unavailable attributes are omitted.
Raw Gateway watch CoT is not rewritten.

WATCH PPE / PHYSIO ON PHONE SELF PLI (SELF_STATUS v1)

While a bonded watch is connected, it may contribute its saved PPE level and fresh,
opted-in physiological samples to the phone's own PLI. The Companion advertises support with
`relay_status.payload.selfStatus: 1` (Garmin) or `{"msg_type":"self_status_capability","version":1}`
on WearOS BLE READY. The watch then sends full `self_status` snapshots: `{version:1, session,
seq, deviceUid, model:"WEAROS"|"GARMIN"|"WATCHOS", ppe:null|{level, savedAt}, physio:null|{hr?,
measuredAt, sktF?, sktFMeasuredAt, exert?, exertMeasuredAt}}`. `level` is exactly `None` or
`MOPP 0`..`MOPP 4` (`None` is distinct from `MOPP 0`); drafts are never sent. `savedAt` is an ISO
time, or `null` when a legacy save time is unknown (never invented). Each physio metric is optional
but carries its own actual measurement time: `hr` bpm, `sktF` skin temperature in °F (59..113),
`exert` fraction 0..1. At least one metric is required.

Snapshots are bound to the current authorized transport generation:
- WearOS: the BLE READY generation, locked to the first accepted session and `deviceUid`.
- Garmin: `session` must equal the current `relaySession`, and `deviceUid` must equal
  `relay_hello.watchUid`. A hello without `watchUid` fails closed.

Delayed snapshots from an older generation, or a different session on the current one, are rejected.
`seq` must increase within a session. Garmin snapshots are best-effort hints and are not acknowledged.

The phone appends one managed line to ATAK's `userRemarks`, e.g.
`MOPP2; HR: 72 bpm @ 18:15:30Z; Skin: 93.4F @ 18:15:00Z; Exert: 45% @ 18:15:30Z`.
`None` is omitted from remarks. Fresh metrics are also merged into the self CoT as
`<biometrics version='1'><device sampleTime='…'><model>…</model><uid>WATCH_UID</uid><hr sampleTime='…'>72</hr><skt sampleTime='…'>93.4</skt><exert sampleTime='…'>0.45</exert></device></biometrics>`.
`skt` is in °F for model `WEAROS` and in °C otherwise, matching the physiology dashboard,
which ignores a device without `hr`. When another provider already publishes `biometrics`,
the watch device is merged into it; that provider's devices are preserved.

Each metric is fresh only while its actual measurement time is no more than 120 s old (30 s future
skew allowed); receipt time is never used. The managed line and biometrics are removed when:
- the watch disconnects;
- trust is lost;
- the route or plugin stops;
- 300 s pass without a snapshot.

Only the exact line the Companion wrote is removed; user remarks and edited lines are never
touched. Legacy watch PLI no longer adds a generated "WearTAK PLI" remark, and its remarks are the
phone user's ATAK remarks. The legacy untimed `<physio hr>` is used only for older watches that
have no active SELF_STATUS snapshot.

One marker per person: once a WearOS or Garmin watch has sent an authorized `self_status` snapshot
on the current Companion link, its `deviceUid` (e.g. `WEAROS_<androidId>`) is treated as part of the
phone's self marker. Any separate map track with exactly that UID, arriving over Ditto, TAK or
multicast, is removed from this phone's map while the link stays current. The watch's emergency UID
(`<deviceUid>-9-1-1`) and all other devices are never suppressed. Suppression ends when the
watch disconnects, loses trust, or the route or plugin stops; any ghost track that appeared during
a brief drop is removed again when the watch reconnects and resends `self_status`. Other EUDs
are not affected by this phone-local filter; the watch must stop publishing its own track
while Companion is connected.

Optional watch `team` / `role` strings in settings snapshots and identity-only
`settings_changed` deltas are retained in the existing observed settings snapshot.
Missing, blank, null or non-string fields do not invent values or erase a known
current-session value. Disconnect, peer switch and mode teardown discard the
observations; old peers start without them. Partial deltas do not unlock settings
sends. These observations neither write phone identity nor trigger settings
feedback to the watch. Phone-managed settings use only the phone's ATAK profile.

PAIRING PREFERENCE STORAGE

Pairing addresses, watch UID/callsign, and preferred-device display metadata use
ATAK host-owned preferences, just like connection mode/pause and reconnect state.
The plugin runs inside ATAK; its own package data directory can be unwritable to
ATAK's UID even while BLE is connected. Existing preference filenames and keys
are retained: `wtk_pairing` for the single watch, `wtk_gateway_pair_<address>` for
each gateway peer, and `weartak_companion_prefs` for mode and device metadata.
Host-owned values are not overwritten or imported into another peer's namespace.
The existing legacy connection-mode read fallback is retained; no pairing or
retry preferences are migrated from plugin-owned storage. An address that was
never saved successfully must be rediscovered through the existing bonded A11A
scan. A saved Samsung candidate still requires an existing system bond; saving
it does not arm Traditional BLE retries or prove a ready connection.
This storage correction does not establish that a reported Samsung reconnect
failure was caused by lost preferences; restart/reconnect acceptance still
requires hardware testing.

The device pane places **Pairing Mode:** immediately below its title, with
**BLE**, **Garmin Connect**, and **Samsung Wearable** choices. BLE places a compact Scan
button to the right of its status, without scan/reconnect explanations.
All modes use a single rounded status / action / settings-gear row. Status badges
show **NOT CONNECTED** on yellow and **CONNECTED** on green; missing-app statuses
remain red. Action buttons
and gears have fixed widths and matching heights; the status fills the remaining
space. The gear opens WearTAK watch settings and stays disabled until the route
is ready (exactly one connected WearTAK watch for Garmin). BLE offers Scan when
disconnected and Disconnect when ready. Garmin no longer displays the setup-requirements paragraph;
its Connect IQ prerequisites described in GARMIN SETUP still apply.
When Garmin Connect is missing (including SDK `GCM_NOT_INSTALLED`), its status bar
shows **Garmin Connect Not Found** in red, with an **Install** action to
open the official app's Play Store listing, with a web-listing fallback when the
Play Store is unavailable. When no bonded Samsung WearTAK watch is found,
**NOT CONNECTED** has an **Open** action to launch Samsung Wearable.
Samsung discovery does not depend on the app's package lookup: Android package
visibility can hide an installed Galaxy Wearable app from the ATAK host.
If the launcher lookup is unavailable, Open tries Galaxy Wearable's explicit
launcher activity; if launching fails, it reports the failure and opens the store listing.
Connect/Disconnect remains available for the watch route's other states;
no bond is removed or pairing mode changed by Install or Open.
Samsung's badge reports **CONNECTED** only after the data transport is ready,
**CONNECTING** during setup, **BLUETOOTH OFF** when the phone radio is off,
and **NOT CONNECTED** otherwise, including when
an existing bond is known but unreachable. Disconnect pauses ATAK's route and
its retries; it does not remove the system bond. Turning phone Bluetooth off
suspends Samsung discovery/retries; turning it back on immediately retries
unless the user paused the route. GATT connections explicitly use LE transport.
Watch-origin PLI, point and emergency CoT imports/forwards run on an ordered
background queue, outside BLE callbacks and their locks. Stopping the route
discards queued old-session imports; an already-running import may finish.
For local simulator checks, the debug build registers an emulator-only
`com.weartak.companion.SIMULATE_WATCH_POINT` broadcast while the plugin is
loaded. With BLE mode selected, send it using
`adb -s emulator-5554 shell am broadcast -a com.weartak.companion.SIMULATE_WATCH_POINT -p com.atakmap.app.civ`.
It imports a synthetic `SIMULATOR-WATCH-POINT` through the real CoT bridge and
logs whether the marker appears and the UI remains responsive. Keep the emulator
offline during this test so synthetic CoT is not sent to a TAK server.
Release builds contain no injection receiver; physical debug phones also reject it.
The same debug-only receiver accepts `com.weartak.companion.SIMULATE_WATCH_CHAT`,
`com.weartak.companion.SIMULATE_WATCH_ALERT`, and
`com.weartak.companion.SIMULATE_WATCH_CANCEL`. These exercise offline All Chat Rooms
submission and synthetic alert/cancel import. The receiver refuses injection unless
the emulator's Wi-Fi and mobile-data settings are both off. After an alert test,
always send CANCEL. UI heartbeat logs alone do not establish successful dispatch;
check the CoT bridge and ATAK chat/emergency logs as well. These checks do not test
BLE delivery, direct TAKX addressing, or server delivery.
Single-watch points retain their selected coordinates even when using the phone's
identity; malformed or partial coordinates are rejected, not replaced with the
phone location. Legacy points without coordinates use the phone fix. Titles are
XML-escaped. Watch alerts retain their event UID for cancellation but link to the
phone's self UID in single-watch mode; alert and cancel use the phone callsign.
Incoming phone alert/cancel CoT is preserved for the watch's emergency ingestion,
which feeds its map and Bloodhound targets. Point deletion remains revision- and
session-scoped; it does not broadcast a network-wide deletion.
For installed-state UI previews only, a debug build on a `ranchu`/`goldfish`
emulator can treat Samsung Wearable as installed when the ATAK host's private
`files/weartak-preview-samsung-installed` file exists. Enable with
`adb -s emulator-5554 shell run-as com.atakmap.app.civ touch files/weartak-preview-samsung-installed`;
disable with the same command using `rm` instead of `touch`, then reopen the pane.
The marker survives restarts and APK updates, but has no effect in release builds
or on physical phones. It does not simulate watch readiness or pairing.
Open displays an explicit preview notice if the real companion app is absent.
Garmin likewise offers **Open** to launch Garmin Connect when installed but no
watch is connected; its connected-state Retry action is unchanged.
The Garmin connection helper text is hidden in all connection states.
**WearTAK Settings** prioritizes **Use WearTAK with this phone**: managed identity
is read-only and uses the phone's callsign, team, and role. Bond Single Watch
requests phone management after a fresh complete watch snapshot, even when the
watch reports `phoneManagedSettings:false` (including after a deliberate release).
This applies to BLE, Samsung Wearable, and Garmin; reported watch state does not
override the selected purpose. Configure Single Watch remains unmanaged and
Gateway never provisions settings. BLE and Samsung distinguish transport readiness
from management confirmation: **CONNECTED - SYNCING** remains visible until a
successful settings acknowledgement is followed by a complete snapshot reporting
phone management enabled. Snapshot requests and identical settings writes each
have up to three attempts, ten seconds apart. Exhaustion or rejection shows
**CONNECTED - SYNC FAILED**; refreshing settings or reconnecting retries.
Disconnect, intentional unlock, and route changes cancel pending retries.
Garmin retains its existing transaction flow. Reporting interval remains configurable.
BLE and Samsung place server/P12 editing in a collapsed **Standalone Setup**
section. Expanding it does not change the saved mode or send anything. For
independent provisioning, select Configure Single Watch, send the settings, and
wait for the watch's save confirmation before disconnecting. Garmin hides standalone
setup because its current profile protocol does not provision servers or P12.
The title-row purpose selector offers **Bond Single Watch**, **Configure Single
Watch**, and **Multiwatch Gateway**, in that order. Bond Single Watch keeps the
existing phone-managed profile behavior and has no settings gear. Configure
Single Watch exposes server/P12 editing; BLE places a ready-gated settings
gear beside each watch's **Connect/Disconnect** action rather than a
status-row gear. Samsung retains its ready-gated settings gear;
purpose selection itself sends nothing. Configure suppresses automatic
managed-profile sends and requires BLE or Samsung Wearable; switch away from
Garmin before choosing it. The purpose is saved on the phone. Gateway never
provisions a watch; single-watch profile changes keep their existing flows.
Samsung Wearable shows a compact status and
adjacent Connect/Disconnect action instead of discovery explanations. Statuses are
**Not Found**, **Found** (known bond, not an active ATAK session), **Connecting**,
**Connected** (transport ready), and **Paused**. Disconnect pauses ATAK's separate
WearTAK route and cancels discovery/setup/retries; it does not remove the Android
bond or disconnect Galaxy Wearable's own connection. The paused choice persists
through plugin restart and mode changes until Connect is pressed. Settings remains
available when connected. Detailed connection errors remain in ATAK logs.

MULTIWATCH GATEWAY (NINE-WATCH BLE)

Choose **Multiwatch Gateway** to scan for and manually connect up to **nine**
WearTAK watches. The limit is a software admission cap, not a promise that every
phone can maintain nine simultaneous GATT connections. Advertisements are
deduplicated by BLE address; newly found watches are never auto-admitted. Only a
previously services-ready bonded address can resume automatically. Connected,
Gateway-ready watches stay at the top of the list in stable order. Disconnect
cancels only that peer's GATT session and retries; Remove also forgets its
Gateway admission. A bonded watch that lacks the Gateway v1 handshake shows an
unsupported-watch error and is not used for routing.

Gateway requires a WearOS WearTAK build containing the WEARTAK-154 Gateway v1
session protocol and **Use WearTAK Companion** enabled on each watch. Older
watch builds do not support Gateway negotiation; the companion-only TPP
source ZIP does not include the required watch update.

Gateway switches any prior BLE, Samsung, or Garmin route off before starting
BLE. Pairing Mode is locked and dimmed at BLE while Gateway is selected. There
is no Gateway settings gear or profile provisioning. The watch keeps only its
existing **Use WearTAK Companion** on/off control; its UID, callsign, team,
role, reporting interval, server list, certificates, and phone-management
setting remain watch-owned and are not written by Gateway.

Gateway v1 negotiates a fresh session for each ready BLE peer and carries only
validated CoT up to 32 KiB. Supported traffic is ATAK map points accepted by
the existing phone marker policy, `a-f-G-U-C`/`a-f-G-E` user positions and
their subtypes, WearTAK emergency CoT (`b-a-o`, `b-a-o-*`, and `b-a-g`), and
GeoChat (`b-t-f`) with explicit recipient metadata. Phone and watch identities
are retained: each watch PLI uses that watch's own UID and current identity;
other users' PLI and map points retain their original CoT. Unsupported CoT,
profile/settings messages, Bloodhound commands, and watch-control payloads are
not relayed.

Direct/group chats are delivered only to listed recipient UIDs; **All Chat
Rooms** is the explicit broadcast path. Alert and cancellation CoT is routed
to the phone and connected Gateway peers. Watch-origin event IDs, chat
message IDs, and per-peer recent revisions suppress echoes and duplicate
delivery. The phone uses bounded per-peer coalescing queues and paced BLE
handoff; full queues report backpressure instead of switching to an unsafe
route. The watch validates the session, supported CoT type, chat recipient,
and its own negotiated identity before importing positions, points, alerts,
or chats. Session and duplicate ledgers are transient and clear on disconnect.

The phone map bridge coalesces up to 128 pending point updates. Overflow marks
already-observed points dirty rather than losing their latest state. Paced
100 ms batches rotate through at most 128 observer entries per tick, retrying
current eligible state without another edit or reconnect. Recovery does not
perform repeated full-map snapshots or send deletion messages; removed,
watch-origin, `nevercot`, and unsupported items are excluded. This recovers
map-bridge saturation, not downstream per-peer transport backpressure.
Run `python scripts\tests\test_gateway_map_bridge.py` from the repository root
for deterministic saturation, fairness, coalescing, removal, and restart
regressions. It requires a JDK and compiles the production bridge against
isolated scheduling/ATAK test doubles; it does not validate device transport
or native ATAK export.

Gateway pairing/retry preferences are separate from traditional single-watch
preferences (`wtk_gateway_v1`, `wtk_gateway_retry_<address>`, and
`wtk_gateway_pair_<address>`). Existing traditional reconnect policy still
targets only the exact previously ready bonded peer. Switching purpose stops
old transports, retries, listeners, and callbacks before the new route starts.
No physical-watch acceptance or TAK-server delivery is claimed by JVM/build
validation.

An Android Bluetooth bond is stored pairing/trust, not an active WearTAK GATT
session. Galaxy Wearable reconnecting its own watch connection does not establish
the plugin's separate A11A GATT channel or notification readiness. The existing
Samsung `SystemBondedWatchConnection` discovery/setup/retry coordinator therefore
remains necessary and unchanged. No Samsung toggle or redundant retry loop, and
no new Garmin reconnect behavior, is added.

Validation uses JVM scheduler/preferences seams plus the existing transport,
session, chat, point/alert/settings, Bloodhound, and Garmin tests. These are not BLE
hardware acceptance. Still verify watch app close/restart/out-of-range, Bluetooth
OFF/ON, manual Disconnect across plugin restart, new peer selection/unpair, mode switches,
and repeated plugin stop/start on paired hardware; ensure no old-session payloads
arrive. SDK 36 and local ATAK 5.5.1.8 `main.jar` support offline Java/resource checks;
the declared ATAK 5.8 target cannot be SDK-validated here because its SDK is not
installed (an APK is not an SDK).

Validation (2026-10-12, base `c6cd9d1`): full offline
`:app:testCivDebugUnitTest` passed **144 tests** (124 existing + 20 new reconnect/
preference tests), zero failures. `:app:compileCivDebugJavaWithJavac` and
`:app:processCivDebugResources` also passed using SDK 36 and these existing flags:
`-Ptakdev.plugin=C:\Projects\ATAK-CIV-5.5.1.8-SDK\atak-gradle-takdev.jar`
and `-Ptak.sdk.main.jar=C:\Projects\ATAK-CIV-5.5.1.8-SDK\main.jar`.
This does not claim ATAK 5.8 SDK/API, install, or hardware validation.

GARMIN SETUP

Pair the Garmin watch in Garmin Connect Mobile and install WearTAK-Garmin.
On the watch, select Settings > Network Preferences and enable ATAK Relay.
In the ATAK plugin device pane, select Garmin Connect IQ. The connection
badge and status text report the phone/watch link; finding a watch alone
does not confirm the WearTAK app is installed or that a message arrived.
The relay supports watch markers, emergencies, direct/broadcast GeoChat,
and requested snapshots of nearby ATAK units. New emergency alerts and
nearby-unit snapshots require a valid phone location; cancellation remains
available if the phone loses GPS. Garmin transport is optional and does
not replace the Samsung/Wear OS setup.

The **Garmin Settings** action requires exactly one connected Garmin watch and
requests its current profile before enabling Send. Garmin profile writes are
correlated by a UUID transaction ID and report
success only after a matching positive save acknowledgement and an exact profile
snapshot are both received. A timeout, disconnect, watch change, profile edit,
negative acknowledgement, or mismatched/untagged response cannot confirm a
write. Only callsign, team, role, reporting interval, and the explicit phone
management flag are sent to Garmin; TAK server configuration and P12 credentials
remain on the ATAK phone. One-time provisioning remains the default; ongoing
phone identity management requires the existing explicit opt-in.
Validation on 2026-10-12 passed all 170 offline `testCivDebugUnitTest` tests and
the Civ release Java/resource checks with the local ATAK 5.5.1.8 SDK and SDK 36.
These results do not establish ATAK 5.8 SDK or paired-watch behavior.

GROUP CHAT (WEARTAK-118)

Incoming remote ATAK chat is forwarded with the normalized conversation UID/title,
an explicit `isGroup` flag, and `participantUids` from ATAK's chat bundle. Local direct
conversations normalize to the remote sender; named conversations must resolve to
an actual ATAK GroupContact. All Chat Rooms remains an explicit broadcast room.
Matching watches aggregate different senders in the same group and reply with its
room UID/title and group flag. Legacy watch replies to known group UIDs also work.

Named group replies resolve the current writable GroupContact and its filtered
individual recipients, then call ATAK's
`sendMessage(String, GroupContact, String[], String[])` with the group identity and
recipient UIDs. Unknown/unwritable groups, empty recipients, missing targets, and
missing chat managers fail with logs; they never fall back to broadcast. Only the
explicit All Chat Rooms target uses the existing broadcast-contact API. Direct
GeoChat now resolves a real remote IndividualContact with a GeoChat endpoint and
uses `sendMessage(String, List<Contact>)` with only that contact, preserving ATAK's
native direct conversation/history rather than dispatching private XML to broadcast.
Unknown/self/broadcast-like direct targets are rejected with logs. Native sends run
on ATAK's main thread.

The watch picker offers **All Chat Rooms** to initiate native send-to-all chat and
**Select User for Chat** to select/confirm a person on the map and create/open a
direct conversation in the normal watch chat-room list. Direct replies retain the selected
UID and never fall back to broadcast; map cancellation sends nothing.
The watch pins All Chat Rooms first and Select User for Chat second; direct/named
group rooms follow in descending latest-received-message order, updated on arrival.
The broadcast room is not duplicated among those rooms.

WIRE CONTRACT: version-1 envelopes retain `msgType`, `msgUid`, and `payload`.
Phone-to-watch `chat_received` payload retains `uid` (message ID), `senderUid`,
`senderCallsign`, `msg`, `receivedAt` (epoch milliseconds), and adds
`conversationUid`, `conversationTitle`, `isGroup` (plus group `participantUids`).
Watch-to-phone `chat` payload uses `roomUid`, `roomTitle`, `isGroup`, `msg` or `text`,
and `cs`; direct messages also carry matching `recipientUid`/`replyTo`.
Legacy `rmUid`/`rmTitle` remain emitted/accepted by WearOS. Conflicting destination
or message aliases are rejected; group sends cannot use direct-recipient fallback.
Room titles/callsigns are display metadata, never broadcast-routing defaults.
The WearOS BLE path supports these named groups. The Garmin adapter shares the
normalizer and native direct/broadcast routing but rejects named groups, including
legacy replies resolving to a GroupContact. Its session-correlated `relay_status`
advertises `chatGroups: false` and `reliableDelivery: false`; only a separately
verified group-aware route may enable named Garmin groups.

Named incoming groups must list the paired phone among their recipient UIDs.
Named replies containing ATAK broadcast-like/fake-group or TADIL-J destinations
are rejected because ATAK would otherwise promote those recipients to broadcast.

This supports existing groups, not watch group creation or historical chat sync.
Phone-authored outgoing messages and watch messages attributed to the phone are
not mirrored back; incoming remote messages are relayed while BLE is connected.
BLE enqueue does not acknowledge watch receipt, and no private-messenger integration
is added. Older watch builds still treat received chat as sender DMs; deploy both
updated builds for group-room support.

Hardware acceptance remains pending: with both updated builds and BLE connected,
send to an existing group containing the paired phone from two remote ATAK devices.
Verify one watch room with both sender callsigns; preset and keyboard replies must
reach that ATAK group and its members, not an outsider. Test a simultaneous direct
chat, All Chat Rooms, duplicates/echo suppression, and a new inbox room arriving
while replying. Delete or empty the phone group before replying and verify rejection
logs without broadcast. Repeat reception/replies after BLE reconnect (no offline
history guarantee). Standalone TAK watch tests are documented in the watch README.
Also start with an empty watch inbox: initiate All Chat Rooms and verify native ATAK
broadcast behavior; use Select User for Chat to cancel, then select/confirm one person and send
by preset and keyboard. Verify only that person receives it and ATAK records the
direct conversation. Remove the ATAK contact before sending and verify logged rejection
without unrelated delivery. Phone-authored outgoing messages remain outside watch mirroring.

While a bonded WearTAK watch is connected over BLE, incoming ATAK emergency
CoT (`b-a-o`, `b-a-o-*`, and `b-a-g`) and cancellations are forwarded to
the watch's existing map/Bloodhound alert handling. The BLE envelope uses
`msg_type: "phone_emergency_cot"` with the original CoT XML in `payload.cot`.
The original UID, sender, location, and event times are preserved.
Watch-origin emergency UIDs are tracked in a bounded set to suppress echoes
back to the watch. Forwarding stops when the plugin stops or BLE disconnects;
alerts received while disconnected are not queued for later replay.
Local phone emergency notifications and imported ATAK alert/cancel notifications
are also observed, since successfully imported events bypass fallback CoT listeners.
Duplicate notifications of the same CoT are suppressed. A successful BLE queue
operation is not a watch delivery acknowledgement.

SETTINGS AND P12 PROVISIONING (WEARTAK-90)

This section applies to Bond Single Watch/Configure Single Watch, not Gateway.
An independent watch profile can be provisioned with Configure Single Watch
before using Gateway, or directly on the watch; Gateway itself never reads or
writes its settings. Edit the watch
callsign and server settings, choose the server's P12 file/password if needed,
then tap Send to Watch. Wait for the successful save acknowledgement before
disconnecting. Uploaded credentials stay on the watch and watch settings remain
editable; one-time provisioning does not replace its team or role.

Use WearTAK with this phone defaults to managed use for profiles without a saved
flag; explicit saved watch values are preserved. Managed use copies phone
identity and syncs on reconnect. With heartbeat-capable watch firmware, the editing lock
lasts only while the managed session is responsive (with a 30-second grace period).
Traditional single-watch BLE answers versioned, session-fenced management heartbeats;
Gateway, Garmin, and Samsung routes do not participate. If ATAK exits/crashes or the link
stalls, local watch edits unlock without deleting servers, certificates, or the saved
management opt-in. A fresh managed-settings handshake restores the lock on reconnect.
Update both watch and Companion together; older Companion builds cannot renew this lock.
Android 6/API 23 remains supported through core-library desugaring. Passive GNSS
status/measurement diagnostics require API 24 or later; older phones report that
support as unavailable without registering unsupported callbacks. BLE permission
denial is reported rather than allowed to escape from transport callbacks.
Uncheck it and send again to release management while
retaining saved certificates and current reporting settings. Unpairing also
releases management, but restores the pre-management reporting settings.


_________________________________________________________________
PURPOSE AND CAPABILITIES

(General Description)


_________________________________________________________________
STATUS

(In Progress?  Expected release?  Released?  To Who?  When?)

_________________________________________________________________
RIGHTS

Unlimited Rights granted to TAK Product Center.

_________________________________________________________________
POINT OF CONTACT

Alex Gorsuch on chat.tak.gov or Signal.

_________________________________________________________________
REPOSITORIES

The TAK Forge repository is canonical; GitHub is secondary.
The canonical `develop` pipeline can publish signed CIV builds to a stable
GitHub release. See
[docs/github-release-publishing.md](docs/github-release-publishing.md) for the
protected-token setup and publication behavior.

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

_________________________________________________________________
EQUIPMENT REQUIRED

_________________________________________________________________
EQUIPMENT SUPPORTED

_________________________________________________________________
COMPILATION

Plugin version 5.6.0 targets ATAK 5.6.0 (plugin-api com.atakmap.app@5.6.0.CIV for
the CIV variant). Use JDK 17 to run Gradle 8.14.3 with Android Gradle Plugin
8.13.2, Android SDK platform 36, compile/target SDK 36, and minimum SDK 23.
Java source and bytecode compatibility remain Java 8.

TPP should configure its authenticated takrepo.url/user/password in
local.properties or Gradle properties. The repository resolves takdev 3.+;
an offline/local takdev.plugin JAR must be paired with the ATAK 5.6 SDK.
This plugin has no native sources, so no NDK installation is required.
ATAK-provided core AndroidX libraries remain excluded from implementation;
dependency overrides retain core 1.17.0 except core-viewtree,
lifecycle 2.10.0, and fragment 1.8.9.

Run gradlew.bat :app:testCivDebugUnitTest :app:assembleCivDebug, then
gradlew.bat :app:lintCivRelease :app:assembleCivRelease for release validation.
An archive is source only, not a verified APK or TPP-signed package.
ATAK 5.6 release compatibility and phone behavior require the matching
SDK/repository and device testing.

Production CIV debug resource-link/Java compilation and JVM reconciliation tests
are described below. These local checks do not establish device compatibility
or release packaging/signing.

_________________________________________________________________
DEVELOPER NOTES

THREE-MODE SELECTOR INTEGRATION

The connection-mode button offers three real, mutually exclusive routes:
- Use Samsung Wearable Bond: explicit opt-in to automatic discovery,
  connection and reconnection through the existing Samsung Wearable mode controller.
  The name refers to reusing the Android system Bluetooth bond established via
  Galaxy Wearable, not a Samsung API integration or a new pairing operation.
- Traditional BLE Pairing: manual Scan/Connect and BLE watch settings.
  No automatic Samsung Wearable candidate observation runs in this mode.
- Garmin Connect IQ: starts the Garmin Connect Mobile SDK relay, including
  watch CoT/messages and requested nearby entity snapshots. Install WearTAK
  on the paired Garmin watch and enable Settings > Network Preferences >
  ATAK Relay. Retry Garmin Connect IQ restarts this route if initialization
  or discovery failed. Direct BLE settings are not available for Garmin.

Selecting a different mode stops the prior route (including Samsung Wearable
observation, timers, scans and GATT, or Garmin SDK listeners), clears the
old connected card/settings state, and then starts the new route. Queued
callbacks are generation-guarded, including after switching away and back.
Re-selecting the current mode does not disconnect it. Plugin shutdown stops
all transport activity without clearing the saved selection.

The host preferences file weartak_companion_prefs owns connection_mode.
Saved traditional_ble from the earlier UI-only selector is honored; its
plugin-context preference is read once if the host value is absent. With
no selector value, legacy use_system_bonded_watch=true migrates to Samsung Wearable;
false/absent migrates to Traditional. Unknown mode values fall back to
Traditional, never automatic use. New values are samsung_system_bond,
traditional_ble and garmin_connect_iq. The legacy Samsung Wearable boolean stays
synchronized for downgrade compatibility; preferred BLE device keys are
unchanged. Restart resumes the saved route; Traditional never auto-connects.

Deliberate BLE Disconnect selects/persists Traditional before disconnecting,
so Samsung Wearable retries cannot immediately reconnect. Scan/manual Connect are
available only in Traditional. To stop Garmin, select another mode; plugin
shutdown also stops it. Switching does not delete Android Bluetooth bonds.

MR !14 is reconciled with canonical develop `2bc0bf7`, including WEARTAK-87/90/
99/100/78/112/118. Samsung Wearable bond reuse, exclusive transport ownership and the
existing mode-selector UI remain unchanged. Mode teardown also removes native
chat/emergency/point listeners and closes Bloodhound correlation before starting
the new route. Readiness and point revisions stay scoped to the active BLE session.

Garmin chat uses the same received-payload builder as BLE: `uid` is the message
ID, not the sender or room; `senderUid`, `senderCallsign`, `msg`, `receivedAt`
(epoch milliseconds), `conversationUid`, `conversationTitle`, `isGroup`, and
group `participantUids` retain their wire meanings. Direct and exact All Chat Rooms
messages can be handed to Connect IQ. Named incoming groups are rejected before
SDK handoff; outgoing named groups are rejected before native dispatch. No missing
or conflicting destination can fall back to broadcast. Updated Garmin watches
reject unsupported named groups before their shared outbox using the false capability.
This adapter does not claim relay-result acknowledgements or TAK delivery.

Garmin-origin marker deletion is a separate, explicitly negotiated best-effort
extension (`relay_status.markerDelete: 1`). A `marker_delete` is accepted only for
a marker whose `garmin-marker-` UID was recorded when that authenticated Garmin
watch created it, from the same `watchUid`, with the current `relaySession`,
`messageId`, and a current epoch-seconds `createdAt`. The UID prefix alone does
not authorize deletion. Ownership is bounded to 256 markers and process memory;
after a restart, an unrecorded marker fails closed until recreated by that watch.

An accepted request is handed to ATAK's internal and external CoT dispatchers as
a `t-x-d-d` event with a distinct event UID, the original point, and
`<link uid="TARGET" relation="none" type="none"/><__forcedelete/>`. The sender's
`relay_result` reports only phone handoff/enqueue, never remote TAK delivery.
There is no durable delete outbox or retry; an offline TAK route may miss it.
The local ATAK item is also removed only when its current type and point still
match the point recorded from that watch; a mismatched item is left untouched
and the request is rejected.
Legacy Companion builds do not advertise `markerDelete`, so watches remain
local-only with those peers. This does not change received network entity removal
or WEARTAK-112's paired BLE local-only revision-gated removal.

This scoped Garmin extension does not authorize deleting peer-created UIDs,
non-Garmin map items, or markers merely because their UID has the expected prefix.
Garmin deletion remains unsupported for any marker whose ownership record is
missing, expired by bounded eviction, or belongs to another watch.
Garmin settings use their existing settings adapter. Bloodhound now shares its
existing native ATAK tracking backend with the Garmin Connect IQ transport.
The later narrow live-entity reconciliation described below extends phone-to-Garmin
map/emergency forwarding; watch-origin marker/emergency routing is unchanged.

### Garmin live entities / emergency lifecycle (narrow MR !15 reconciliation)

Based on canonical `develop` `c729e014` after MR !19, not the older combined MR !15
branch (`8a18f210`). Traditional BLE auto-reconnect remains default-off, Traditional-only,
persisted in host preferences; Samsung bond coordination and all current chat,
provisioning, BLE alert/point/Bloodhound and paired-local deletion policies remain intact.
There are no layout, picker or preference changes.

One map subscription observes add/import/remove, point, metadata and type changes.
Each connected Garmin application link gets its own fenced handoff and bounded
50-entity deduplication cache. Link readiness sends an initial nearest-first snapshot
(at most 50); `entity_sync_request` uses the same normalization and replies only to
the requesting link. Live changes use `msgType: entity`; snapshots use `entities`
with `payload.entities`. Dictionary metadata includes callsign/team/role,
`senderUID`, original `time`/`start`/`stale`, and emergency category/active/cancel.
The canonical Garmin receiver assigns `__source: phone-relay`.

Invalid, zero-placeholder or explicitly expired non-alert positions are rejected.
A snapshot requires a valid, non-expired phone self position, without last-fix fallback.
Active alerts with unusable coordinates carry no coordinates: the Garmin receiver
can show unknown location or retain only its own prior same-source valid location.
Cancellation does not require a fix; it carries `state: CANCEL`, `active: false`
and `emergency.cancel: true`. Expired alerts keep their original stale deadline for
the watch's stale display rather than being fabricated into deletion/cancellation.
Self PLI, tracked watch-origin alerts and Garmin-origin points are not echoed.

Stop/mode switch removes the exact map/item observers and existing emergency
broadcast/fallback listener. Captured queued updates and snapshot replies cannot
move onto a reconnected, replacement or different watch link. Item removal drops
pending ordinary-point sends, but does not send generic `marker_delete`, external
deletion, or invented emergency cancellation. Garmin retains its existing bounded
map and five-minute ordinary-entity prune; alerts retain Garmin's source/stale rules.

This is SDK handoff only: `reliableDelivery: false`, named groups fail closed,
and there is no watch acknowledgement or guaranteed emergency delivery.
MR !15's settings ACK + snapshot transaction and broader protocol/build/test-provenance
bundle are **not** imported or declared complete. No watch installation is performed.

Focused CIV JVM validation covers normalization, snapshot bounds/order, listener
start/stop/restart, actual mode switches, removal/cancellation/expiry, SDK-scoped
dictionary handoff, link replacement and retired callbacks, plus existing BLE
reconnect, chat, settings, alert, paired-delete and Bloodhound regressions.
Production CIV Java compilation and resource linking use Android SDK 36 and the
local ATAK 5.5.1.8 development plugin/main.jar with the offline flags below.
The focused run passes 123 JVM tests (18 newly added). The native ATAK observer/
broadcast adapter is compile-checked, not exercised in an Android/ATAK runtime by
the fake-source/SDK JVM tests. These checks do not validate ATAK 5.8 APIs or physical
Garmin/phone operation.
Hardware acceptance remains required: update nearby units/points, issue/cancel/expire
phone and remote emergencies (including loss of fix), reconnect/replace the watch
with queued callbacks, request a snapshot with two paired watches, switch all modes,
and confirm no echo, third-device deletion, named-group broadcast or false delivery ACK.

Reconciliation validation uses Gradle 8.14.3/AGP 8.13.2, Android SDK 36 and
the installed ATAK 5.5.1.8 development plugin/main.jar:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --offline "-Ptakdev.plugin=C:\Projects\ATAK-CIV-5.5.1.8-SDK\atak-gradle-takdev.jar" "-Ptak.sdk.main.jar=C:\Projects\ATAK-CIV-5.5.1.8-SDK\main.jar" :app:testCivDebugUnitTest :app:compileCivDebugJavaWithJavac --console=plain
```

The production CIV debug resource-link/Java compile and all 124 JVM tests pass
(zero failures/errors, including 11 Garmin chat/handshake regressions)
against that installed SDK. Those historical checks predate the current
ATAK 5.6.0 target. Full target-SDK compatibility,
APK packaging/signing and device acceptance still require the matching TAK SDK
and TPP environment. No SDK, credentials, signing keys or local.properties are
included.

Hardware acceptance is pending: switch between every mode while discovery,
GATT setup, queued writes, settings snapshots/certificate selection or Garmin
callbacks are pending; ensure only the selected transport survives, old callbacks
cannot affect the new session, and Android/Galaxy Wearable bonds survive switching
and disconnect. Repeat BLE chat/groups, emergencies, managed/unmanaged provisioning,
point update/delete and Bloodhound tests after mode switches/reconnect. On Garmin,
verify direct/broadcast inbox/replies, false group capability and rejection of
named groups without fallback, handoff-only status, SDK retry, and entity snapshots.
Confirm a third TAK peer receives no deletion from either watch transport.

### Garmin route display and point tracking

The Garmin adapter exports ATAK `b-m-r` routes as versioned atomic `route`
messages. Watches opt in with numeric `routeVersion: 1` in `relay_hello` or
`entity_sync_request`; older watches receive no route messages. A new capable
link or explicit refresh sends `routes_reset` before replay. Geometry, route
metadata, additions and removals are observed while Garmin mode is active.
Teardown removes those listeners; updates cannot cross transport generations.
Queued changes reconcile against the current authoritative map snapshot, so
delayed work cannot restore deleted geometry; recreation publishes only the
current revision. Invalid geometry is preserved only for its originating map
item, never another server's route with the same UID.

Routes retain source server ID and route UID separately. Each message contains
the route name, ordered latitude/longitude vertices with optional names, and
optional mission name/sender UID. Limits match the watch: 8 routes, 2–64 vertices
per route, 128 vertices total, and 6,000 UTF-8 bytes per serialized envelope.
Text limits in UTF-8 bytes are server ID 64, UID 128, route/point name 64,
mission/sender UID 128, and error 256. Invalid or oversized updates preserve the
last valid geometry and report `routes_error`; capacity omissions also report
an error once per continuous capacity condition (and again after explicit refresh).
Rejected warning handoffs retry on the next reconciliation. Routes are never
silently simplified. Removal sends `route_remove`
for that source/UID only. Freed route/vertex capacity is filled automatically
after removal or shrinking geometry, retaining published routes first and
selecting new candidates in source-server/UID order. Immediately rejected SDK
updates and removals retain retry state for the next map/link reconciliation;
ordinary reconciliation does not reset watch-local route tracking. No raw route
XML is logged.

On Garmin, **Map > Layers > Routes** lists every vertex and exposes point
details and local Bloodhound range/bearing/proximity alerts. Overlapping map
points use the existing selection menu. Route tracking is local to the watch:
synthetic vertex identities are not sent to native ATAK Bloodhound, which still
requires an existing point map item. Ordinary-point synchronized Bloodhound
is unchanged. Refresh resets route tracking and repairs a missed SDK handoff;
there are no end-to-end delivery acknowledgements.

Focused checks: `GarminRouteProtocolTest`, `GarminRouteForwarderTest`,
`GarminRouteSourceCacheTest`, existing
Garmin Connect IQ/entity forwarding tests and `BloodhoundSyncTest`. Production
Java compilation uses the locally available ATAK SDK. The debug-only emulator
action `com.weartak.companion.SIMULATE_GARMIN_ROUTES` requires Wi-Fi and mobile data
disabled and exercises native ATAK route addition, ordered coordinates, geometry
edits, renaming, removal and listener teardown using the production route source
and an in-memory watch link. Android XML parsing rejects DTD/entity declarations
and external resolution without requiring unsupported desktop JAXP feature flags.
Native observation is simulator-verified; target-SDK packaging and physical
Garmin/ATAK delivery remain acceptance requirements.

### WEARTAK-128: Garmin Bloodhound synchronization

Garmin Connect IQ negotiates Bloodhound version 1 in the existing session-bound
`relay_hello` / `relay_status` exchange. This enables the same native ATAK
Bloodhound coordinator used by the existing BLE route; no new tracking screen,
menu, or reconnect behavior is added. The Garmin route is active only in Garmin
mode and only for one current Connect IQ application link. Commands and results
are sent back on that exact link; a second connected Garmin watch disables
Bloodhound negotiation until the link is unambiguous.

The Garmin `msgType` values are `bloodhound_control` and
`bloodhound_control_result`, with the same `start`, `update`, and `stop` actions
as the existing coordinator. Garmin frames add `relaySession`,
`bloodhoundVersion: 1`, and UUID `command_id`; acknowledgements echo those values
plus UUID `session_id`, `target_uid`, and `action`. The adapter rejects malformed
identity, unsupported versions/actions, stale relay sessions, invalid locations,
and acknowledgements that do not exactly match a pending command. Start and
update targets require finite, in-range coordinates. Link or mode changes retire
the session and discard pending correlation; nothing is replayed after reconnect.

The companion starts native ATAK Bloodhound only for an existing map
`PointMapItem` with a valid location. It does not fabricate a marker for a
missing UID, overwrite an ATAK-owned CoT target from a watch update, cancel an
alert, or send a TAK-server deletion. A successful result confirms the native
tracking action on this phone only; it does not claim delivery to TAK servers or
other peers. Garmin does not gain P12/server settings, reliable TAK delivery, or
named-group chat capability.

Focused CIV tests cover the Bloodhound coordinator, Garmin wire normalization,
session/version fencing, command/result correlation, and capability negotiation.
Validation uses Android SDK 36 and the installed ATAK 5.5.1.8 local development
plugin/main.jar; it does not establish ATAK 5.8 API compatibility or paired
hardware behavior. Hardware acceptance must verify native ATAK start/update/stop
from both Garmin and phone, rejection of missing/self targets and stale replies,
disconnect/mode changes without replay, and unchanged Samsung/Traditional BLE
and local alert/marker lifetimes.

SAMSUNG TRANSPORT DETAILS (SELECTOR-OWNED)

Samsung Wearable mode is a persisted explicit opt-in, not the new-install default.
Enable Bluetooth, grant Nearby Devices permissions to the
ATAK host, and pair the Samsung watch through Galaxy Wearable first.
This option reuses the existing Android Bluetooth bond established through
Galaxy Wearable without changing or removing that pairing.
Run the WearTAK watch app so its A11A BLE service is available.
On Android 6 through 11, BLE scanning requires the ATAK host's location
permission and legacy Bluetooth permissions. Android 12 and later use
Nearby Devices permissions instead.

Discovery uses the previously identified WearTAK address if still bonded;
otherwise it scans for bonded devices advertising WearTAK's A11A service.
A Samsung device name alone is never sufficient. This automatic route
never creates or removes a bond. The original Scan/manual Connect route
is retained and may request a new BLE bond for an unbonded selection.

The selector controls WearTAK use, not pairing or Galaxy Wearable.
Samsung Wearable mode can be selected before a candidate is available; it reports
unavailability and retries, rather than claiming a reachable watch.
While Android's Bluetooth service is off, the last confirmed bond is retained
and reverified when Bluetooth returns (or invalidated by a bond-loss event).
Without Nearby Devices permission the bond cannot be verified.
The selected mode is never
cleared by transient unreachability, Bluetooth-off, permission loss, or bond
loss; retry discovery continues until the user changes mode or disconnects.
Changing mode cancels discovery/setup/retries and disconnects only WearTAK's
GATT session, never Galaxy Wearable or the system bond.

CONNECTED, the connected card, and settings requests are enabled only
after service discovery and successful A11B notification subscription
(CCCD write). Discovery is bounded to 35 seconds and GATT setup to
25 seconds. Failures/disconnects retry at 2, 4, 8, 16, 32, then at most
60-second intervals; successful readiness resets the backoff. Bluetooth
disabled and missing permissions are reported, and periodic retries recover
after Bluetooth/permissions are restored. This does not request permissions
on the user's behalf.

SystemBondedWatchConnection.start()/stop() are the explicit activation seam:
the selector stops this controller before activating another transport and
starts it only when Samsung Wearable is selected. observe() remains available to
other callers but is not used by this selector, so Traditional and Garmin
have no competing Samsung Wearable observation.

BLE settings are isolated per device/session. Mode changes, manual scans/
connections, disconnects and trust loss clear the server adapter, editors,
certificate display, callsign and snapshot state. Send stays disabled until
this session receives a snapshot containing takServerList and reportIntSecs;
partial settings_changed messages alone do not unlock a new session. Queued
previous-session settings and certificate-picker callbacks are discarded.

For end-to-end bond preservation use the corresponding WearOS Samsung
system-pairing watch version (wearos-tak-civ-samsung-system-pairing,
commit 9a90f83 or its successors). A TPP-signed Companion plugin cannot
update or replace the watch app. The Companion source ZIP is for TPP
build/signing and testing, not certification or a directly installable APK.
Build configuration and plugin version now target ATAK 5.6.0.
Behavior on the requested ATAK 5.8 phone is not established by this 5.6 target
or by local compilation/unit tests.
TPP must provide its normal TAK SDK/build credentials; no local SDK,
local.properties, generated outputs, or signing key is included.
### Phone-owned route navigation over bonded WearOS BLE

Starting native ATAK route navigation in Traditional or Samsung Wearable mode
starts the same route on the bonded watch. This is separate from Bloodhound:
the phone remains the authority for waypoint advancement. Objective changes
(including manual next/previous), geometry and route-detail edits are forwarded.
The observer also reconciles every 1.5 seconds to recover replaced navigation
managers, reconnects and rejected writes. Setup and Gateway modes do not enable
this single-watch BLE relay.

Newline-delimited JSON uses `msg_type=phone_route_navigation` with payload
`version: 1`, UUID `sessionId`, increasing `sequence`, `action` (`start`,
`progress`, `stop`) and `routeUid`. Every start/progress includes complete
`routeCot` (`b-m-r` with matching UID) and zero-based `targetPointIndex` into
the raw CoT link vertices. Geometry and objective are applied atomically:
invalid vertices/counts are rejected, never filtered or renumbered. Bounds are
10,000 vertices and 60 KiB per UTF-8 JSON frame after escaping. Errors are logged;
no truncation or simplified replacement route is sent. Duplicate unchanged
snapshots are suppressed. Reconnect sends a fresh start snapshot with the same
navigation session and a higher sequence; a new ATAK navigation uses a new UUID.
Writes are connection-generation fenced, and a queued write is not an
acknowledgement of watch delivery.

A matching stop ends only the phone-owned watch session. `completed: true` is
sent on native final-vertex arrival, or when navigation stops after that native
arrival; cancellation, route removal or mode shutdown sends `completed: false`.
Companion never infers arrival from watch GPS or advances a waypoint itself.
ATAK 5.6 uses its route-method arrival bubble: defaults are 10 m walking/swimming,
20 m driving, 100 m watercraft and 1,000 m flying. `waypointBubble.<method>`
preferences override these defaults; the native navigation loop adds the phone
position's circular error when available. Intermediate progression is ATAK's
arrival/departure logic, not a new Companion distance threshold. These details
are verified against the 5.6 SDK (`RouteNavigator.getBubbleRadius`, its navigation
loop, `RouteNavigationManager`, and `RouteNavigationViewModel.onArrivedAtPoint`).
Paired hardware acceptance still needs a corresponding watch build: start,
next/previous, edit, disconnect/reconnect, cancel, and final arrival.

The reverse command is `msg_type=watch_route_navigation`, payload `version: 1`,
UUID `requestId`, watch-proposed UUID `sessionId`, `action` (`start` or `stop`)
and `routeUid`. Start also supplies raw checkpoint `targetPointIndex` and can
include full `routeCot`. Existing native geometry must match (raw count and
coordinates within ATAK's export precision); it is never overwritten. A
watch-only route is validated and imported through ATAK's local importer, not
dispatched to network/server connections. Checkpoint UID collisions with other
phone map items are rejected. Stops must match the current native route and
session. The acknowledgement `route_navigation_ack` echoes `requestId`,
`sessionId` and `ok`, with an explicit `error` on failure. Native snapshots reuse
the proposed session ID, so stopping before an acknowledgement can fence late
starts. Request receipts and ended-session tombstones are bounded; duplicates
never restart navigation. The watch uses an eight-second acknowledgement/state
timeout and sends a scoped stop on timeout.

Garmin uses the same command/acknowledgement names under `msgType`, with
`relaySession` in every payload and `serverId` on starts. It must advertise
`routeVersion: 1` and `routeNavigationVersion: 1` in `relay_hello`; Companion
confirms navigation version in `relay_status` only for an exclusive current
watch link. Start requires an existing native route from the same server
identity. Every start/progress sends the existing, capacity-accounted Garmin
`route` snapshot before `phone_route_navigation`; state includes `serverId`
instead of XML. Stops retain that same server identity. Existing 8-route,
64-vertex-per-route, 128-total-vertex and message-size bounds are not bypassed.
Unavailable, mismatched or capacity-rejected routes fail explicitly. Garmin
does not receive XML or import watch-only geometry, and existing Garmin
marker/PLI forwarding is unchanged.
Garmin route snapshots also retain optional `method` (ATAK's serialized route
method) and per-vertex `type` (`b-m-p-w` checkpoint, `b-m-p-c` control; missing
means control), without filtering vertices. These support standalone watch
guidance; bonded progression still comes only from native ATAK.

Paired BLE Bloodhound synchronization (WEARTAK-78)

While connected to a bonded WearTAK watch, starting native ATAK Bloodhound
shares the same CoT target UID with the watch. A watch start selects native
ATAK Bloodhound tracking (not route navigation). Ending tracking on either
device ends that shared tracking session on both. This never cancels the
underlying alert or deletes its marker. The target must already exist as a
valid ATAK point; missing targets are rejected and never synthesized.

The newline-delimited JSON protocol uses `msg_type=bloodhound_control` and a
payload containing `action` (`start`, `update`, or `stop`), UUID `session_id`,
and `target_uid`. Starts and updates carry `target_callsign`, `lat`, `lon`, and
optional `hae`; coordinates are validated before a command is applied. Replies
use `msg_type=bloodhound_control_result`, with `session_id`, `action`, Boolean
`ok`, and optional `error`. Garmin Connect IQ adds a current `relaySession`,
`bloodhoundVersion: 1`, and UUID `command_id`, echoed in its result to fence
replaced commands and links. Local and watch failures are shown as
ATAK toast notifications and logged. Native event echoes and duplicate starts
are suppressed; stops require both the current session ID and target UID.
Repeated stops are acknowledged only for the original session/target pair.
Garmin command results correlate command, session, action and target IDs.
Missing results time out after five seconds and failures are visibly reported;
a queued BLE/Connect IQ write alone does not confirm synchronization.
Simultaneous starts awaiting acknowledgement
converge on the lexicographically smaller session ID. The losing incoming start
receives `ok=false`, `error=Superseded by simultaneous tracking start`; a late
negative result or timeout for the losing local start cannot clear the winner.
`action=update` carries the same session ID, target UID, callsign and coordinates
Native target point/callsign changes publish updates; matching incoming updates
never overwrite the phone's authoritative map point, restart tracking, or echo.
Stale, mismatched and disconnected updates are rejected.
Non-bridge point/callsign edits relinquish ownership immediately, before
the import-complete event, so companion snapshots cannot overwrite a newly
authoritative source. ATAK's same-UID MarkerImporter updates the existing marker
in place; source handoff neither deletes it nor stops/restarts native tracking.
Disconnect/unpair clears shared correlation, without stopping normal local
tracking; reconnect never replays or resumes the disconnected shared session.
Unpaired native ATAK behavior is unchanged.

ATAK 5.5.1.8 SDK integration, verified against local `main.jar` bytecode:
`BloodHoundTool._onBeginLink` sets target metadata `hounding=true`;
`_onEndLink` removes it. `MapItem.addOnMetadataChangedListener("hounding", ...)`
observes actual tracking edges, not dialog/HUD visibility or generic tool
activation. Existing and newly added map items are observed. The host
`MapActivity.getMapComponent(BloodHoundMapComponent.class)` supplies the native
component. ATAK exposes no public tool getter, so a guarded reflective lookup
of that component's protected `_buttonTool` field is necessary on this SDK.
`BloodHoundTool.getRunning()` and `getEndItem()` confirm current native state.
Its public `getHoundReceiver().onReceive(...)` is invoked on the main thread
with `BloodHoundTool.BLOOD_HOUND` and `uid`: this starts a new target or toggles
an existing target off. Synchronous invocation prevents a queued toggle from
affecting a different target after the user switches tracking. Missing or
incompatible host APIs produce a negative result instead of manipulating
alerts. Future ATAK SDK versions must revalidate this compatibility seam.
Native `onButtonClicked()` ends active tracking through `stop()`, which calls
`_onEndLink()`; the watched metadata edge therefore includes the native close
action as well as UID-toggle stops and target replacement.

Focused local JVM coverage: `:app:testCivDebugUnitTest --tests
com.weartak.atak.weartak_companion.plugin.BloodhoundSyncTest`.
Device validation still requires bonded watch + ATAK hardware: test native
and watch starts, both tracking-end actions, fast target replacement, stale
stop rejection, unpair/disconnect, reconnect without replay, and unchanged
alert/marker lifetime.
### WEARTAK-100: live phone point relay

The companion forwards ATAK marker creation and subsequent point, type, and
metadata changes to the active bonded watch after notification subscription
completes (services ready). Existing map points are observed for future edits,
not automatically uploaded. Disconnect, reconnect, or switching watches does
not trigger an offline replay or bulk synchronization.

The envelope remains:

```json
{"msg_type":"phone_marker_cot","payload":{"cot":"<event ...>...</event>"}}
```

Supported types are all `a-*` atoms and SPI `b-m-p-s-p-i`.
Self/PLI, emergency, non-exportable items, shapes, routes, and
unsupported types are excluded. Exported identity must match the map item,
coordinates must be in range, HAE/CE/LE finite, and all timestamps positive.
The ATAK SDK export preserves UID, type, point and detail. For received tracks,
the original network `time`, `start` and `stale` replace the exporter's synthesized
timestamps, on both WearOS and Garmin. An additive communications observer retains
source timing in a bounded 512-UID cache and item metadata; it does not consume or
replace ATAK import processing. ATAK's receipt time and inflated stale grace period
are not used to reconstruct source expiry. Expiry-only source updates are forwarded,
and expired source deadlines are never extended. Persistent locally created points
keep ATAK's default lifetime. If an existing imported track has no retained source
expiry (for example, after starting the plugin), export waits for its next source
update rather than inventing a one-year expiry. User/contact PLI routing is unchanged.

Watch imports carry an internal ATAK import-source flag. The resulting marker
snapshot suppresses immediate echoes and identical later notifications, not
every future use of that UID. A phone edit to the point or exported details
releases suppression. Failed imports have a bounded five-second pending guard.
Duplicate edit notifications are coalesced; all map, point, metadata, and type
listeners and pending callbacks are removed on plugin stop or item removal.
Queued BLE chunks and callbacks are session-scoped so they cannot transfer an
old watch's pending data to a new session. A successful enqueue is not a watch
delivery acknowledgement; asynchronous GATT failures remain reported as errors.

### WEARTAK-112: explicit shared-point deletion

**Local paired-device mirrors only.** Watch deletion removes only the eligible
local phone map copy; phone local deletion echoes only to the eligible paired-watch
copy. No deletion is sent to TAK servers, multicast, Ditto, SITX, plugins/mesh, or
other devices. Incoming mirrored removal cannot re-echo. Watch Clear/Last/All is
immediate local removal even without BLE and no longer generates network clear CoT.

ATAK 5.5.1.8 SDK inspection shows that local
`MapItemDeleteReceiver` calls `removeFromGroup()`. `CotEventFactory` does not
turn removed items into expired events; its default marker exporter creates
fresh timestamps. ATAK's remote deletion importer instead accepts a
`t-x-d-d` task with a target `<link>` and optional `__forcedelete`, which is not
the shared-point contract. Neither importer nor external dispatcher is invoked
for the new deletion envelope; the companion uses local `removeFromGroup()`.

Both peers add `payload.revision` (opaque UUID) to live marker envelopes without
rewriting the XML or existing marker fields. Deletion references that exact revision:

```json
{"msg_type":"marker_delete","payload":{"targetUid":"shared-point-uid","revision":"previously-exchanged-revision"}}
```

Only supported, non-PLI/non-emergency markers exchanged in the current bonded,
services-ready BLE session qualify. A matching revision plus unchanged exported
content is required before incoming deletion can remove an observed ATAK marker.
Phone edits replace the shared revision and relinquish watch-echo suppression.
Local ATAK removal emits a deletion only for an unchanged shared marker. Incoming
removal suppresses the outgoing echo and releases listeners/state; the existing
Bloodhound removal listener stops tracking the deleted target.
Unknown UIDs, malformed tokens, duplicates, unsent edits, and ended-session frames
cannot delete unrelated/native or newer points. Outcomes/failures are logged
without sensitive payloads.

The watch removes only its companion-owned map/navigation copy at or before the
revision's original event time, retaining other-source copies and a replay watermark.
For watch-origin points, the original local point is also removed unless a local
edit is pending. Watch received-marker Delete retains its existing UID-wide local
UX, but relays only a selected companion-source point. Bloodhound menu dismissal
is not point deletion. Legacy stale BLE marker clears are no longer imported as
fresh markers; watch point deletion never generates a network clear.

There is no fabricated expiry, network deletion broadcast, offline replay,
bulk sync, or deletion on disconnect. Transport enqueue is not delivery confirmation.
Deletions are acknowledged in both directions with a `marker_delete_ack` envelope
(`targetUid`, `revision`, `result` of `applied` or `rejected`) over the same BLE link.
After handling a watch deletion the plugin returns one for the watch to match. For a
deletion the phone sends, it records the watch's reply for that exact uid, revision and
session, reports "not acknowledged" after 10 seconds, and ignores unknown or duplicate
acks. An ack reports only the peer's local outcome, not delivery to any TAK server, and a
peer that predates it never answers.
Update both apps together; older peers can still receive points but have no deletion
authorization token. Validate both directions on paired hardware before deployment.

Validation: `PhoneMarkerRelayTest` exercises the production forwarding policy,
exact envelope/XML preservation, create/update deduplication, filtering,
watch-echo release, disconnect/session rejection, removal/stop state, and write
failures. The production local-removal policy is also exercised with synchronous
removal events to verify that incoming deletion invokes only local removal and
cannot emit a BLE echo. SDK `javap` inspection confirms `MapItem.removeFromGroup`
delegates to local `MapGroup.removeItem`, not a CoT dispatcher/importer. These JVM
tests do not establish ATAK/BLE hardware end-to-end behavior or side effects from
unrelated installed plugins.
Paired-device acceptance should include move/title/detail edits, watch-origin
edits, unsupported PLI/emergency/shape items, disconnect/watch switching, and
plugin stop/start. Specifically, create a phone point and start watch Bloodhound,
delete in ATAK, and verify the companion-source point/target disappears and watch
navigation stops. Delete a shared watch-dropped point on the watch and verify
ATAK marker/tracking removal. Edit that watch-origin point in ATAK and repeat both
directions. A same-UID TAK copy on the watch must survive phone deletion. Replay an
old deletion after a newer edit or reconnect: newer/unshared points must survive,
without echo deletion or startup replay. These are pending hardware acceptance
checks, not claims of device validation. With a third TAK/network peer displaying
the same point, delete on either paired device and verify that third peer retains
its copy and receives no deletion packet.

CHAT ECHO SUPPRESSION (WEARTAK-158)

In single-watch purpose (Traditional BLE, Samsung Wearable, or Garmin Connect IQ),
the plugin records the exact CoT identity of chats sent through the watch's
`sendChat` path. Its incoming wrapper drops only an exact matching `b-t-f` echo
before `GeoChatService` can store it, mark it unread, or notify. Direct, group,
and All Chat Rooms sends share this path; new messages, ordinary phone sends,
and delivery/read receipts are forwarded unchanged. Matching is bounded to 256
identities and ten minutes and fails open if the server changes the CoT identity.
Gateway purpose is not intercepted.

The debug emulator diagnostics exercise the production filter. With emulator
Wi-Fi and mobile data disabled, send the
`com.weartak.companion.SIMULATE_CHAT_ECHO_TEST` broadcast. It sends an offline watch
chat, replays two exact copies through ATAK's receive path, checks sent history and
unread count, then injects a new message from another sender and checks that unread
count increases. It also checks that an ordinary phone send is not filtered and
that chat still works after stopping and restarting the filter. Keep the chat
conversation closed to verify its notification.

The plugin uses ATAK 5.6's public listener registration methods; it does not modify
ATAK core or global notification preferences. On plugin stop or when switching to
Gateway purpose, it removes the wrapper and restores the original
`GeoChatService` listener. Failed installation rolls back to the original listener
and logs that watch echoes may notify ATAK.

**Known lifecycle risk:** ATAK exposes no atomic listener replacement or selective
notification veto. The public remove/add operations are separate; an incoming chat
at that transition can be missed. This is minimized by doing the swap synchronously
on plugin lifecycle transitions, but cannot be eliminated through the plugin API.
The exact filter has simulator and unit coverage; direct/group echo behavior with
real ATAK servers, plugin lifecycle transitions under live traffic, and BLE/Garmin
hardware delivery still require paired-device acceptance. The simulator broadcast
and observer are debug-only; the production filter is included in release builds.

## WEARTAK-158 / 160 / 161 release candidate

This candidate combines selective watch-chat echo suppression, remote TAK user
map relay, and acknowledged release of phone-managed watch settings on intentional
disconnect. The chat wrapper uses ATAK's public listener API and has a small,
non-atomic listener replacement window. Emulator-only debug checks cover chat
listener lifecycle, synthetic remote-user snapshots and updates, and release
state-machine acknowledgements; they do not replace paired-watch interoperability
testing. Unlock requires watch firmware that supports `release_phone_management`.
