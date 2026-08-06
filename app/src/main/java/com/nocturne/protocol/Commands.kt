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

    // M3 — scheduler/capture sequences.
    const val SCHEDULER_GET_JOBS = "scheduler_get_jobs"
    const val SCHEDULER_ADD_JOBS = "scheduler_add_jobs"
    const val SCHEDULER_REMOVE_JOBS = "scheduler_remove_jobs"
    const val SCHEDULER_START_JOB = "scheduler_start_job"
    const val SCHEDULER_SAVE_SEQUENCE_FILE = "scheduler_save_sequence_file"
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
}
