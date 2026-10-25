package com.nocturne.transport

/**
 * Cheap link-quality facts [EkosRemoteClient] keeps for the Home health card. Plain fields, not
 * flows: they change at message rate, and only the card that reads them (on a timer) should pay
 * for that.
 */
class LinkStats {
    /** Wall-clock ms of the last message from Ekos; 0 until one arrives. */
    @Volatile var lastMessageAtMs: Long = 0
    /** Wall-clock ms of the last image frame on the media socket; 0 until one arrives. */
    @Volatile var lastMediaAtMs: Long = 0
    @Volatile var mediaOpen: Boolean = false
    /** Round trip of the last `get_connection` probe, ms; null before the first answer. */
    @Volatile var rttMs: Long? = null
    @Volatile var rttAtMs: Long = 0
}
