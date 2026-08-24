package com.nocturne.session

/**
 * Serializes one [SequenceJob]'s [Block] list to Ekos's real `.esq` sequence
 * queue XML (format version 2.6) — confirmed against the actual KStars
 * source (`kstars/ekos/capture/sequencequeue.cpp`/`sequencejob.cpp`) and the
 * real `Tests/ekos/scheduler/9filters.esq` fixture, not guessed.
 *
 * Deliberately partial: `sequencejob.cpp`'s load switch treats every per-Job
 * field as its own independent `else if` (`Frame`/`Temperature`/
 * `PlaceholderSuffix`/`RemoteDirectory`/`ISOIndex`/`Rotation`/pre/post
 * scripts/...) — an absent field just keeps the constructor default, it's
 * not an error. Only the fields Nocturne actually has data for are written:
 * - `FITSDirectory`/`PlaceholderFormat` **were previously omitted/hardcoded — real bug, found
 *   live 2026-08-23**: the doc here used to claim omitting `FITSDirectory` "avoids blanking out
 *   whatever the Pi's Capture module already has configured" — confirmed live that assumption was
 *   wrong: a real pushed job's own `.esq` had NO `FITSDirectory` element at all, and real Ekos
 *   showed the job's save directory as genuinely empty, not falling back to any other config —
 *   the Camera-settings "Save path" field (`fileDirectoryT`) is a *different*, Capture-module-wide
 *   setting from a job's own per-job `FITSDirectory`; setting one never populated the other. Now
 *   sourced from [AppState.wireCaptureSettings]'s own `fileDirectoryT`/`placeholderFormatT`
 *   (both real, both already wired to the Camera settings sheet) instead of a fixed literal.
 * - `HFRCheck` is omitted — Nocturne has no "HFR deviation %" setting to
 *   source it from (`enforceAutofocusOnTemperature`/`enforceRefocusEveryN` map to the two
 *   fields below, not this one).
 * - `afOnFilterChange` had no `.esq` field at all (README §8), and turned out to have no real
 *   Ekos equivalent anywhere else either — removed entirely 2026-08-23, same call as the
 *   per-block `forceAfOnStart` toggle that shared this exact gap (real per-block autofocus
 *   stays deferred past M4, see docs/app-side-feature-backlog.md).
 *
 * **`enabled='true'`/`'false'` fixed 2026-08-23** — previously hardcoded `enabled='true'` on
 * both refocus elements unconditionally, so there was no way to actually turn either off. Real
 * Ekos writes/reads this as a literal string attribute, not a magic value (confirmed
 * `sequencequeue.cpp:283-287` write / `:146` read: `!strcmp(findXMLAttValu(ep, "enabled"),
 * "true")`) — and, confirmed the same source, loading an `.esq` with these set actually writes
 * straight through to the same global `Options::enforceRefocusEveryN`/`refocusEveryN` etc. the
 * Camera-settings sheet's own `capture_set_all_settings` call uses (`sequencequeue.cpp:213-214`)
 * — genuinely the same underlying setting via two different real paths, not two independent
 * ones, so feeding this from [AppState.wireCaptureSettings] can't drift out of sync with itself.
 */
object EsqWriter {

    fun write(
        job: SequenceJob, targetName: String,
        enforceRefocusEveryN: Boolean, refocusEveryN: Int,
        enforceAutofocusOnTemperature: Boolean, maxFocusTemperatureDelta: Double,
        /** Real `fileDirectoryT` (Camera settings' "Save path") — null/blank omits the element entirely. */
        fitsDirectory: String? = null,
        /** Real `placeholderFormatT` — falls back to Ekos's own real default format when blank. */
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
        // Real "-1 means off" sentinel (confirmed against sequencejob.cpp:1069-1070) — kept
        // confined to this one write site; the app's own Block.ditherEvery uses a real `null`.
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

    /** Decimals are always `.`-locale on the wire (plan §"Protocol facts") — drop a bare `.0` for whole numbers, matching the fixture's own `<HFRDeviation>1.12</HFRDeviation>` / integer style. */
    private fun formatDecimal(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
