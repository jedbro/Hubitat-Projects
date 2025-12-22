/**
 *  Vacation Lighting Analyzer (Child)
 *   - This child app analyzes the history of selected switches and dimmers over a user-defined date range, generating
 *     a timeline chart and usage statistics to help users understand their vacation lighting patterns. The goal of this tool 
 *     is to help you refine your simulation settings for more realistic vacation lighting behavior.
 *  
 *  V0.3.0.1 - December 2025
 *    - 0.3.01. Small performance improvement
 *    - Child analyzer for the Vacation Lighting Suite parent
 *    - "Analyze History" timelines with a 24h lookback
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "v0.3.0.1 • Dec 2025"
@Field static final Long HISTORY_LOOKBACK_MS = 24L * 60L * 60L * 1000L

definition(
    name: "Vacation Lighting Analyzer",
    namespace: "Logicalnonsense",
    author: "Jed Brown",
    description: "History analyzer child for the Vacation Lighting Suite.",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "",
    parent: "Logicalnonsense:Vacation Lighting Simulator Suite"
)

preferences {
    page(name: "mainPage")
    page(name: "historyPage")
}

// ========================================
// Lifecycle
// ========================================

def installed() {
    log.info "Vacation Lighting Analyzer installed"
}

def updated() {
    log.info "Vacation Lighting Analyzer updated"
}

// ========================================
// Pages
// ========================================

private String appVersion() { APP_VERSION }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Vacation Lighting Analyzer", install: true, uninstall: true) {
        section("") {
            paragraph "<div style='text-align:right;font-size:11px;color:#888;'>${appVersion()}</div>"
            paragraph "Select the devices you want to include in history analysis, then open the Analyze History tool."
        }

        section("Devices to analyze") {
            input name: "trackedSwitches", type: "capability.switch", title: "Switches", multiple: true, required: true
            input name: "trackedDimmers", type: "capability.switchLevel", title: "Dimmers (optional)", multiple: true, required: false
        }

        section("Tools") {
            href "historyPage", title: "Analyze History (Date Range)", description: "Build a timeline using device event history"
        }

        section("Options") {
            label title: "Analyzer label (optional)", required: false
        }
    }
}

def historyPage() {
    dynamicPage(name: "historyPage", title: "Analyze History (Date Range)") {
        section("Select date range") {
            paragraph "Pick both a calendar date and a clock time for the analysis window."
            input name: "historyStartDate", type: "date", title: "Start date", required: false
            input name: "historyStartTime", type: "time", title: "Start time", required: false
            input name: "historyEndDate", type: "date", title: "End date", required: false
            input name: "historyEndTime", type: "time", title: "End time", required: false
            input name: "historyAnalyzeNow", type: "bool", title: "Run analysis now", defaultValue: false,
                description: "Toggle on to render the chart and stats. It will auto-reset after running.",
                submitOnChange: true
        }

        boolean haveStart = settings.historyStartDate && settings.historyStartTime
        boolean haveEnd   = settings.historyEndDate && settings.historyEndTime
        boolean runAnalysis = haveStart && haveEnd && (settings.historyAnalyzeNow == true)

        if (runAnalysis) {
            Date start = combineDateAndTime(settings.historyStartDate, settings.historyStartTime)
            Date end   = combineDateAndTime(settings.historyEndDate, settings.historyEndTime)

            if (!start || !end) {
                section {
                    paragraph "<span style='color:red'>Unable to parse the selected dates/times. Please adjust and try again.</span>"
                }
                return
            }

            if (end.before(start)) {
                section {
                    paragraph "<span style='color:red'>End time must be after start time.</span>"
                }
                return
            }

            section("Details") {
                paragraph """
<b>Start:</b> ${formatDateTime(start)}<br/>
<b>End:</b> ${formatDateTime(end)}<br/>
<b>Duration:</b> ${formatDuration(end.time - start.time)}<br/>
"""
            }

            def devicesById = [:]

            (settings.trackedSwitches ?: []).each { dev ->
                def segs = buildSegmentsFromHistory(dev, start, end)
                devicesById[dev.id.toString()] = [
                    name: dev.displayName,
                    segments: segs
                ]
            }
            (settings.trackedDimmers ?: []).each { dev ->
                def segs = buildSegmentsFromHistory(dev, start, end)
                if (devicesById[dev.id.toString()]) {
                    devicesById[dev.id.toString()].segments += segs
                    devicesById[dev.id.toString()].segments = mergeSegments(devicesById[dev.id.toString()].segments)
                } else {
                    devicesById[dev.id.toString()] = [
                        name: dev.displayName,
                        segments: segs
                    ]
                }
            }

            section("Per-device stats") {
                def table = buildDeviceStatsTable(devicesById as Map, start.time as Long, end.time as Long)
                paragraph table
            }

            section("Timeline (from device history)") {
                def html = renderTimeline(start.time as Long, end.time as Long, devicesById as Map)
                paragraph html
            }

            app.updateSetting("historyAnalyzeNow", [value: "false", type: "bool"])
        } else {
            section {
                if (!haveStart || !haveEnd) {
                    paragraph "Select both a start and end date plus their times, then toggle 'Run analysis now' to render the chart."
                } else {
                    paragraph "Toggle 'Run analysis now' to refresh the analysis with the selected date range."
                }
            }
        }
    }
}

// ========================================
// History helpers
// ========================================

private List buildSegmentsFromHistory(dev, Date start, Date end) {
    if (!dev || !start || !end) return []

    Long startMs = start.time
    Long endMs = end.time
    if (endMs <= startMs) return []

    Long lookbackStart = Math.max(startMs - HISTORY_LOOKBACK_MS, 0L)
    Date queryStart = new Date(lookbackStart)

    def events = dev.eventsBetween(queryStart, end, [max: 1000]) ?: []
    events = events.sort { it?.date?.time ?: 0L }

    String currentState = null
    Long lastChange = startMs
    boolean sawInRangeStateEvent = false

    List segments = []

    for (def evt : events) {
        Long ts = evt?.date?.time
        if (ts == null) { continue }
        if (ts > endMs) { break }

        String st = eventToState(evt)
        if (!st) { continue }

        if (ts < startMs) {
            currentState = st
            continue
        }

        sawInRangeStateEvent = true

        if (!currentState) {
            currentState = deviceStateSnapshot(dev) ?: "off"
        }

        Long segStart = Math.max(lastChange, startMs)
        Long segEnd = Math.min(ts, endMs)
        if (currentState && segEnd > segStart) {
            segments << [start: segStart, end: segEnd, state: currentState]
        }

        currentState = st
        lastChange = ts
    }

    if (!sawInRangeStateEvent) {
        if (!currentState) {
            currentState = deviceStateSnapshot(dev) ?: "off"
        }
        if (currentState == "on") {
            segments << [start: startMs, end: endMs, state: "on"]
        }
        return segments
    }

    if (currentState && lastChange < endMs) {
        segments << [start: Math.max(lastChange, startMs), end: endMs, state: currentState]
    }

    return mergeSegments(segments)
}

private List mergeSegments(List segments) {
    if (!segments || segments.size() < 2) return segments ?: []

    List merged = []
    def current = segments[0].clone()

    for (int i = 1; i < segments.size(); i++) {
        def seg = segments[i]
        if (seg.state == current.state && seg.start == current.end) {
            current.end = seg.end
        } else if (seg.state == current.state && seg.start <= current.end) {
            current.end = Math.max(current.end as Long, seg.end as Long)
        } else {
            merged << current
            current = seg.clone()
        }
    }
    merged << current
    return merged
}

private String eventToState(evt) {
    if (!evt) return null

    if (evt.name == "switch") {
        return (evt.value == "on") ? "on" : "off"
    }

    if (evt.name == "level") {
        try {
            Integer lvl = evt.value?.toInteger()
            return (lvl > 0) ? "on" : "off"
        } catch (ignored) {
            return null
        }
    }

    return null
}

private String deviceStateSnapshot(dev) {
    if (!dev) return null

    def switchVal = dev.currentValue("switch")
    if (switchVal == "on" || switchVal == "off") {
        return switchVal
    }

    def levelVal = dev.currentValue("level")
    if (levelVal != null) {
        try {
            return (levelVal.toInteger() > 0) ? "on" : "off"
        } catch (ignored) {
            return null
        }
    }

    return null
}

// ========================================
// Timeline rendering + stats
// ========================================

private String renderTimeline(Long startTs, Long endTs, Map devicesById) {
    Long total = endTs - startTs
    if (!devicesById || total <= 0) return "No data for this period."

    StringBuilder sb = new StringBuilder()

    sb << """
<style>
.timeline-container { font-size:11px; line-height:1.2; }
.timeline-row { display:flex; align-items:center; margin:4px 0; }
.timeline-label { width:150px; padding-right:8px; text-align:right; white-space:nowrap; }
.timeline-bar { position:relative; flex:1; height:16px; background:#222; border-radius:8px; overflow:hidden; }
.timeline-seg-on { position:absolute; top:0; bottom:0; background:#4caf50; }
.timeline-axis { margin-left:150px; font-size:10px; display:flex; justify-content:space-between; margin-bottom:4px; }
</style>
<div class="timeline-container">
"""

    Date start = new Date(startTs)
    Date end   = new Date(endTs)
    Long midpointTs = (((startTs + endTs) / 2L) as Long)
    sb << "<div class=\"timeline-axis\">"
    sb << "<span>${formatTimeShort(start)}</span>"
    sb << "<span>${formatTimeShort(new Date(midpointTs))}</span>"
    sb << "<span>${formatTimeShort(end)}</span>"
    sb << "</div>"

    devicesById.values().each { devEntry ->
        def segments = devEntry.segments ?: []
        sb << "<div class=\"timeline-row\">"
        sb << "<div class=\"timeline-label\">${devEntry.name}</div>"
        sb << "<div class=\"timeline-bar\">"

        segments.each { seg ->
            Long segStart = Math.max(seg.start as Long, startTs)
            Long segEnd   = Math.min(seg.end   as Long, endTs)
            if (segEnd <= segStart) return

            BigDecimal leftPct  = ((segStart - startTs) * 100.0) / total
            BigDecimal widthPct = ((segEnd - segStart) * 100.0) / total

            if (seg.state == "on") {
                sb << "<div class=\"timeline-seg-on\" style=\"left:${leftPct}%;width:${widthPct}%;\"></div>"
            }
        }

        sb << "</div></div>"
    }

    sb << "</div>"
    return sb.toString()
}

private String buildDeviceStatsTable(Map devicesById, Long startTs, Long endTs) {
    Long totalMs = endTs - startTs
    StringBuilder sb = new StringBuilder()
    sb << "<table style='width:100%;border-collapse:collapse;font-size:11px;'>"
    sb << "<tr style='font-weight:bold;border-bottom:1px solid #555;'>"
    sb << "<td>Device</td><td>On segments</td><td>Total on time</td><td>% of period on</td>"
    sb << "</tr>"

    devicesById.values().each { devEntry ->
        def segments = devEntry.segments ?: []
        long onMs = 0
        int onCount = 0
        segments.each { seg ->
            if (seg.state == "on") {
                long s = Math.max(seg.start as Long, startTs)
                long e = Math.min(seg.end as Long, endTs)
                if (e > s) {
                    onMs += (e - s)
                    onCount++
                }
            }
        }
        int pct = totalMs > 0 ? Math.round((onMs * 100.0) / totalMs) as int : 0

        sb << "<tr style='border-bottom:1px solid #333;'>"
        sb << "<td>${devEntry.name}</td>"
        sb << "<td>${onCount}</td>"
        sb << "<td>${formatDuration(onMs)}</td>"
        sb << "<td>${pct}%</td>"
        sb << "</tr>"
    }

    sb << "</table>"
    return sb.toString()
}

// ========================================
// Helpers
// ========================================

private String formatDateTime(Date d) {
    return d?.format("yyyy-MM-dd HH:mm", location.timeZone)
}

private Date combineDateAndTime(String dateStr, String timeStr) {
    if (!dateStr || !timeStr) {
        return null
    }

    def tz = location?.timeZone ?: TimeZone.getDefault()

    def dateFmt = new java.text.SimpleDateFormat("yyyy-MM-dd")
    dateFmt.setTimeZone(tz)

    Date dateOnly
    try {
        dateOnly = dateFmt.parse(dateStr)
    } catch (Exception ex) {
        log.warn "combineDateAndTime(): unable to parse date '${dateStr}' - ${ex.message}"
        return null
    }

    Date timeOnly = null

    if (timeStr.contains("T")) {
        def isoFmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        isoFmt.setTimeZone(tz)
        try {
            timeOnly = isoFmt.parse(timeStr)
        } catch (Exception ignored) {
        }
    }

    if (!timeOnly) {
        def timeFmt = new java.text.SimpleDateFormat("HH:mm")
        timeFmt.setTimeZone(tz)
        try {
            timeOnly = timeFmt.parse(timeStr)
        } catch (Exception ex) {
            log.warn "combineDateAndTime(): unable to parse time '${timeStr}' - ${ex.message}"
            return null
        }
    }

    def cal = java.util.Calendar.getInstance(tz)
    cal.setTime(dateOnly)

    def timeCal = java.util.Calendar.getInstance(tz)
    timeCal.setTime(timeOnly)

    cal.set(java.util.Calendar.HOUR_OF_DAY, timeCal.get(java.util.Calendar.HOUR_OF_DAY))
    cal.set(java.util.Calendar.MINUTE, timeCal.get(java.util.Calendar.MINUTE))
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)

    return cal.getTime()
}

private String formatTimeShort(Date d) {
    return d?.format("HH:mm", location.timeZone)
}

private String formatDuration(Long ms) {
    if (ms == null) return "-"
    long totalSec = (long)Math.floor(ms / 1000.0)
    long h = (long)Math.floor(totalSec / 3600.0)
    long m = (long)Math.floor((totalSec % 3600) / 60.0)
    long s = totalSec % 60

    if (h > 0)      return "${h}h ${m}m"
    else if (m > 0) return "${m}m ${s}s"
    else            return "${s}s"
}
