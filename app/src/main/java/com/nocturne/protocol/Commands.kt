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
}
