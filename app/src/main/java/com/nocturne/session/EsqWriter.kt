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
 * - `FITSDirectory` is omitted entirely (not written as `""`) — Nocturne has
 *   no directory setting of its own, and an empty element would explicitly
 *   blank out whatever the Pi's Capture module already has configured,
 *   which omitting the element avoids.
 * - `HFRCheck` is omitted — Nocturne has no "HFR deviation %" setting to
 *   source it from (`afTempDeltaC`/`afRefocusMin` map to the two fields
 *   below, not this one).
 * - `afOnFilterChange` has no `.esq` field at all — same documented gap as
 *   `Block.forceAfOnStart` (README §8) — neither is written here.
 */
object EsqWriter {

    fun write(job: SequenceJob, targetName: String, afRefocusMin: Int, afTempDeltaC: Double): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<SequenceQueue version='2.6'>\n")
        append("<RefocusOnTemperatureDelta enabled='true'>${formatDecimal(afTempDeltaC)}</RefocusOnTemperatureDelta>\n")
        append("<RefocusEveryN enabled='true'>$afRefocusMin</RefocusEveryN>\n")
        job.blocks.forEach { append(writeJob(it, targetName)) }
        append("</SequenceQueue>\n")
    }

    private fun writeJob(b: Block, targetName: String): String = buildString {
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
        append("<GuideDitherPerJob>${b.ditherEvery}</GuideDitherPerJob>\n")
        append("<PlaceholderFormat>/%t/%T/%F/%t_%T_%F_%e_%D</PlaceholderFormat>\n")
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
