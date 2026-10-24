package com.nocturne.session

/**
 * Serializes one [SequenceJob]'s [Block] list to Ekos's `.esq` sequence queue XML
 * (version 2.6), per KStars' `sequencequeue.cpp`/`sequencejob.cpp` and the
 * `Tests/ekos/scheduler/9filters.esq` fixture.
 *
 * Deliberately partial: Ekos loads each per-Job field independently and an absent one keeps
 * its default, so only fields Nocturne has data for are written.
 * - `FITSDirectory` must be written per job: an absent element leaves the job's save directory
 *   empty, it does not fall back to Capture's "Save path" (`fileDirectoryT`), which is a
 *   separate module-wide setting. Both it and `PlaceholderFormat` come from
 *   [AppState.wireCaptureSettings].
 * - `HFRCheck` is omitted; Nocturne has no "HFR deviation %" setting.
 *
 * The refocus elements' `enabled` attribute is compared as the literal string "true"
 * (`sequencequeue.cpp`), and loading them writes the same global `Options` that
 * `capture_set_all_settings` does, so both are fed from [AppState.wireCaptureSettings].
 */
object EsqWriter {

    fun write(
        job: SequenceJob, targetName: String,
        enforceRefocusEveryN: Boolean, refocusEveryN: Int,
        enforceAutofocusOnTemperature: Boolean, maxFocusTemperatureDelta: Double,
        /** `fileDirectoryT` (Camera settings' "Save path"); null/blank omits the element. */
        fitsDirectory: String? = null,
        /** `placeholderFormatT`; Ekos's default format when blank. */
        placeholderFormat: String = DEFAULT_PLACEHOLDER_FORMAT,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<SequenceQueue version='2.6'>\n")
        append("<RefocusOnTemperatureDelta enabled='$enforceAutofocusOnTemperature'>${formatDecimal(maxFocusTemperatureDelta)}</RefocusOnTemperatureDelta>\n")
        append("<RefocusEveryN enabled='$enforceRefocusEveryN'>$refocusEveryN</RefocusEveryN>\n")
        val format = placeholderFormat.ifBlank { DEFAULT_PLACEHOLDER_FORMAT }
        job.blocks.forEach { append(writeJob(it, targetName, fitsDirectory, format)) }
        append("</SequenceQueue>\n")
    }

    private const val DEFAULT_PLACEHOLDER_FORMAT = "/%t/%T/%F/%t_%T_%F_%e_%D"

    private fun writeJob(b: Block, targetName: String, fitsDirectory: String?, placeholderFormat: String): String = buildString {
        append("<Job>\n")
        append("<Exposure>${b.exposureSec}</Exposure>\n")
        append("<Format>Mono</Format>\n")
        append("<Encoding>FITS</Encoding>\n")
        append("<Binning><X>${b.binning}</X><Y>${b.binning}</Y></Binning>\n")
        append("<Filter>${xmlEscape(b.filter)}</Filter>\n")
        append("<Type>Light</Type>\n")
        append("<Count>${b.subCount}</Count>\n")
        append("<Delay>0</Delay>\n")
        append("<TargetName>${xmlEscape(targetName)}</TargetName>\n")
        // Ekos uses -1 for "off" (sequencejob.cpp); Block.ditherEvery uses null.
        append("<GuideDitherPerJob>${b.ditherEvery ?: -1}</GuideDitherPerJob>\n")
        if (!fitsDirectory.isNullOrBlank()) {
            append("<FITSDirectory>${xmlEscape(fitsDirectory)}</FITSDirectory>\n")
        }
        append("<PlaceholderFormat>${xmlEscape(placeholderFormat)}</PlaceholderFormat>\n")
        append("<UploadMode>0</UploadMode>\n")
        append("<Properties>\n")
        append("<PropertyVector name='CCD_GAIN'><OneElement name='GAIN'>${b.gain}</OneElement></PropertyVector>\n")
        append("<PropertyVector name='CCD_OFFSET'><OneElement name='OFFSET'>${b.offset}</OneElement></PropertyVector>\n")
        append("</Properties>\n")
        append("<Calibration><PreAction><Type>1</Type></PreAction>\n")
        append("<FlatDuration dark='false'><Type>Manual</Type></FlatDuration></Calibration>\n")
        append("</Job>\n")
    }

    /** `.`-decimal regardless of locale; whole numbers drop the `.0`, as Ekos writes them. */
    private fun formatDecimal(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
