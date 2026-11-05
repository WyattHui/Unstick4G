# Unstick4G — spec

A small Android app that gets my phone back from "stuck on 2G" to 4G, and logs when and where it happens.

Personal tool for one phone, built small. Public repo, MIT licence, Kotlin.

## The problem

- Phone: Samsung Galaxy A60 (confirm exact model number in Settings > About phone), Android 11 / One UI 3, Snapdragon 675. China/HK-market model. Not rooted.
- UK network. 3G is switched off in the UK, so when 4G drops the phone falls straight to 2G.
- The A60 has no LTE Band 20 (800MHz), the UK's main rural 4G band, so on country roads it genuinely loses 4G. That part can't be fixed in software.
- The fixable part: after coverage returns, the phone **stays on 2G** until I intervene.
- Manual fixes that work (verified by me):
  1. Settings > Mobile networks > Network mode: "2G only", then back to "4G/3G/2G (auto connect)" → phone returns to 4G.
  2. Flight mode on, then off.

## Milestones

- **v1 (build now):** one-tap Fix (Quick Settings tile + in-app button), result feedback, passive background logger, location logging during drives, CSV export, CI.
- **v2 (later, after 1–2 weeks of v1 logs):** automatic fixing. Thresholds come from the logged data, not guesses. See "v2 ideas" at the end.

## Privilege: Shizuku

- All privileged calls go through **Shizuku** (shell UID, started via Wireless Debugging, no root). Use the Shizuku API (UserService or binder wrapper).
- Shizuku stops on every reboot. That's acceptable (I reboot about monthly). Handle it well:
  - After boot, if Shizuku isn't running, post a notification: "Shizuku is off — tap to start it" (opens Shizuku).
  - The tile shows a greyed-out "Needs Shizuku" state and opens Shizuku when tapped, rather than failing silently.
  - If Shizuku is running but this app lacks its permission, prompt for it.

## v1: the Fix action

One "Fix" action, used from the tile or the in-app button:

1. **Network-mode flip (preferred).** It's what worked for me, and it leaves Wi-Fi and Bluetooth alone. Read the current preferred network mode, set GSM-only, wait ~3–5 s, then restore the **exact original value**. Likely route: hidden `ITelephony.setPreferredNetworkType(subId, type)` (or `setPreferredNetworkTypeBitmask`) called as shell via Shizuku. Use HiddenApiBypass or hidden-API stubs. Don't hard-code mode values: this firmware's "auto" value may include CDMA/TD-SCDMA.
2. **Flight-mode toggle (fallback)** if the flip isn't available on this device: `cmd connectivity airplane-mode enable`, wait ~3 s, `cmd connectivity airplane-mode disable`.

Put both behind one interface (e.g. `NetworkKicker`) so the method can be chosen at runtime. Step 0 decides which actually works.

Safety rules:
- Always restore the original network mode, including after a crash. Persist a "flip in progress + original value" record before flipping. On every app/service start, restore if that record exists.
- Only act when the current mode is the normal auto mode. If I've deliberately set something else, do nothing and say why.
- Never act during a call (call state must be idle). Show a message instead.

Feedback:
- After the Fix, watch the network type for up to 30 s, then notify either **"Back on 4G ✓"** or **"Still on 2G — probably no usable 4G here."**

## v1: passive background logger

- Foreground service with a low-priority persistent notification showing the current network type.
- Starts on boot. Event-driven via `TelephonyManager.listen(PhoneStateListener, …)` (API 30: `TelephonyCallback` isn't available), with a light 60 s poll as backup. No unnecessary wakelocks.
- **Never takes action in v1.** It only records.
- "On 2G" = data or voice network type is GPRS, EDGE or GSM.

### Location: only while driving

- "Driving" = connected to my car's Bluetooth **and** the phone's location switch is on. I sometimes turn location on while walking for navigation, so the location switch alone is **not** a driving signal. Never log location at home or on foot.
- Car device: chosen in settings from the list of paired Bluetooth devices, stored by MAC address. Detect connect/disconnect via Bluetooth ACL broadcasts.
- While driving: record a location sample every ~30 s and on every network-type change, plus nearby LTE cells from `getAllCellInfo()` (cell ID, band/EARFCN if available, RSRP).
- Use `LocationManager` directly. **Don't depend on Google Play Services**: China/HK firmware may not have it.
- Permissions: fine location, plus background location ("Allow all the time"), since the service runs from boot. The app itself gates location on car Bluetooth, so that's acceptable. Verify what Android 11 actually requires.

## v1: data and export

Local Room database; nothing leaves the phone unless I export it.

- `network_events`: timestamp, network type, signal strength (dBm / RSRP), in_drive (bool)
- `drives`: start, end (Bluetooth connect/disconnect)
- `location_samples`: timestamp, lat, lon, accuracy, speed, drive_id, network type, LTE neighbour summary
- `fix_attempts`: timestamp, method (flip / flight mode), type before, type after, seconds on 2G before the tap, result, duration

Other rules:
- Retention: auto-delete anything older than **90 days**.
- **Export:** a button that writes one CSV per table (zipped) and opens the share sheet, so I can analyse it in Python on my laptop.

## v1: UI

One screen, Jetpack Compose:
- Status: Shizuku (running / permission), logger on/off, current network type, driving yes/no.
- Fix button plus last result.
- Settings: car device picker, Fix method (auto / flip only / flight mode only).
- Recent log: last ~50 network events and fix attempts.
- Export CSV button.

Plus a Quick Settings tile for Fix, with the states: ready / needs Shizuku / working / result.

## Tech

- Kotlin, Jetpack Compose, Room. minSdk 30, single module, sideloaded APK (no Play Store).
- No root, no analytics, no network calls.

## CI and tests

- GitHub Actions on every push and PR: build the debug APK, run unit tests, and upload the APK as a downloadable artifact. Add a build-status badge to the README.
- Unit tests for the logic that matters most:
  - restore-original-mode rule (including the crash-recovery record)
  - "time on 2G before tap" calculation from `network_events`
  - only-log-location-while-driving gating
  - CSV export format

## README

What the app does and why it exists (A60 lacks Band 20 + UK 3G switch-off → falls to 2G and stays there), Shizuku setup steps, permissions and why each is needed, privacy note (location only while connected to the car, data stays on the phone), and a screenshot.

## Workflow

- **Phase A — cloud session (from my phone):** scaffold the project, CI, logger, database, UI, tile, and the `NetworkKicker` interface with both implementations. Can't touch the phone from here, so skip Step 0. Every push gets a CI build.
- **Phase B — on my laptop with the phone plugged in:** do Step 0, wire up whichever method works, install, and test.

## Step 0 — verify on the device (Phase B, over adb)

1. `getprop ro.product.model` and `getprop ro.build.version.release`
2. `dumpsys telephony.registry` — find where the current voice/data network type is shown.
3. `settings list global | grep -i preferred_network` — note the current value(s) and the subId suffix.
4. `cmd connectivity airplane-mode` — should print enabled/disabled. Test enable/disable.
5. Find a shell-level way to flip the network mode on this exact device and confirm it works (watch the network type change). If no shell route works, tell me, and default the Fix method to flight mode.
6. Check whether Google Play Services is present (`pm list packages | grep com.google.android.gms`). Informational only; the app must not depend on it.

## Acceptance (v1)

- Fix from the tile works and the original network mode is always restored.
- Killing the app mid-flip and reopening it restores the original mode.
- No Fix during a call.
- After a reboot, the "Shizuku is off" notification appears and the tile shows "Needs Shizuku."
- On a real drive: network events and location samples are logged; nothing is logged with location when not connected to the car.
- Export produces readable CSVs.
- CI is green and the APK artifact installs on the phone.

## v2 ideas (not now)

- Auto-fix when on 2G for N minutes, with N taken from the v1 logs, plus a cooldown and exponential backoff.
- Trigger on car disconnect: "just parked and on 2G" → fix immediately.
- Smart trigger: fix as soon as an LTE neighbour with decent RSRP is seen while on 2G.
- While driving, prefer the flip over flight mode so car Bluetooth isn't dropped.
- A map of 4G dead spots on my regular routes, from the exported data.
