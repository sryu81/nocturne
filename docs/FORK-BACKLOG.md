# KStars fork backlog

Real features Nocturne can't reach because the EkosRemote fork (`/home/soo/cc/repo/kstars/kstars/ekos/ekosremote/`)
doesn't forward the data or command yet — not a Nocturne (Android app) limitation, and not
speculative: every item below is confirmed against the actual fork source, with file:line
citations, not guessed. Each needs a real C++ change + rebuild + redeploy to the Pi, which is why
these are tracked separately from `docs/STATUS.md` (that doc is scoped to what's buildable from
this repo alone). User already maintains this fork and touches the connection layer in it (see the
reboot-daemon precedent, `pi-tools/reboot-daemon/`) — no reluctance to extend it further, this doc
exists so the *scope* of each change is clear before starting, not to talk anyone out of it.

Ordered by value, highest first (per the user's own priority: real-time guiding data is what
actually decides whether a session is worth continuing).

---

## 1. Guide RMS/drift/SNR — real live data, currently 3 local consumers, 0 remote

**Why it matters**: this is the number an imager actually watches to decide "keep going or pack
up" — confirmed the user's own priority for this list.

**Real data exists, confirmed in `guide.h`**:
```cpp
void newAxisDelta(double ra, double de);        // drift, per sample
void newAxisSigma(double ra, double de);        // RMS (arcsec), running
void guideStats(double raError, double decError, int raPulse, int decPulse,
                 double snr, double skyBg, int numStars);  // richest — SNR, sky bg, star count too
```

**Confirmed 0 of these reach EkosRemote** — every real connection in `manager.cpp` is local:
- `newAxisSigma` → `Manager::updateSigmas` (desktop UI label)
- `newAxisDelta` → a local lambda + `Capture::setGuideDeviation` (Capture's own guide-deviation guard)
- `guideStats` → `Analyze::guideStats` (the local `.analyze` session log)

### Wire contract

Reuses the existing `NEW_GUIDE_STATE` push type (`"new_guide_state"`) — no new command string —
adding 2 independent JSON shapes alongside the current `{"status": string}`, same precedent as
`new_align_state`'s status/solution split. Real per-sample scatter (`guideStats`) and the
accumulated RMS (`newAxisSigma`) are genuinely different Guide-internal concepts (confirmed:
`Guide::setAxisSigma`, `guide.cpp:2518-2525`, computes `total = std::hypot(ra, de)` — RSS, the
*standard* combined-RMS formula — purely for its own local UI label; the signal itself only ever
carries raw `ra`/`de`), so they stay 2 separate shapes rather than one merged one.

**Pi/fork side** (`message.h`/`message.cpp`, `manager.cpp`):
```cpp
// message.h
void sendGuideStats(double raError, double decError, int raPulse, int decPulse,
                     double snr, double skyBg, int numStars);
void sendGuideSigma(double rmsRA, double rmsDE);

// message.cpp
void Message::sendGuideStats(double raError, double decError, int raPulse, int decPulse,
                              double snr, double skyBg, int numStars)
{
    QJsonObject stats = {
        {"raError", raError}, {"decError", decError},
        {"raPulse", raPulse}, {"decPulse", decPulse},
        {"snr", snr}, {"skyBg", skyBg}, {"numStars", numStars},
    };
    sendResponse(commands[NEW_GUIDE_STATE], stats);
}
void Message::sendGuideSigma(double rmsRA, double rmsDE)
{
    sendResponse(commands[NEW_GUIDE_STATE], QJsonObject{{"rmsRA", rmsRA}, {"rmsDE", rmsDE}});
}

// manager.cpp, alongside the existing guideModule() connections
connect(guideModule(), &Ekos::Guide::guideStats, ekosRemote.get()->message(),
        &EkosRemote::Message::sendGuideStats, Qt::UniqueConnection);
connect(guideModule(), &Ekos::Guide::newAxisSigma, ekosRemote.get()->message(),
        &EkosRemote::Message::sendGuideSigma, Qt::UniqueConnection);
```

**Real JSON shapes** (3 independent, same push type):
```json
{"status": "Guiding"}
{"raError": 0.42, "decError": -0.18, "raPulse": 120, "decPulse": 0, "snr": 18.3, "skyBg": 245.0, "numStars": 14}
{"rmsRA": 0.61, "rmsDE": 0.55}
```

**App side** (`EkosEvent.kt`): current `NewGuideState(val status: String)` is required/non-nullable —
**fix this as part of the same change**, same standing bug class this repo has already hit 4 times
(default every field, merge non-null on arrival):
```kotlin
data class NewGuideState(
    val status: String? = null,
    val raError: Double? = null, val decError: Double? = null,
    val raPulse: Int? = null, val decPulse: Int? = null,
    val snr: Double? = null, val skyBg: Double? = null, val numStars: Int? = null,
    val rmsRA: Double? = null, val rmsDE: Double? = null,
) : EkosEvent
```
`rmsTotal` is a client-side derivation (`hypot(rmsRA, rmsDE)`, same real formula as `guide.cpp`'s own
`setAxisSigma`), not a wire field — no reason to send a value Nocturne can compute itself from 2
real numbers using the exact same formula Ekos does.

Nocturne-side app work once the wire exists (small, already has the pattern to follow): bounded
history in `AppState` (same shape as this session's `alignAccuracyHistory`), a real chart in
`GuideSheet` replacing the current honest "no RMS/drift data exists" disclosure.

---

## 2. Real Pause Scheduler — graceful, not the abrupt Stop/Start stand-in

**Why it matters**: current pause/resume (Stop/Start `SchedulerToggleButton`) is abrupt — marks the
active job `ABORTED` and interrupts the current exposure. Real Ekos has a genuinely graceful pause
that doesn't do either.

**Real data/methods confirmed**:
- `Scheduler::pause()` (`scheduler.cpp:2122`) — sets `SCHEDULER_PAUSED`, defers rather than aborting
  ("Scheduler pause planned...").
- `SchedulerProcess::execute()` (`schedulerprocess.cpp:124-144`) has a matching `SCHEDULER_PAUSED`
  case ("Scheduler resuming.") — the real graceful resume path.

**Confirmed not reachable today**: zero references to "pause" anywhere in `message.cpp`/`commands.h`.
Also confirmed NOT reachable via the existing generic `invoke_method` escape hatch either —
`Scheduler::pause()` is a plain `protected` method, not a slot or `Q_INVOKABLE`, so
`QMetaObject::invokeMethod` (what that generic command wraps) can't resolve it regardless of
whether `Scheduler` itself is `findObject`-reachable.

### Wire contract

New command pair, matching the empty-request/no-reply shape `scheduler_start_job` already uses
(`Commands.SCHEDULER_START_JOB` → `toggleScheduler()`):

```cpp
// commands.h
SCHEDULER_PAUSE,
SCHEDULER_RESUME,
// ...
{SCHEDULER_PAUSE, "scheduler_pause"},
{SCHEDULER_RESUME, "scheduler_resume"},
```

```cpp
// message.cpp, in processSchedulerCommands, alongside the existing SCHEDULER_START_JOB branch
else if (command == commands[SCHEDULER_PAUSE])
    scheduler->pause();
else if (command == commands[SCHEDULER_RESUME])
    scheduler->process()->execute();  // see open question below — confirm this is really the
                                       // graceful SCHEDULER_PAUSED→RUNNING path, not start()'s
                                       // job-state-resetting one
```

`Scheduler::pause()` is currently `protected` (`scheduler.h`) — needs making callable from
`EkosRemote::Message`, either by moving it `public` or adding a thin public wrapper method.

**Open question, resolve before implementing, not after**: does real Ekos's own `execute()`
(`schedulerprocess.cpp:124-144`) actually get called anywhere in the resume path the *real Resume
button* uses, or does `toggleScheduler()`'s `start()` branch (which unconditionally resets every
job to `IDLE`) run instead? Both exist in this fork; only one is the genuinely graceful path. Worth
a `qCDebug` trace or breakpoint on a real desktop KStars build (not the Pi) confirming which one the
real Resume button hits before writing the exact `SCHEDULER_RESUME` handler — get this wrong and the
"graceful" resume ends up no better than the existing Stop/Start stand-in.

**Real request/response**: both empty payload (`{}`), no reply — same as `scheduler_start_job`.
**App side**: no new `EkosEvent` needed — `NewSchedulerState`'s existing `status` int already
covers `SCHEDULER_PAUSED` (`3`) and would just need `AppState.schedulerRunning`'s own `it != 0 &&
it != 5` check reviewed (currently `PAUSED` already counts as "running" — probably still correct,
but worth confirming the UI reads "paused" distinctly somewhere once this exists, not just "running").

---

## 3. Meridian-flip countdown

**Correction — the previous version of this doc was wrong about `invoke_method`, caught while
writing the exact wire contract just now.** `Mount::hourAngle()`/`Mount::meridianFlipValue()`
(`mount.h:271,221`) are real `Q_SCRIPTABLE` getters — real current hour angle and the real
flip-trigger threshold, `hoursUntilFlip ≈ meridianFlipValue() - hourAngle()` — but re-reading
`Message::invokeMethod` (`message.cpp:3091-3145`) line by line: it calls
`QMetaObject::invokeMethod(context, name, argsList[0], ...)` with **no `Q_RETURN_ARG` anywhere in
the function, for any argument count, and never calls `sendResponse`**. A getter invoked this way
genuinely executes, but its return value is silently discarded — there is no path for it to reach
the client at all. This isn't a `findObject` resolution question (which was the earlier,
too-optimistic framing) — **`invoke_method` cannot return a value over this wire regardless of
whether the target object resolves.** No live test would have caught this differently; it's visible
from the source alone once actually traced through.

### Wire contract (2 real options)

**Option A — dedicated command (recommended, consistent with how every other feature in this fork
is built)**:
```cpp
// commands.h
MOUNT_GET_FLIP_STATUS,
// ...
{MOUNT_GET_FLIP_STATUS, "mount_get_flip_status"},
```
```cpp
// message.cpp, processMountCommands
else if (command == commands[MOUNT_GET_FLIP_STATUS])
{
    QJsonObject flip = {
        {"hourAngle", m_Manager->mountModule()->hourAngle()},
        {"meridianFlipValue", m_Manager->mountModule()->meridianFlipValue()},
        {"meridianFlipEnabled", m_Manager->mountModule()->meridianFlipEnabled()},
    };
    sendResponse(commands[MOUNT_GET_FLIP_STATUS], flip);
}
```
Real request: `{}` (or `{"jd": double}` if a non-"now" time is ever wanted, matching the existing
`astro_get_objects_riseset` convention). Real response: the object above. `hoursUntilFlip` is a
client-side derivation (`meridianFlipValue - hourAngle`, both real hours), not a wire field.

**Option B — generalize `invoke_method` to support return values** — reusable beyond just this one
case (any future `Q_INVOKABLE`/`Q_SCRIPTABLE` getter benefits), but real work: needs
`QMetaMethod::returnMetaType()` inspected at runtime and the matching `Q_RETURN_ARG` overload
selected dynamically, then a `sendResponse` added with the result. Bigger, more general, more
fragile than Option A for a single well-known real need — Option A is the recommended path unless
this generic capability is wanted for other reasons too.

Either way, still needs `findObject("Mount")` (or a direct `m_Manager->mountModule()` call, which
Option A uses instead — sidesteps the `findObject` question entirely, another reason to prefer A).

---

## 4. Network auto-discovery (mDNS) — server never announces itself, app has nothing to find

**Why it matters**: today the Pi's host/port has to be typed in by hand every time (`ConnectionSettings`,
manual entry) — real auto-discovery would let Nocturne find the rig on the LAN itself, no typing.

**Correction — also caught while writing the exact contract.** The earlier version of this doc said
"call `qMDNS`'s registration side" as if a service-advertisement API already exists there, just
unused. **Read `qMDNS.h` directly this time**: that class has no registration/advertisement
capability *at all* — it's a bare hostname **resolver** only (`lookup(name)` → an IP, a hand-rolled
mDNS A/AAAA query-response pair, `qMDNS.cpp`). No SRV/TXT/PTR records, no service-type concept, no
DNS-SD support of any kind. There is nothing in this class to "turn on" — real service
advertisement would be new code, not a flipped switch.

**Given that, the actually-simplest real fix probably isn't a KStars/fork change at all**: Raspberry
Pi OS ships `avahi-daemon` by default (verify with `systemctl status avahi-daemon` on the real Pi —
not yet confirmed live, but a very standard default). If it's running, a **static service file**
advertises `_ekosremote._tcp` with zero C++ code, zero KStars rebuild:
```xml
<!-- /etc/avahi/services/ekosremote.service -->
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">EkosRemote on %h</name>
  <service>
    <type>_ekosremote._tcp</type>
    <port>9000</port>
  </service>
</service-group>
```
Avahi picks this up automatically (or after `systemctl reload avahi-daemon`) — same class of change
as the reboot-daemon's own systemd unit (`pi-tools/reboot-daemon/`), a Pi-side config file, not a
KStars source change at all. **This should probably be reclassified out of this doc** once
confirmed live — it doesn't touch the fork.

**App side either way**: Android's own `NsdManager` (`android.net.nsd`, standard DNS-SD client) —
`NsdManager.discoverServices("_ekosremote._tcp", NsdManager.PROTOCOL_DNS_SD, listener)`, resolve the
matching service to get host/port, prefill `ConnectionSettings` instead of the current manual entry.
Self-contained app work, no dependency on which advertisement path (Avahi config vs. a real
extended `qMDNS`) ends up used server-side.

**Scope note**: orthogonal to auth (item 5) — discovery just answers "where is the Pi," doesn't
change who's allowed to connect once found.

---

## 5. Network hardening — auth, per-client tracking

Only needed if real security posture (auth, not just "trusted LAN") is ever actually required — see
`docs/STATUS.md`'s own network section for the full current-state writeup. Fork-required parts:

### Wire contract — token auth

Checked the exact real accept path (`NodeManager::onNewConnection`, `nodemanager.cpp:56-74`) before
designing this: it already inspects `socket->requestUrl().path()` to route Message vs. Media —
the same `requestUrl()` also exposes `.query()`, a real, already-available place to carry a token
with zero WS-handshake-header plumbing needed:

```cpp
// nodemanager.cpp, in onNewConnection, alongside the existing path check
const QString token = QUrlQuery(socket->requestUrl()).queryItemValue("token");
if (token != loadSharedToken())  // new helper — same shared-secret-file pattern as
                                  // pi-tools/reboot-daemon/reboot_daemon.py's own TOKEN_PATH
{
    qCWarning(KSTARS_EKOS) << "EkosRemote: rejecting connection, bad/missing token";
    socket->close();
    socket->deleteLater();
    continue;
}
```

Real connect URL becomes `ws://<pi>:9000/message/ekos?token=<shared-secret>` (and the same for
`/media/ekos`) — same shared-secret-file convention the reboot-daemon already established
(`/etc/nocturne-reboot/token`-style path, a matching `/etc/nocturne-ekosremote/token` or reuse the
same file), not a new auth scheme invented from scratch. **App side**: `EkosRemoteClient`/
`MediaChannel` append `?token=...` to the connect URL, `ConnectionSettings` gains a token field —
same shape as `RigRebootClient`'s own `rigRebootToken` already does for the reboot channel.

### Wire contract — per-client tracking

`NodeManager` holds one `Node` slot per channel today (`m_Nodes[Message]`/`m_Nodes[Media]`); a
second client silently displaces the first (`Node::adoptSocket` tears down the previous socket, no
notification either side). Real fix needs `NodeManager` to hold a list rather than one slot per
channel, and either (a) reject a second connection outright while one is active, or (b) allow it
but push a real notification to the *first* client that it was displaced (would need a new push
type, e.g. `client_displaced`, sent to the outgoing socket right before closing it) — (b) matches
this doc's own item 1 pattern (a small, well-scoped new push) better than (a)'s silent-reject,
which would surprise a second device trying to connect with no explanation either.

---

## Explicitly not on this list (deliberately out of scope, not forgotten)

- **Observatory/dome control** (`DOME_*` wire commands) — no real dome hardware to test against on
  this rig; user's own standing call to skip indefinitely, not a priority gap.
- **Rotator `Options` ground-truth read** — investigated this exact class of problem
  (`GET_PROPERTY`/`findObject("Options")`) earlier and initially thought it needed live-verification
  like item 3 above — turned out **moot**: `option_get`/`option_set` (already real, already wired in
  Nocturne, `message.cpp:1445-1471`) reaches `Options::self()`'s properties directly via
  `QObject::property()`, no `findObject` involved at all. Confirmed working pattern already in use
  for `astrometryUseRotator`/`ekosRemoteNotifications`/`ekosRemoteSound`. No fork change needed here
  — resolved, not backlogged.
