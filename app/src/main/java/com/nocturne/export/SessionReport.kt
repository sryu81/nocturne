package com.nocturne.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nocturne.session.AppState
import com.nocturne.session.displayName
import com.nocturne.session.endedJob
import com.nocturne.session.findTarget
import com.nocturne.session.formatSiteTime
import com.nocturne.session.keepCount
import com.nocturne.session.meta
import com.nocturne.session.realNightWindow
import com.nocturne.session.rejectCount
import com.nocturne.session.spec
import java.io.File

/**
 * Builds the "Export log + FITS list" report as one self-contained HTML file —
 * session log (alerts timeline) and frame list side by side, viewable in any
 * browser instead of two separate plain-text exports.
 */
fun buildSessionReportHtml(state: AppState): String {
    val job = state.endedJob
    val target = job?.let { j -> state.findTarget(j.targetId) }

    val blockRows = job?.blocks?.joinToString("\n") { b ->
        "<tr><td>${b.filter}</td><td>${b.spec}</td><td>${b.meta}</td></tr>"
    } ?: "<tr><td colspan=\"3\">No job active at export time.</td></tr>"

    // Real once a frame has actually landed (M4.3, Room-backed) — HFR can be null if the real
    // header didn't carry one for that frame. Filename is the real on-disk name (M4.5 Part A);
    // target/filter are real too, null for a Preview/test capture (M4.5 Part C).
    val frameRows = state.frameRows.sortedBy { it.timestampMs }.joinToString("\n") { f ->
        val status = if (f.keep) "kept" else "cut"
        val filename = File(f.filePath).name
        "<tr class=\"${if (!f.keep) "cut" else ""}\"><td>$filename</td><td>${f.target ?: "—"}</td>" +
            "<td>${f.filter ?: "—"}</td><td>${f.hfr?.let { "%.2f".format(it) } ?: "—"}</td><td>$status</td></tr>"
    }

    // Real new_notification stream (M4.5 half A, docs/STATUS.md) — was the static ALERTS fixture.
    val alertRows = if (state.wireNotifications.isEmpty()) {
        "<tr><td colspan=\"2\">No alerts this session.</td></tr>"
    } else {
        state.wireNotifications.reversed().joinToString("\n") { a ->
            "<tr><td>${a.time}</td><td>${a.message}</td></tr>"
        }
    }

    // Real dusk/dawn (same `state.realNightWindow` source as `NightArcCard`'s Session-tab fix,
    // M2026-08) once it's arrived. The rest of this report (alerts/frames) is still genuinely
    // fixture data regardless — that caveat stays as-is, only the window itself was fake here.
    val nightWindow = state.realNightWindow?.let {
        "${state.formatSiteTime(it.first)} → ${state.formatSiteTime(it.second)}"
    } ?: "21:48 → 04:12"

    return """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>Nocturne session report</title>
        <style>
            body { background:#161826; color:#e9e9ed; font:14px/1.5 -apple-system,Roboto,sans-serif; padding:24px; }
            h1 { font-size:20px; margin-bottom:4px; }
            h2 { font-size:13px; text-transform:uppercase; letter-spacing:.08em; color:#9397ab; margin:28px 0 8px; }
            table { width:100%; border-collapse:collapse; font-size:13px; }
            th, td { text-align:left; padding:6px 10px; border-bottom:1px solid rgba(233,233,237,.12); }
            th { color:#75798c; font-weight:500; }
            tr.cut td { color:#d98484; }
            .meta { color:#75798c; font-size:12.5px; }
        </style></head>
        <body>
            <h1>${target?.displayName ?: "Nocturne session"}</h1>
            <div class="meta">$nightWindow · exported from Nocturne (session log below is still fixture placeholder data, pending M4.5)</div>

            <h2>Sequence</h2>
            <table><tr><th>Filter</th><th>Exposure</th><th>Progress</th></tr>$blockRows</table>

            <h2>Frames — ${state.keepCount} kept, ${state.rejectCount} cut</h2>
            <table><tr><th>File</th><th>Target</th><th>Filter</th><th>HFR</th><th>Status</th></tr>$frameRows</table>

            <h2>Session log</h2>
            <table><tr><th>Time</th><th>Event</th></tr>$alertRows</table>

            <h2>Teardown</h2>
            <div class="meta">Cooler ${"%.1f".format(state.coolNow)} °C · mount ${if (state.mountParked) "parked" else "not parked"}</div>
        </body></html>
    """.trimIndent()
}

/**
 * The milestone's own exit criteria names "export produces log + FITS list" — this app never
 * receives raw FITS bytes over the wire (only JPEG previews via the Media channel), so this is a
 * plain CSV manifest of the real per-frame metadata already sitting in Room (M4.3/M4.5), not
 * actual FITS files. One row per real captured frame; empty (header-only) if none exist yet.
 */
fun buildFrameListCsv(state: AppState): String {
    fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
    val header = "filename,target,filter,exposure,gain,bin,resolution,hfr,mean,median,stddev,status"
    val rows = state.frameRows.sortedBy { it.timestampMs }.map { f ->
        listOf(
            File(f.filePath).name,
            f.target ?: "",
            f.filter ?: "",
            f.exposure ?: "",
            f.gain ?: "",
            f.bin ?: "",
            f.resolution ?: "",
            f.hfr?.let { "%.2f".format(it) } ?: "",
            f.mean?.let { "%.1f".format(it) } ?: "",
            f.median?.let { "%.1f".format(it) } ?: "",
            f.stddev?.let { "%.2f".format(it) } ?: "",
            if (f.keep) "kept" else "cut",
        ).joinToString(",") { esc(it) }
    }
    return (listOf(header) + rows).joinToString("\n") + "\n"
}

/** Writes both the HTML report and the real FITS-list CSV to cache, offers them together via FileProvider. */
fun exportSessionReport(context: Context, state: AppState) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val htmlFile = File(dir, "session_report.html").apply { writeText(buildSessionReportHtml(state)) }
    val csvFile = File(dir, "frame_list.csv").apply { writeText(buildFrameListCsv(state)) }

    val authority = "${context.packageName}.fileprovider"
    val htmlUri = FileProvider.getUriForFile(context, authority, htmlFile)
    val csvUri = FileProvider.getUriForFile(context, authority, csvFile)
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(htmlUri, csvUri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export session report"))
}
