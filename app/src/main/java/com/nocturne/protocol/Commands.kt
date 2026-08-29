package com.nocturne.protocol

/**
 * Wire command strings M2 actually sends — the connection lifecycle +
 * bootstrap set. Flat namespace, not the full ~230-command set from
 * EkosRemote-Command-Reference.md; M3 appends more `const val`s here as it
 * wires up real capture/mount/focus/etc. commands, no restructuring needed.
 */
object Commands {
    const val SET_CLIENT_STATE = "set_client_state"
    const val GET_CONNECTION = "get_connection"
    const val GET_STATES = "get_states"
    const val GET_DEVICES = "get_devices"
    const val GET_PROFILES = "get_profiles"

    // M3 — profiles.
    const val PROFILE_START = "profile_start"
    const val PROFILE_STOP = "profile_stop"
    const val PROFILE_ADD = "profile_add"
    const val PROFILE_UPDATE = "profile_update"
    const val PROFILE_DELETE = "profile_delete"

    // M3 — devices/properties.
    const val DEVICE_GET = "device_get"
    const val DEVICE_PROPERTY_GET = "device_property_get"
    const val DEVICE_PROPERTY_SET = "device_property_set"
    const val DEVICE_PROPERTY_SUBSCRIBE = "device_property_subscribe"

    // M3 — astro lookups.
    const val ASTRO_SEARCH_OBJECTS = "astro_search_objects"
    const val ASTRO_GET_OBJECTS_INFO = "astro_get_objects_info"
    const val ASTRO_GET_OBJECTS_RISESET = "astro_get_objects_riseset"
    const val ASTRO_GET_ALMANAC = "astro_get_almanac"
    const val ASTRO_GET_LOCATION = "astro_get_location"

    // M3 — scheduler/capture sequences.
    const val SCHEDULER_GET_JOBS = "scheduler_get_jobs"
    const val SCHEDULER_ADD_JOBS = "scheduler_add_jobs"
    const val SCHEDULER_REMOVE_JOBS = "scheduler_remove_jobs"
    const val SCHEDULER_START_JOB = "scheduler_start_job"
    const val SCHEDULER_SAVE_SEQUENCE_FILE = "scheduler_save_sequence_file"
    const val SCHEDULER_GET_ALL_SETTINGS = "scheduler_get_all_settings"
    const val SCHEDULER_SET_ALL_SETTINGS = "scheduler_set_all_settings"

    // M3 — optical trains.
    const val TRAIN_GET_ALL = "train_get_all"
    const val TRAIN_GET_PROFILES = "train_get_profiles"
    const val TRAIN_SET = "train_set"
    const val TRAIN_ADD = "train_add"
    const val TRAIN_UPDATE = "train_update"

    // M3.1 — Scopes catalog (separate from Optical Trains — EkosRemote-Command-Reference.md §4).
    const val GET_SCOPES = "get_scopes"
    const val SCOPE_ADD = "scope_add"
    const val SCOPE_UPDATE = "scope_update"
    const val SCOPE_DELETE = "scope_delete"

    // M3.2 — Bench check (Capture preview/Focus jog/Mount motion — EkosRemote-Command-Reference.md §5/6/8/9).
    const val CAPTURE_PREVIEW = "capture_preview"
    const val GUIDE_CAPTURE = "guide_capture"
    const val FOCUS_IN = "focus_in"
    const val FOCUS_OUT = "focus_out"
    const val MOUNT_SET_MOTION = "mount_set_motion"
    const val MOUNT_SET_SLEW_RATE = "mount_set_slew_rate"
    const val MOUNT_UNPARK = "mount_unpark"
    const val MOUNT_PARK = "mount_park"
    const val MOUNT_SET_TRACKING = "mount_set_tracking"
    const val MOUNT_GOTO_TARGET = "mount_goto_target"
    const val ALIGN_SOLVE = "align_solve"
    // M5 — rotator angle-readback + auto-drive (docs/STATUS.md M5 steps 4/5).
    const val ALIGN_MANUAL_ROTATOR_TOGGLE = "align_manual_rotator_toggle"
    const val ALIGN_SET_ASTROMETRY_SETTINGS = "align_set_astrometry_settings"
    const val CAPTURE_SET_ALL_SETTINGS = "capture_set_all_settings"

    // M3.3 — per-module settings (curated subset, see docs/M3.3-plan.md).
    const val MOUNT_GET_ALL_SETTINGS = "mount_get_all_settings"
    const val MOUNT_SET_ALL_SETTINGS = "mount_set_all_settings"
    const val CAPTURE_GET_ALL_SETTINGS = "capture_get_all_settings"

    // M3.3 phase 3 — Align settings (curated subset, see docs/M3.3-plan.md).
    const val ALIGN_GET_ALL_SETTINGS = "align_get_all_settings"
    const val ALIGN_SET_ALL_SETTINGS = "align_set_all_settings"

    // M3.3 phase 4 (partial, brought forward for Bench "Snap guide" exposure/gain/bin control).
    const val GUIDE_GET_ALL_SETTINGS = "guide_get_all_settings"
    const val GUIDE_SET_ALL_SETTINGS = "guide_set_all_settings"

    // M3.3 phase 6 — Focus settings (curated subset, see docs/M3.3-plan.md). GET brought
    // forward earlier to fix Bench Focuser's stale initial position; SET added now.
    const val FOCUS_GET_ALL_SETTINGS = "focus_get_all_settings"
    const val FOCUS_SET_ALL_SETTINGS = "focus_set_all_settings"

    // Controls tab v2 — real Autofocus/Guide/Polar-Align start-stop control (empty
    // request/response, all three — see EkosRemote-Command-Reference.md §8/9/11; progress
    // arrives only via new_focus_state/new_guide_state/new_polar_state, already wired).
    const val FOCUS_START = "focus_start"
    const val FOCUS_STOP = "focus_stop"
    const val GUIDE_START = "guide_start"
    const val GUIDE_STOP = "guide_stop"
    const val POLAR_START = "polar_start"
    const val POLAR_STOP = "polar_stop"

    // M4.1 — Media channel. Payload is a bare boolean, not an object-wrapped one like every
    // other command here — see MediaChannel/EkosRemoteClient call sites.
    const val SET_BLOBS = "set_blobs"
}
