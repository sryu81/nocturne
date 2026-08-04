package com.nocturne.transport

import okhttp3.OkHttpClient

/**
 * Binary frame channel (`/media/ekos` — 512-byte JSON metadata header +
 * JPEG bytes, EkosRemote-Client-Guide.md §"Media channel binary frame
 * format"). Not built in M2 — Session tab's sub preview and the Frames tab
 * stay on simulator/placeholder imagery until M4 ("Media channel → preview
 * + Frames grid" per README's milestone table). This stub exists only so
 * [EkosRemoteClient]'s shape doesn't need to change again when M4 lands;
 * it is not instantiated or opened by [EkosRemoteClient] in M2.
 */
class MediaChannel(private val okHttpClient: OkHttpClient, private val url: String) {
    fun open(): Nothing = TODO("M4 — 512-byte metadata header + JPEG frame parsing, see README §2/§8")

    fun close(): Nothing = TODO("M4")
}
