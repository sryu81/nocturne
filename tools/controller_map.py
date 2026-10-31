#!/usr/bin/env python3
"""Measures EkosRemoteController for the split described in docs/REFACTOR.md.

Prints, for the class as it is now: member counts and sizes, how many members call `super.*` (the
base class's optimistic local update), and for each proposed domain its lines, the private fields it
owns or shares, and the calls that leave it. Re-run after each step to see the shell shrink.

    python3 tools/controller_map.py [path/to/EkosRemoteController.kt]

Comments and string literals are stripped first, so a function named in a comment is not a call.
Domains are assigned by the rules in DOMAINS below; edit them when a function moves or a new one lands.
"""
import collections
import re
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java/com/nocturne/session/EkosRemoteController.kt"

# domain -> (name regex or exact names). First match wins.
DOMAINS = [
    ("rig storage (split out)", r"^(rigStorage\w*|requestRigStorageSummary|openRigStorage|closeRigStorage)$"),
    ("settings: scheduler", r"^(setScheduler\w*|sendSchedulerSetting)$"),
    ("settings: guide", r"^(setGuide\w*|sendGuideSetting)$"),
    ("settings: focus", r"^(setFocus\w*|sendFocusSetting)$"),
    ("settings: align", r"^(setAlign\w*|sendAlignSetting|setRotator\w*)$"),
    ("settings: mount", r"^(setMountMeridian\w*|setMountAlt\w*|setMountHa\w*|setMountPark\w*|setMountAutoPark\w*|sendMountSetting)$"),
    ("settings: capture", r"^(setCamera(Guide|Start|Dither|Refocus|MaxFocus|Filter)\w*|sendCaptureSetting|setCapturePreview\w*|setCaptureFullFrame)$"),
    ("events (reducer, follow-ups)", r"^(shouldThrottleNumberPush|applyEvent|sendFollowUpCommands|enforceCaptureLocation|defaultBinningIfMissing|refreshModuleSettingsForCamera|fetchFullDeviceIfNewlyConnected|subscribeDevice|enforcePreferredPierSide)$"),
    ("plan search", r"^(matchingSearchNames|resetSearchResults|buildSearchResults|setQuery|clearQuery|toggleChip|setSearchType|setSearchMaxMag|refilterSearch|requestNameLookup|sendNameLookup|sendSearchResolve|runSearch|ensureTargetRiseset|ensureReferenceImage|selectTarget)$"),
    ("scheduler / session", r"^(applySchedulerJobs|reconcileSyncedJobStatus|distributeCompleted|pushJob|toggleScheduler|ensureCoolingBeforeStart|pushRealJob|removeJob|removeUnmanagedJob|removeRealJobsByName|removeRealJobByName|endSession|finishNight|endingJobNames|stopSchedulerIfRunning|resumeSession|startNextJob|parseCoordsToRaDecBox)$"),
    ("profiles / trains / scopes", r"^(resolveModuleTrainAssignments|startProfile|disconnectDevices|stopProfile|deleteProfile|editProfile|finishSetup|setTrainRole|setTrainReducer|setModuleTrain|sendTrainUpdate|addScope|updateScope|removeScope)$"),
    ("rig maintenance / sky atlas", r"^(setRigRebootPort|rebootRig|shutdownRig|syncTimeToRig|syncRigOnConnect|syncLocationToRig|rescanSkyAtlas|refreshSkyAtlas|copySkyAtlasFromRig|cancelSkyAtlasCopy|clearSkyAtlas|setMountJ2000|setPushNotifications|setEkosRemoteNotifications|setEkosRemoteSound|sendOption)$"),
    ("devices / INDI", r"^(toggleDevice|setIndiSwitch|setIndiNumber|setIndiText|refreshDeviceSoon|sendSwitchElement|setCameraFan|setCoolerEnabled|setTemperatureEnforced|applyCoolTarget|cancelPendingCapture|pushLocalAlert)$"),
]
FALLBACK = "equipment actions (goto, park, guide, focus, align, polar)"
INFRA = {"client", "scope", "log", "ctx", "rigStorage", "connectionRepo", "sequenceRepo", "frameRepo",
         "targetRepo", "skyAtlas", "allowOptionalInternet"}


def strip(t):
    out, i, n = [], 0, len(t)
    while i < n:
        if t.startswith("//", i):
            j = t.find("\n", i); j = n if j < 0 else j; out.append(" " * (j - i)); i = j
        elif t.startswith("/*", i):
            j = t.find("*/", i); j = n if j < 0 else j + 2; out.append(re.sub(r"[^\n]", " ", t[i:j])); i = j
        elif t.startswith('"""', i):
            j = t.find('"""', i + 3); j = n if j < 0 else j + 3; out.append(re.sub(r"[^\n]", " ", t[i:j])); i = j
        elif t[i] == '"':
            j = i + 1
            while j < n and t[j] != '"':
                j += 2 if t[j] == "\\" else 1
            out.append(" " * (j + 1 - i)); i = j + 1
        else:
            out.append(t[i]); i += 1
    return "".join(out)


def domain(name):
    for label, rx in DOMAINS:
        if re.match(rx, name):
            return label
    return FALLBACK


raw = open(PATH).read().split("\n")
code = strip("\n".join(raw)).split("\n")
cls_start = next(i for i, l in enumerate(raw, 1) if l.startswith("class EkosRemoteController"))
cls_end = next(i for i, l in enumerate(raw, 1) if l == "}" and i > cls_start)

pat = r"^    (private |internal |protected )?(override )?(open )?(suspend )?fun (\w+)"
members = []
for i in range(cls_start, cls_end):
    m = re.match(pat, code[i - 1])
    if m:
        members.append([i, m.group(5)])
for k, m in enumerate(members):
    m.append((members[k + 1][0] - 1) if k + 1 < len(members) else cls_end - 1)
fields = [m.group(1) for i in range(cls_start, cls_end)
          if (m := re.match(r"^    private (?:va[lr]) (\w+)", code[i - 1]))]
body = {n: "\n".join(code[s - 1:e]) for s, n, e in members}
size = {n: e - s + 1 for s, n, e in members}
names = set(body)
calls = {n: {m for m in names if m != n and (re.search(r"(?<![\w.])" + m + r"\s*[({]", body[n]) or re.search(r"::" + m + r"\b", body[n]))} for n in names}
uses = {n: {f for f in fields if f not in INFRA and re.search(r"(?<![\w.])" + f + r"\b", body[n])} for n in names}
sup = {n for n in names if re.search(r"\bsuper\.\w+", body[n])}

total = len(raw)
print(f"{PATH}\n  file {total} lines, class {cls_end - cls_start + 1}, {len(members)} members, {len(fields)} private fields")
s_lines = sum(size[n] for n in sup)
print(f"  call super.*: {len(sup)} members / {s_lines} lines  |  no super: {len(names) - len(sup)} members / {sum(size.values()) - s_lines} lines")
tiny = sum(1 for n in sup if size[n] <= 6)
print(f"  of the super callers, {tiny} are 6 lines or fewer (send, then super: declarative)\n")

by = collections.defaultdict(list)
for n in names:
    by[domain(n)].append(n)
users = collections.defaultdict(set)
for n in names:
    for f in uses[n]:
        users[f].add(domain(n))
print(f"{'domain':58s} {'funs':>4s} {'lines':>5s} {'super':>5s}  owns / shares (fields)")
for d, ns in sorted(by.items(), key=lambda x: -sum(size[n] for n in x[1])):
    fs = set().union(*[uses[n] for n in ns])
    own = sorted(f for f in fs if users[f] == {d})
    shared = sorted(f for f in fs if users[f] != {d})
    out = collections.Counter(domain(c) for n in ns for c in calls[n] if domain(c) != d)
    print(f"{d:58s} {len(ns):4d} {sum(size[n] for n in ns):5d} {sum(1 for n in ns if n in sup):5d}  {len(own)} / {len(shared)}"
          + (f"   calls into {dict(out)}" if out else ""))
