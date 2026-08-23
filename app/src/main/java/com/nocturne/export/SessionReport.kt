package com.nocturne.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nocturne.session.ALERTS
import com.nocturne.session.SimState
import com.nocturne.session.displayName
import com.nocturne.session.endedJob
import com.nocturne.session.findTarget
import com.nocturne.session.formatSiteTime
import com.nocturne.session.frames
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
fun buildSessionReportHtml(state: SimState): String {
    val job = state.endedJob
    val target = job?.let { j -> state.findTarget(j.targetId) }

    val blockRows = job?.blocks?.joinToString("\n") { b ->
        "<tr><td>${b.filter}</td><td>${b.spec}</td><td>${b.meta}</td></tr>"
    } ?: "<tr><td colspan=\"3\">No job active at export time.</td></tr>"

    val frameRows = state.frames.joinToString("\n") { f ->
        val status = if (f.cut) "cut" else "kept"
        "<tr class=\"${if (f.cut) "cut" else ""}\"><td>sub_${f.id}.fits</td><td>${f.hfr}</td><td>$status</td></tr>"
    }

    val alertRows = ALERTS.joinToString("\n") { a ->
        "<tr><td>${a.time}</td><td>${a.text}</td></tr>"
    }

    // Real dusk/dawn (same `state.realNightWindow` source as `NightArcCard`'s Session-tab fix,
    // M2026-08) once it's arrived; falls back to the fixture literal under the simulator or
    // before the fetch lands. The rest of this report (alerts/frames) is still genuinely M1
    // fixture data regardless — that caveat stays as-is, only the window itself was fake here.
    val nightWindow = (if (state.isRealRig) state.realNightWindow else null)?.let {
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
            <div class="meta">$nightWindow · exported from Nocturne (M1 simulator data)</div>

            <h2>Sequence</h2>
            <table><tr><th>Filter</th><th>Exposure</th><th>Progress</th></tr>$blockRows</table>

            <h2>Frames — ${state.keepCount} kept, ${state.rejectCount} cut</h2>
            <table><tr><th>File</th><th>HFR</th><th>Status</th></tr>$frameRows</table>

            <h2>Session log</h2>
            <table><tr><th>Time</th><th>Event</th></tr>$alertRows</table>

            <h2>Teardown</h2>
            <div class="meta">Cooler ${"%.1f".format(state.coolNow)} °C · mount ${if (state.mountParked) "parked" else "not parked"}</div>
        </body></html>
    """.trimIndent()
}

/** Writes the report to cache and opens it in the user's browser via FileProvider. */
fun exportSessionReport(context: Context, state: SimState) {
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "session_report.html")
    file.writeText(buildSessionReportHtml(state))

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open session report"))
}
