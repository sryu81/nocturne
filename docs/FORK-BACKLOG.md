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

**The fix** (same shape as `Message::setAlignSolution`, `message.cpp:958-969` — already a working
precedent in this exact fork for "export a module's rich real-time data as a JSON push"):
1. `commands.h`: add `NEW_GUIDE_STATS` (or fold into existing `NEW_GUIDE_STATE` as a second,
   independent shape — matches the `new_align_state` status/solution split already in this fork).
2. `message.h`/`message.cpp`: new `Message::sendGuideStats(...)` building a `QJsonObject` from the
   `guideStats`/`newAxisSigma` params, `sendResponse(commands[NEW_GUIDE_STATS], ...)`.
3. `manager.cpp`: `connect(guideModule(), &Ekos::Guide::guideStats, ekosRemote.get()->message(),
   &EkosRemote::Message::sendGuideStats, Qt::UniqueConnection);` (and the same for `newAxisSigma`
   if RMS is wanted as its own field rather than derived client-side from the per-sample stream).

**Nocturne-side work once the wire exists** (small, already has the pattern to follow):
new `EkosEvent`, decode + bounded history in `AppState` (same shape as this session's
`alignAccuracyHistory`), a real chart in `GuideSheet` replacing the current honest "no RMS/drift
data exists" disclosure. Straightforward once the push is real.

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

**The fix**: add `SCHEDULER_PAUSE` (and confirm a resume path — either reuse the existing
`SCHEDULER_START_JOB`/`toggleScheduler()` if it's confirmed to route through `execute()`'s
`SCHEDULER_PAUSED` case correctly, or add a distinct `SCHEDULER_RESUME`; **not fully traced** which
of these `toggleScheduler()`'s own `start()` branch actually does — it unconditionally resets all
job states to `IDLE`, which doesn't obviously match the graceful resume semantics, needs checking
directly in source or via a live test before assuming either way). Likely needs making
`Scheduler::pause()` callable from `EkosRemote::Message` (public, or a `Q_INVOKABLE` wrapper).

---

## 3. Meridian-flip countdown — *may not need a fork change at all*

Different from the other items: the real ingredients might already be reachable via the **existing**
generic `invoke_method` command, no new fork command needed — just unverified.

**Real data confirmed**: `Mount::hourAngle()` and `Mount::meridianFlipValue()` (`mount.h:271,221`)
are both `Q_SCRIPTABLE` — real current hour angle and the real flip-trigger threshold. Together:
`hoursUntilFlip ≈ meridianFlipValue() - hourAngle()`.

**What's actually unverified**: whether `findObject("Mount")` (the generic wire's own object-lookup,
`message.cpp`'s `Message::findObject`) resolves the real `Mount` module instance at all — it walks
`QObject::findChild` by `objectName()`, and nothing in this repo confirms `Mount`'s instance has one
set. If it resolves, this needs **zero fork changes** — call `invoke_method` twice
(`{"object":"Mount","name":"hourAngle"}`, `{"object":"Mount","name":"meridianFlipValue"}`) from
Nocturne directly. If it doesn't resolve, `findObject` would need extending to recognize the module
by a fixed name.

**Next step**: a 30-second live test next time there's a real Ekos connection — send both
`invoke_method` calls, see if a real value comes back or the object lookup fails. Cheap to check
before assuming a fork change is even needed here.

---

## 4. Network auto-discovery (mDNS) — server never announces itself, app has nothing to find

**Why it matters**: today the Pi's host/port has to be typed in by hand every time (`ConnectionSettings`,
manual entry) — real auto-discovery would let Nocturne find the rig on the LAN itself, no typing.

**Confirmed real mDNS already exists in this codebase, just unused for this**: KStars has a real
mDNS responder (`qMDNS.cpp`, UDP 5353) — but its only real call site anywhere is client-side lookup
during profile setup (finding a StellarMate box). `NodeManager`/`ekosremoteserver.cpp` never touch
it at all — the EkosRemote server **never announces itself on the LAN in any way** (no mDNS, no
Bonjour, no UPnP, no broadcast). This is why "app-side discovery" alone is not feasible today —
there is nothing on the network to discover, regardless of how well a client-side scanner is built.

**The fix**: the fork needs to call `qMDNS`'s registration side (not its lookup side, which already
exists and is unrelated) from `NodeManager`'s own startup — advertise a service (e.g.
`_ekosremote._tcp`) carrying the host/port already fixed at `0.0.0.0:9000`. Once that's real,
Nocturne-side discovery (Android's own `NsdManager`, the standard mDNS/DNS-SD API) is a normal,
self-contained app feature — no further fork work needed past the initial advertisement.

**Scope note**: this is orthogonal to auth (item 5 below) — discovery just answers "where is the
Pi," it doesn't change who's allowed to connect once found. Worth doing independently of whether
auth ever gets built.

---

## 5. Network hardening — auth, per-client tracking

Only needed if real security posture (auth, not just "trusted LAN") is ever actually required — see
`docs/STATUS.md`'s own network section for the full current-state writeup. Fork-required parts:

- **Real handshake/token auth on the EkosRemote channel** — same shape as the reboot-daemon's
  already-working token scheme (`pi-tools/reboot-daemon/`, a real precedent that this pattern works
  *on a channel Nocturne controls*), or restoring a stripped-down version of stock EkosLive's
  HTTP-auth-before-socket pattern (removed in this fork's own migration commit `9664c0142`). The
  only way to close "no credential check at all" — the server today accepts any correctly-pathed
  socket, no app-side work reaches that.
- **Per-client session tracking / connection allowlist** — `NodeManager` holds one `Node` slot per
  channel today; a second client silently displaces the first (`Node::adoptSocket`). Would need
  holding more than one `Node` and an explicit accept/reject decision instead of unconditional
  displacement.

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
