package com.nocturne.protocol

/** Wire command strings the app sends. A subset of the EkosRemote command set; see docs/PROTOCOL.md. */
object Commands {
    const val SET_CLIENT_STATE = "set_client_state"
    const val GET_CONNECTION = "get_connection"
    const val GET_STATES = "get_states"
    const val GET_DEVICES = "get_devices"
    const val GET_PROFILES = "get_profiles"
    /** Every INDI driver KStars knows (its drivers.xml catalog), installed or not running. */
    const val GET_DRIVERS = "get_drivers"

    // Profiles
    const val PROFILE_START = "profile_start"
    const val PROFILE_STOP = "profile_stop"
    const val PROFILE_ADD = "profile_add"
    const val PROFILE_UPDATE = "profile_update"
    const val PROFILE_DELETE = "profile_delete"

    // Devices / INDI properties
    const val DEVICE_GET = "device_get"
    const val DEVICE_PROPERTY_GET = "device_property_get"
    const val DEVICE_PROPERTY_SET = "device_property_set"
    const val DEVICE_PROPERTY_SUBSCRIBE = "device_property_subscribe"

    // Astro lookups
    const val ASTRO_SEARCH_OBJECTS = "astro_search_objects"
    /** Every object name KStars knows (stars, planets, comets, DSOs…) — ~44k names / ~0.5 MB live. */
    const val ASTRO_GET_NAMES = "astro_get_names"
    const val ASTRO_GET_OBJECTS_INFO = "astro_get_objects_info"
    const val ASTRO_GET_OBJECT_INFO = "astro_get_object_info"
    const val ASTRO_GET_OBJECTS_RISESET = "astro_get_objects_riseset"
    const val ASTRO_GET_ALMANAC = "astro_get_almanac"
    const val ASTRO_GET_LOCATION = "astro_get_location"

    // Scheduler
    const val SCHEDULER_GET_JOBS = "scheduler_get_jobs"
    const val SCHEDULER_ADD_JOBS = "scheduler_add_jobs"
    const val SCHEDULER_REMOVE_JOBS = "scheduler_remove_jobs"
    const val SCHEDULER_START_JOB = "scheduler_start_job"
    const val SCHEDULER_SAVE_SEQUENCE_FILE = "scheduler_save_sequence_file"
    const val SCHEDULER_GET_ALL_SETTINGS = "scheduler_get_all_settings"
    const val SCHEDULER_SET_ALL_SETTINGS = "scheduler_set_all_settings"

    // Optical trains
    const val TRAIN_GET_ALL = "train_get_all"
    const val TRAIN_GET_PROFILES = "train_get_profiles"
    const val TRAIN_SET = "train_set"
    const val TRAIN_ADD = "train_add"
    const val TRAIN_UPDATE = "train_update"

    // Scopes catalog (separate from optical trains)
    const val GET_SCOPES = "get_scopes"
    const val SCOPE_ADD = "scope_add"
    const val SCOPE_UPDATE = "scope_update"
    const val SCOPE_DELETE = "scope_delete"

    // Capture preview, focus jog, mount motion, solve
    const val CAPTURE_PREVIEW = "capture_preview"
    /** Cancels a preview still waiting in preparation (e.g. "Setting Temperature"). */
    const val CAPTURE_STOP = "capture_stop"
    const val GUIDE_CAPTURE = "guide_capture"
    const val FOCUS_IN = "focus_in"
    const val FOCUS_OUT = "focus_out"
    const val MOUNT_SET_MOTION = "mount_set_motion"
    const val MOUNT_SET_SLEW_RATE = "mount_set_slew_rate"
    const val MOUNT_UNPARK = "mount_unpark"
    const val MOUNT_PARK = "mount_park"
    const val MOUNT_SET_TRACKING = "mount_set_tracking"
    const val MOUNT_GOTO_TARGET = "mount_goto_target"
    const val MOUNT_ABORT = "mount_abort"
    const val ALIGN_SOLVE = "align_solve"
    // Rotator. align_manual_rotator_toggle is unused: it only shows/hides Ekos's dialog on the
    // Pi's own screen.
    const val ALIGN_SET_ASTROMETRY_SETTINGS = "align_set_astrometry_settings"
    const val ALIGN_SET_TARGET_PA = "align_set_target_pa"
    const val CAPTURE_SET_ALL_SETTINGS = "capture_set_all_settings"

    // Per-module settings
    const val MOUNT_GET_ALL_SETTINGS = "mount_get_all_settings"
    const val MOUNT_SET_ALL_SETTINGS = "mount_set_all_settings"
    const val CAPTURE_GET_ALL_SETTINGS = "capture_get_all_settings"

    const val ALIGN_GET_ALL_SETTINGS = "align_get_all_settings"
    const val ALIGN_SET_ALL_SETTINGS = "align_set_all_settings"

    const val GUIDE_GET_ALL_SETTINGS = "guide_get_all_settings"
    const val GUIDE_SET_ALL_SETTINGS = "guide_set_all_settings"

    const val FOCUS_GET_ALL_SETTINGS = "focus_get_all_settings"
    const val FOCUS_SET_ALL_SETTINGS = "focus_set_all_settings"

    // Autofocus/guide/polar-align start-stop. No payload and no reply; progress arrives only
    // via new_focus_state/new_guide_state/new_polar_state.
    const val FOCUS_START = "focus_start"
    const val FOCUS_STOP = "focus_stop"
    const val GUIDE_START = "guide_start"
    const val GUIDE_STOP = "guide_stop"
    // `guide_loop` = Ekos's Loop button (continuous guide-cam frames, stopped by `guide_stop`);
    // `guide_clear` = Clear calibration. Both take no payload (`message.cpp` processGuideCommands).
    const val GUIDE_LOOP = "guide_loop"
    const val GUIDE_CLEAR = "guide_clear"
    const val POLAR_START = "polar_start"
    const val POLAR_STOP = "polar_stop"
    /** `{"value": refreshExposureSec}` — starts the adjust (refresh) phase. */
    const val POLAR_REFRESH = "polar_refresh"
    /** Ends the refresh phase — server-side this is the same stopPAHProcess() as [POLAR_STOP]. */
    const val POLAR_REFRESHING_DONE = "polar_refreshing_done"
    /** Manual-slew mode: user finished rotating the RA axis by hand. */
    const val POLAR_SLEW_DONE = "polar_slew_done"
    /** `{"x","y"}` as 0..1 fractions of the sent align image — picks the correction star. */
    const val POLAR_SET_CROSSHAIR = "polar_set_crosshair"

    // Media channel. The payload is a bare boolean, not an object.
    const val SET_BLOBS = "set_blobs"

    // Generic Options::self() property get/set (message.cpp:1445-1471): any kcfg-backed setting
    // by its Qt property name (e.g. "ekosRemoteNotifications", "astrometryUseRotator"), without
    // the ambiguous findObject() lookup that invoke_method relies on.
    const val OPTION_GET = "option_get"
    const val OPTION_SET = "option_set"

    // Phone GPS/time sync. Fork-only commands calling KStars::setGPSLocation/setLocalTime
    // directly: option_set only changes the persisted default, and invoke_method's findObject()
    // can't resolve the KStars singleton. Fire-and-forget, no reply.
    const val KSTARS_SET_LOCATION = "kstars_set_location"
    const val KSTARS_SET_TIME = "kstars_set_time"
}
