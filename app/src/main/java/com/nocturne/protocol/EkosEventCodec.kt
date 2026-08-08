package com.nocturne.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** Decodes/encodes the wire [Envelope] format, dispatching to typed [EkosEvent]s. */
object EkosEventCodec {

    /**
     * Parses one inbound text frame into a typed [EkosEvent]. Never throws —
     * an unrecognized `type`, or a recognized `type` whose payload doesn't
     * match the expected shape, both fall back to [EkosEvent.Raw] rather than
     * crashing the socket's read loop. The wire has no formal error envelope
     * to lean on (EkosRemote-Command-Reference.md §5), so decode failures
     * must degrade gracefully, not propagate.
     */
    fun decode(text: String): EkosEvent {
        val envelope = try {
            protocolJson.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            return EkosEvent.Raw(type = "<unparsable>", payload = JsonObject(emptyMap()))
        }
        return try {
            when (envelope.type) {
                "new_connection_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewConnectionState>(envelope.payload)
                "new_capture_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewCaptureState>(envelope.payload)
                "new_mount_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewMountState>(envelope.payload)
                "new_focus_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewFocusState>(envelope.payload)
                "new_guide_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewGuideState>(envelope.payload)
                "new_align_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewAlignState>(envelope.payload)
                "new_polar_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewPolarState>(envelope.payload)
                "get_profiles" -> protocolJson.decodeFromJsonElement<EkosEvent.Profiles>(envelope.payload)
                "get_devices" -> EkosEvent.Devices(protocolJson.decodeFromJsonElement(envelope.payload))
                "device_get" -> {
                    val payload = protocolJson.decodeFromJsonElement<DeviceGetPayload>(envelope.payload)
                    EkosEvent.DeviceProperties(
                        device = payload.device,
                        properties = payload.properties.mapNotNull { decodeWireProperty(it.jsonObject) },
                    )
                }
                "device_property_get" -> decodeWireProperty(envelope.payload.jsonObject)
                    ?.let { EkosEvent.DeviceProperty(it) }
                    ?: EkosEvent.Raw(envelope.type, envelope.payload)
                "astro_search_objects" -> EkosEvent.AstroSearchResult(protocolJson.decodeFromJsonElement(envelope.payload))
                "astro_get_objects_info" -> EkosEvent.AstroObjectsInfo(protocolJson.decodeFromJsonElement(envelope.payload))
                "astro_get_objects_riseset" -> EkosEvent.AstroObjectsRiseset(protocolJson.decodeFromJsonElement(envelope.payload))
                "scheduler_get_jobs" -> protocolJson.decodeFromJsonElement<EkosEvent.SchedulerJobs>(envelope.payload)
                "train_get_all" -> EkosEvent.Trains(protocolJson.decodeFromJsonElement(envelope.payload))
                "get_scopes" -> EkosEvent.Scopes(protocolJson.decodeFromJsonElement(envelope.payload))
                "train_get_profiles" -> EkosEvent.TrainProfiles(protocolJson.decodeFromJsonElement(envelope.payload))
                "mount_get_all_settings" -> EkosEvent.MountSettings(protocolJson.decodeFromJsonElement(envelope.payload))
                "capture_get_all_settings" -> EkosEvent.CaptureSettings(protocolJson.decodeFromJsonElement(envelope.payload))
                "align_get_all_settings" -> EkosEvent.AlignSettings(protocolJson.decodeFromJsonElement(envelope.payload))
                "guide_get_all_settings" -> EkosEvent.GuideSettings(protocolJson.decodeFromJsonElement(envelope.payload))
                else -> EkosEvent.Raw(envelope.type, envelope.payload)
            }
        } catch (e: SerializationException) {
            EkosEvent.Raw(envelope.type, envelope.payload)
        }
    }

    /**
     * Sniffs which INDI vector shape a property JSON object is — Switch/
     * Number/Text/Light are distinguished by which key is present, not a
     * discriminator field (`EkosRemote-Command-Reference.md` §14). Returns
     * `null` (falls back to [EkosEvent.Raw] at the call site) if none match.
     */
    private fun decodeWireProperty(obj: JsonObject): WireProperty? = when {
        "switches" in obj -> protocolJson.decodeFromJsonElement<WireProperty.Switch>(obj)
        "numbers" in obj -> protocolJson.decodeFromJsonElement<WireProperty.Number>(obj)
        "texts" in obj -> protocolJson.decodeFromJsonElement<WireProperty.Text>(obj)
        "lights" in obj -> protocolJson.decodeFromJsonElement<WireProperty.Light>(obj)
        else -> null
    }

    /** Builds one outbound text frame for [type]/[payload] (defaults to an empty object). */
    fun encode(type: String, payload: JsonElement = JsonObject(emptyMap())): String =
        protocolJson.encodeToString(Envelope(type, payload))
}

/** `device_get`'s reply shape — `properties[]` stays raw pending [decodeWireProperty] dispatch. */
@Serializable
private data class DeviceGetPayload(val device: String, val properties: List<JsonElement> = emptyList())
