/**
 *  Vacation Lighting Simulator (Child)

 *  V0.4.0 - March 2026
 *    - Configuration checklist on main page (shows ✅/❌/⚠️ for each setting)
 *    - Dynamic arming summary in Setup page (explains effective arming condition)
 *    - Enhanced status dashboard: arming context, time since last cycle, frequency info
 *    - Actionable "Why not running" diagnostics with specific hints
 *    - Test cycle section collapsed by default; add clear button + last test result
 *    - Time window page shows resolved today's window when sunrise/sunset used
 *    - Fix: anchor lights no longer turned off during mode changes when app was not running
 *
 *  V0.3.3 - February 2026
 *    - Fix to ensure schedule maintains over multiple days
 *    - Option for notifications to be immediate after a session

 *  V0.3.2.2 - January 2026
 *    - Small bug fix for daily summary (only sends if cycles > 0, only schedules when armed)
 *    - Warning displayed if Hubitat timezone is not configured
 *    - Small if check fix that prevents lights from staying on

 *  V0.3.2 - January 2026
 *    - Converted to parent/child architecture (managed by Vacation Lighting Suite)
 *    - Child app retains randomized scheduling, summaries, and test cycle tools
 *    - Vacation switch ON bypasses both the configured time window and mode restriction (manual override)
 *    - New: Added a Analysis tool to aggregate lighting stats and a timeline chart for how the lights behaved over a period of time.
 *
 *  V0.2.5 - December 2025 Updated to:
 *    - Turn on a set of lights during active time, and turn them off at end of vacation time
 *    - Instantly shut off when leaving configured modes / vacation switch
 *    - More pseudo random with per-light on duration = frequency_minutes ± ~20% (style B)
 *    - Optionally send a daily summary notification with cycle and light-change counts
 *    - Hubitat Performance Improvements:
 *      - Use a single queue-based scheduler to turn lights off after per-light durations
 *      - Use Hubitat `state` instead of `atomicState`
 *    - Optional Vacation switch in addition to modes
 *    - Only schedules when "armed" (by mode and/or vacation switch)
 *    - Shows status + Last Cycle + "Next cycle" + debug reason on main page
 *    - Test Functionality to run a one-off cycle (on main page under Options)
 *    - Improved Debug and Trace logging
 *
 *      Status icons:
 *       🔴 Not configured
 *       🟡 Idle (waiting for trigger: mode / vacation switch / etc.)
 *       🟢 Armed (ready; waiting for next cycle within allowed time window)
 *       ✅ Active (currently simulating / queued)
 *
 *  Based on original by tslagle and Eric Schott
 *  Optimized for Hubitat with additional features December 2025 by Jed Brown
 *
 *  Original source:
 *  https://github.com/imnotbob/vacation-lighting-director/blob/master/smartapps/imnotbob/vacation-lighting-director.src/vacation-lighting-director.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final String APP_VERSION = "v0.4.0 • Mar 2026"

definition(
    name: "Vacation Lighting Simulator",
    namespace: "Logicalnonsense",
    author: "Jed Brown",
    category: "Safety & Security",
    description: "Simulate light and switch behaviors of an occupied home while you are away or on Vacation.",
    iconUrl: "",
    iconX2Url: "",
    parent: "Logicalnonsense:Vacation Lighting Simulator Suite"
)

preferences {
    page(name:"pageSetup")
    page(name:"Setup")
    page(name:"Settings")
    page(name:"timeIntervalPage")
}

// --------- Logging helpers ---------

private Boolean isDescriptiveLoggingEnabled() {
    return (descriptiveLogging != null ? descriptiveLogging : true)
}

private Boolean isDebugLoggingEnabled() {
    return (debugLogging != null ? debugLogging : false)
}

private logInfo(msg) {
    if (isDescriptiveLoggingEnabled()) {
        log.info msg
    }
}

private logDebug(msg) {
    if (isDebugLoggingEnabled()) {
        log.debug msg
    }
}

private logTrace(msg) {
    if (isDebugLoggingEnabled()) {
        log.trace msg
    }
}

// --------- Version helper ---------

private String appVersion() { APP_VERSION }


// --------- Arming helper (mode + optional switch) ---------

private getModeOk() {
    def result = !newMode || newMode.contains(location.mode)
    result
}

private boolean hasModeRestriction() {
    // newMode is typically a List when multiple:true; treat empty as "no restriction configured"
    if (newMode == null) return false
    if (newMode instanceof List) return !newMode.isEmpty()
    // Defensive: if Hubitat provides a scalar value
    return true
}

/**
 * armOk:
 *  - If no vacationSwitch configured: rely on modes only
 *  - If vacationSwitch configured: armed when (switch ON) OR (mode allowed)
 */
private getArmOk() {
    boolean modesConfigured = hasModeRestriction()
    boolean modeAllowed = modesConfigured ? (modeOk as boolean) : false

    // No vacation switch: rely on modes only (if modes aren't configured, we are not armed)
    if (!vacationSwitch) {
        return modeAllowed
    }

    boolean switchIsOn = vacationSwitchOn()

    // Vacation switch configured:
    // - If modes configured: armed when (switch ON) OR (mode allowed)
    // - If modes NOT configured: armed only when switch is ON
    return modesConfigured ? (switchIsOn || modeAllowed) : switchIsOn
}

private boolean vacationSwitchOn() {
    return vacationSwitch && (vacationSwitch.currentSwitch == "on")
}

// --------- Status helpers ---------

private String nextCycleStatus() {
    def ts = state?.nextCycleAtMs
    if (!ts) return "not scheduled."

    long diff = (ts as Long) - now()
    if (diff <= 0) return "any moment."

    int mins = Math.round(diff / 60000.0)
    if (mins <= 1) return "~1 minute."
    return "~${mins} minutes."
}

private String timeSinceLastCycle() {
    def ts = state?.lastCycleAt
    if (!ts) return null
    long diff = now() - (ts as Long)
    int mins = Math.round(diff / 60000.0)
    if (mins <= 1) return "~1 minute ago"
    return "~${mins} minutes ago"
}

private String armingContext() {
    boolean modesConfigured = hasModeRestriction()
    boolean switchIsOn = vacationSwitchOn()
    boolean modeAllowed = modesConfigured && (modeOk as boolean)

    if (switchIsOn && modeAllowed) {
        return "Armed by: ${location.mode} mode + vacation switch ON"
    } else if (switchIsOn) {
        return "Armed by: vacation switch override"
    } else if (modeAllowed) {
        return "Armed by: ${location.mode} mode"
    }
    return ""
}

private String activeLightsWarning() {
    if (!switches || !number_of_active_lights) return ""
    int n = (number_of_active_lights as Integer)
    int s = switches.size()
    if (n > s) {
        return "Note: Active lights per cycle (${n}) exceeds configured lights (${s}); app will clamp to ${s}."
    }
    if (n < 1) {
        return "Warning: Active lights per cycle must be at least 1."
    }
    return ""
}

/**
 * Explain why the app is not running / not armed based on current conditions.
 * Returns an HTML string with actionable hints after each reason.
 */
private String whyNotRunning() {
    def reasons = []

    if (newMode && !modeOk && !vacationSwitchOn()) {
        def modeList = (newMode instanceof List) ? newMode.join(", ") : "${newMode}"
        reasons << "Current mode '<b>${location.mode}</b>' is not in allowed modes [<b>${modeList}</b>]. " +
                   "Go to Setup to change allowed modes, or turn on the vacation switch to override."
    }

    // Vacation switch reasons depend on whether modes are configured.
    if (vacationSwitch && !vacationSwitchOn()) {
        def sw = vacationSwitch.currentSwitch ?: 'unknown'
        if (!hasModeRestriction()) {
            reasons << "Vacation switch '<b>${vacationSwitch.displayName}</b>' is <b>${sw}</b> — turn it ON to arm the app."
        } else if (!modeOk) {
            reasons << "Mode '<b>${location.mode}</b>' is not allowed and vacation switch '<b>${vacationSwitch.displayName}</b>' is <b>${sw}</b>. " +
                       "Turning the switch ON would bypass the mode restriction."
        }
    }

    if (!daysOk && days) {
        def df = new java.text.SimpleDateFormat("EEEE")
        if (getTimeZone()) df.setTimeZone(getTimeZone())
        def today = df.format(new Date())
        def dayList = (days instanceof List) ? days.join(", ") : "${days}"
        reasons << "Today is <b>${today}</b>, which is not in the allowed days [<b>${dayList}</b>]. " +
                   "Check Settings → Advanced to adjust day restrictions."
    }

    if (!timeOk && !vacationSwitchOn() && (startTimeType || endTimeType || starting || ending)) {
        def win = timeIntervalLabel()
        def tz = getTimeZone()
        def fmt = new SimpleDateFormat("h:mm a")
        if (tz) fmt.setTimeZone(tz)
        def nowStr = fmt.format(new Date())
        if (win) {
            reasons << "Current time (<b>${nowStr}</b>) is outside the configured window (<b>${win}</b>). " +
                       "Go to Setup → Time window to adjust, or turn on the vacation switch to bypass."
        } else {
            reasons << "Current time (<b>${nowStr}</b>) is outside the configured time window. " +
                       "Go to Setup → Time window to review."
        }
    }

    if (!reasons) {
        return "No specific blockers detected — check that lights are configured in Setup."
    }

    return reasons.join("<br>")
}

private String appStatus() {
    def modeName = location?.mode ?: "Unknown"
    def configured = (switches && (newMode || vacationSwitch))

    // Check for TimeZone issue
    if (!location.timeZone) {
        return "<b>⚠️ Warning:</b> Hub TimeZone is not set. Time windows cannot be calculated, so the app will run 24/7 when armed.<br>Please set your TimeZone in Hubitat Settings."
    }

    // Counters
    def cycles   = state?.cycles   ?: 0
    def lightsOn = state?.lightsOn ?: 0
    def lightsOff= state?.lightsOff?: 0

    List lastRand = (state.lastCycleRandomized ?: []) as List
    List lastAnch = (state.lastCycleAnchors    ?: []) as List

    StringBuilder sb = new StringBuilder()

    if (!configured) {
        sb << "<b>Status:</b> <span style='color:#cc0000;'>🔴 Not fully configured.</span><br>"
        sb << "<b>Current mode:</b> ${modeName}<br>"
        sb << "Configure modes and/or a vacation switch, plus your lights to enable vacation lighting."
        return sb.toString()
    }

    boolean armed   = armOk
    boolean running = state?.Running ?: false
    boolean sched   = state?.schedRunning ?: false
    boolean queued  = (state?.lightSchedule instanceof Map) && !state.lightSchedule.isEmpty()
    boolean switchOverride = vacationSwitchOn()
    boolean inWindow = (timeOk || switchOverride)

    String icon
    String color
    String label

    if (running || sched || queued) {
        icon  = "✅"
        color = "#008800"
        label = "Active (simulating occupancy)"
    } else if (armed && inWindow) {
        icon  = "🟢"
        color = "#008800"
        label = "Armed (ready)"
    } else if (armed) {
        icon  = "🟡"
        color = "#cc9900"
        label = "Armed (outside time window)"
    } else {
        icon  = "🟡"
        color = "#cc9900"
        label = "Idle (waiting for trigger)"
    }

    sb << "<b>Status:</b> <span style='color:${color};'>${icon} ${label}</span><br>"
    sb << "<b>Current mode:</b> ${modeName}"
    if (vacationSwitch) {
        sb << " &nbsp; <b>Vacation switch:</b> ${vacationSwitch.currentSwitch ?: 'unknown'}"
    }
    sb << "<br>"

    // Arming context — show what is keeping the app armed
    if (armed || running || sched || queued) {
        String ctx = armingContext()
        if (ctx) {
            sb << "<b>${ctx}</b><br>"
        }
    }

    // Frequency/pool summary
    if (switches) {
        Integer freq = (frequency_minutes ?: 15) as Integer
        Integer numActive = (number_of_active_lights ?: 1) as Integer
        sb << "<small style='color:#666;'>Cycling every ~${freq} min with up to ${numActive} light(s) from a pool of ${switches.size()}</small><br>"
    }

    // Last cycle info
    if (!lastRand.isEmpty()) {
        sb << "<b>Last cycle randomized:</b> ${lastRand.join(', ')}<br>"
    }
    if (!lastAnch.isEmpty()) {
        sb << "<b>Anchor lights in last cycle:</b> ${lastAnch.join(', ')}<br>"
    }

    // Time since last cycle + next cycle countdown
    String sinceStr = timeSinceLastCycle()
    String nextStr  = nextCycleStatus()
    if (sinceStr) {
        sb << "<b>Last cycle:</b> ${sinceStr}. <b>Next cycle:</b> ${nextStr}<br>"
    } else {
        sb << "<b>Next cycle:</b> ${nextStr}<br>"
    }

    sb << "<b>Since last summary:</b> ${cycles} cycles, ${lightsOn} light-ons, ${lightsOff} light-offs.<br>"

    // What would trigger it when armed but outside window
    if (armed && !inWindow) {
        def winLabel = timeIntervalLabel()
        if (winLabel) {
            sb << "<b>Will run:</b> ${winLabel}<br>"
        }
    }

    def warn = activeLightsWarning()
    if (warn) {
        sb << "<b>Notes:</b> ${warn}<br>"
    }

    // Diagnostics when not actively cycling
    if (!running && !sched && !queued) {
        String diagLabel = armed ? "Why not cycling" : "Why not running"
        def debugLine = whyNotRunning()
        if (debugLine) {
            sb << "<b>${diagLabel}:</b> ${debugLine}"
        }
    }

    return sb.toString()
}

// --------- UI formatting helpers ---------

private String getFormat(String type, String myText = "") {
    switch(type) {
        case "header-blue":
            // Blue bar with white bold text
            return "<div style='color:#ffffff;background-color:#1A77C9;border:1px solid #1A77C9;padding:4px 8px;margin:4px 0;'><b>${myText}</b></div>"
        case "line-blue":
            // Thin blue separator line
            return "<hr style='background-color:#1A77C9;height:1px;border:0;margin-top:8px;margin-bottom:8px;'>"
        default:
            return myText
    }
}

/**
 * Build a quick-glance configuration checklist for the main page.
 * Shows ✅/❌/⚠️ for each key setting so new users can see what's missing at a glance.
 */
private String configChecklist() {
    StringBuilder sb = new StringBuilder()
    sb << "<div style='margin:4px 0;line-height:1.8;'>"

    // Arming
    boolean hasModes  = hasModeRestriction()
    boolean hasSwitch = (vacationSwitch != null)
    if (hasModes && hasSwitch) {
        def modeList = (newMode instanceof List) ? newMode.join(", ") : "${newMode}"
        sb << "✅ <b>Arming:</b> ${modeList} + vacation switch<br>"
    } else if (hasModes) {
        def modeList = (newMode instanceof List) ? newMode.join(", ") : "${newMode}"
        sb << "✅ <b>Arming:</b> ${modeList} mode(s)<br>"
    } else if (hasSwitch) {
        sb << "✅ <b>Arming:</b> vacation switch only<br>"
    } else {
        sb << "❌ <b>Arming:</b> not configured — go to Setup to choose modes or a vacation switch<br>"
    }

    // Lights
    if (switches && switches.size() > 0) {
        int numActive   = (number_of_active_lights ?: 1) as Integer
        int numSwitches = switches.size()
        if (numActive > numSwitches) {
            sb << "⚠️ <b>Lights:</b> ${numSwitches} switches, active count (${numActive}) exceeds pool — will clamp to ${numSwitches}<br>"
        } else {
            sb << "✅ <b>Lights:</b> ${numSwitches} switches, up to ${numActive} per cycle<br>"
        }
    } else {
        sb << "❌ <b>Lights:</b> no switches configured — go to Setup to add lights<br>"
    }

    // Time window
    def winLabel = timeIntervalLabel()
    if (winLabel) {
        sb << "✅ <b>Time window:</b> ${winLabel}<br>"
    } else {
        sb << "⚠️ <b>Time window:</b> not set — runs any time when armed<br>"
    }

    // Notifications
    if (summaryDevice) {
        sb << "✅ <b>Notifications:</b> configured<br>"
    } else {
        sb << "⚠️ <b>Notifications:</b> not configured<br>"
    }

    sb << "</div>"
    return sb.toString()
}

/**
 * Return a one-sentence summary of the effective arming condition based on current inputs.
 * Used in Setup() with submitOnChange so it re-renders as the user configures modes/switch.
 */
private String dynamicArmingSummary() {
    boolean hasModes  = hasModeRestriction()
    boolean hasSwitch = (vacationSwitch != null)

    if (!hasModes && !hasSwitch) {
        return "<span style='color:#cc9900;'>⚠️ Nothing configured — app will not arm.</span>"
    } else if (hasModes && !hasSwitch) {
        def modeList = (newMode instanceof List) ? newMode.join(", ") : "${newMode}"
        return "App arms when mode is: <b>${modeList}</b>"
    } else if (!hasModes && hasSwitch) {
        def swName = vacationSwitch?.displayName ?: "selected vacation switch"
        return "App arms only when <b>${swName}</b> switch is ON"
    } else {
        def modeList = (newMode instanceof List) ? newMode.join(", ") : "${newMode}"
        def swName   = vacationSwitch?.displayName ?: "selected vacation switch"
        return "App arms when mode is <b>${modeList}</b>, OR when <b>${swName}</b> switch is ON " +
               "(switch also bypasses time/day restrictions)"
    }
}

/**
 * Return a human-readable resolved time window for today using sunrise/sunset data.
 * Only returns a value when both start and end are configured.
 */
private String resolvedTimeWindow() {
    if (!startTimeType && !starting) return null
    if (!endTimeType && !ending) return null

    def start = timeWindowStart()
    def stop  = timeWindowStop()
    if (!start || !stop) return null

    def tz = getTimeZone()
    if (!tz) return null

    def fmt = new SimpleDateFormat("h:mm a")
    fmt.setTimeZone(tz)
    return "Today's window: ${fmt.format(start)} → ${fmt.format(stop)}"
}

// --------- PAGES ---------

// Show main page
def pageSetup() {

    def pageProperties = [
        name:       "pageSetup",
        title:      "",           // remove automatic Hubitat title
        nextPage:   null,
        install:    true,
        uninstall:  true
    ]

    return dynamicPage(pageProperties) {

        // --- VERSION + STATUS BLOCK ---
        section("") {
            paragraph "<div style='text-align:right;font-size:11px;color:#888;'>${appVersion()}</div>"
            paragraph getFormat("header-blue", "Status")
            paragraph appStatus()
        }

        // --- CONFIGURATION CHECKLIST ---
        section("") {
            paragraph getFormat("header-blue", "Configuration")
            paragraph configChecklist()
        }

        section("") {
            paragraph "This app simulates occupancy when you are away. Use the sections below to configure when it runs and which lights it controls."
        }

        // --- SETUP BLOCK ---
        section("") {
            paragraph getFormat("header-blue", "🛠 Setup")
            href "Setup",
                title: "Define triggers, time window, and which lights to use",
                description: ""
        }

        // --- SETTINGS BLOCK ---
        section("") {
            paragraph getFormat("header-blue", "⚙️ Settings")
            href "Settings",
                title: "Configure delays, daily summary, days, and advanced options",
                description: ""
        }

        // --- LABEL ---
        section("") {
            label title: "Assign a name for this app (useful if you have multiple instances):", required: false
        }

        // --- TEST & DIAGNOSTICS (collapsed by default) ---
        section(title: "Test & Diagnostics", hideable: true, hidden: true) {
            paragraph "Run a one-off cycle to verify lights are configured correctly. " +
                      "Anchor lights will stay on until the session ends naturally or you use the clear option below."
            if (state?.lastTestSummary) {
                paragraph "<b>Last test:</b> ${state.lastTestSummary}"
            }
            input "runTestNow", "bool",
                title: "Run a test cycle now",
                defaultValue: false,
                submitOnChange: false
            input "clearTestNow", "bool",
                title: "Clear test state (turn off anchor lights left on by test)",
                defaultValue: false,
                submitOnChange: false
        }
    }
}

// Show "Setup" page
def Setup() {

    def newModeInput = [
        name:           "newMode",
        type:           "mode",
        title:          "Modes (e.g., Away/Vacation)",
        multiple:       true,
        required:       false,
        submitOnChange: true,
        description:    "Optional. If left blank, Modes will not arm the app."
    ]
    def vacationSwitchInput = [
        name:           "vacationSwitch",
        type:           "capability.switch",
        title:          "Vacation switch (optional – ON enables app)",
        required:       false,
        multiple:       false,
        submitOnChange: true,
        description:    "Optional. If set, the app runs when this switch is ON OR when an allowed Mode is active. Switch ON also bypasses mode/time restrictions as a manual override."
    ]
    def switchesInput = [
        name:         "switches",
        type:         "capability.switch",
        title:        "Switches to randomize",
        multiple:     true,
        required:     false,
        description:  "Required for the app to actually run, but you can configure time window first."
    ]

    def frequencyInput = [
        name:         "frequency_minutes",
        type:         "number",
        title:        "Minutes between cycles (5–180)",
        range:        "5..180",
        required:     false,
        description:  "Optional. Default is 15 minutes if left blank."
    ]

    def numberActiveInput = [
        name:         "number_of_active_lights",
        type:         "number",
        title:        "Number of active lights per cycle",
        range:        "1..999",
        required:     false,
        description:  "Optional. Default is 1 light per cycle if left blank."
    ]

    def anchorLightsInput = [
        name:         "on_during_active_lights",
        type:         "capability.switch",
        title:        "Anchor lights (on during active times, not randomized)",
        multiple:     true,
        required:     false,
        description:  "Optional. These stay on whenever the app is active."
    ]

    def pageProperties = [
        name:       "Setup",
        title:      "Setup",
        nextPage:   "pageSetup"
    ]

    return dynamicPage(pageProperties) {

        // When should this run?
        section("") {
            paragraph getFormat("header-blue", "When should this run?")
            paragraph "Choose how vacation lighting is armed. You can use Modes, a Vacation switch, or both."
            input newModeInput
            input vacationSwitchInput
            paragraph dynamicArmingSummary()
        }

        // Time window
        section("") {
            paragraph getFormat("header-blue", "Time window")
            paragraph "Optional but recommended. If you skip this, the app can run any time it is armed."
            href "timeIntervalPage",
                title: "Time window",
                description: timeIntervalLabel() ?: "Tap to set a time window (optional)."
        }

        // Which lights and how
        section("") {
            paragraph getFormat("header-blue", "Which lights and how?")
            input switchesInput
            input frequencyInput
            input numberActiveInput
        }

        // Anchor lights
        section("") {
            paragraph getFormat("header-blue", "Anchor lights")
            paragraph "Anchor lights are not randomized. They turn on whenever the app is active and off when the session ends."
            input anchorLightsInput
        }
    }
}

// Show "Settings" page
def Settings() {

    def falseAlarmThresholdInput = [
        name:       "falseAlarmThreshold",
        type:       "decimal",
        title:      "Delay before first cycle (minutes)",
        required:   false,
        description:"Optional. Default is 2 minutes if left blank."
    ]

    def daysInput = [
        name:       "days",
        type:       "enum",
        title:      "Only on certain days of the week",
        multiple:   true,
        required:   false,
        options:    ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
        description:"Optional. If blank, runs on all days."
    ]

    def pageProperties = [
        name:       "Settings",
        title:      "Settings",
        nextPage:   "pageSetup"
    ]

    return dynamicPage(pageProperties) {

        // --- Basic behavior ---
        section("") {
            paragraph getFormat("header-blue", "Behavior")
            input falseAlarmThresholdInput
        }

        // --- Summary notifications ---
        section("") {
            paragraph getFormat("header-blue", "Summary notifications")
            paragraph "Get a summary of how many cycles and light changes ran."
            input "summaryDevice", "capability.notification",
                title: "Notification devices for summary",
                required: false,
                multiple: true,
                description: "Optional. Select one or more devices to receive summaries."
            input "summaryMode", "enum",
                title: "When to send summary",
                options: ["daily": "Daily at specified time", "session": "After each session ends"],
                defaultValue: "daily",
                required: false,
                submitOnChange: true,
                description: "Daily: send at a fixed time each day. Session: send when time window closes, switch turns off, or mode changes."
            if (summaryMode == null || summaryMode == "daily") {
                input "summaryTime", "time",
                    title: "Time each day to send summary",
                    required: false,
                    description: "Optional. If not set, no daily summary will be sent."
            }
        }

        // --- Advanced header bar ---
        section("") {
            paragraph getFormat("header-blue", "Advanced settings")
        }

        // --- Advanced (collapsed by default) ---
        section(title: "Advanced settings", hideable: true, hidden: true) {

            paragraph "<b>Days restrictions</b><br>If today is not in the allowed days list, the app will not run."
            input daysInput

            paragraph "<b>Logging</b>"
            input "descriptiveLogging", "bool",
                title: "Enable descriptive logging?",
                defaultValue: true,
                required: false,
                description: "Info-level status messages (recommended)."
            input "debugLogging", "bool",
                title: "Enable debug logging?",
                defaultValue: false,
                required: false,
                description: "Includes debug + trace details for troubleshooting."
        }
    }
}

def timeIntervalPage() {
    dynamicPage(name: "timeIntervalPage", title: "Only during a certain time") {
        section {
            input "startTimeType", "enum",
                title: "Starting at",
                options: [time: "A specific time", sunrise: "Sunrise", sunset: "Sunset"],
                submitOnChange: true,
                description: "Optional. Leave blank to allow starting at any time."
            if (startTimeType in ["sunrise","sunset"]) {
                input "startTimeOffset", "number",
                    title: "Offset in minutes (+/-)",
                    range: "*..*",
                    required: false,
                    description: "Optional. Positive = after, negative = before."
            }
            else {
                input "starting", "time",
                    title: "Start time",
                    required: false,
                    description: "Optional. If blank, no specific start time."
            }
        }
        section {
            input "endTimeType", "enum",
                title: "Ending at",
                options: [time: "A specific time", sunrise: "Sunrise", sunset: "Sunset"],
                submitOnChange: true,
                description: "Optional. Leave blank to allow running indefinitely after start."
            if (endTimeType in ["sunrise","sunset"]) {
                input "endTimeOffset", "number",
                    title: "Offset in minutes (+/-)",
                    range: "*..*",
                    required: false,
                    description: "Optional. Positive = after, negative = before."
            }
            else {
                input "ending", "time",
                    title: "End time",
                    required: false,
                    description: "Optional. If blank, no specific end time."
            }

            // Show resolved window for today when both start and end are configured
            def resolved = resolvedTimeWindow()
            if (resolved) {
                paragraph "<i style='color:#555;'>${resolved}</i>"
            }
        }
    }
}

// --------- LIFECYCLE ---------

def installed() {
    state.Running = false
    state.schedRunning = false
    state.startendRunning = false
    state.cycles = 0
    state.lightsOn = 0
    state.lightsOff = 0
    state.lightSchedule = [:]
    state.offTickScheduled = false
    state.nextCycleAtMs = null

    // Track which devices have been used for summaries
    state.summaryOnNames   = []
    state.summaryOffNames  = []

    // Track last cycle devices
    state.lastCycleRandomized = []
    state.lastCycleAnchors    = []
    state.lastCycleAt         = null

    initialize()
}

def updated() {
    // Snapshot flags before re-init (settings are read at render time)
    def doTest  = runTestNow
    def doClear = clearTestNow

    unsubscribe()
    clearState(true, true)
    initialize()

    // Handle test AFTER normal initialization
    if (doTest) {
        runTestCycle()
        app.updateSetting("runTestNow", [value: "false", type: "bool"])
    }

    // Clear test state: lights already turned off by clearState(true, true) above.
    // Reset lastTestSummary and toggle so the UI reflects the cleared state.
    if (doClear) {
        state.lastTestSummary = null
        app.updateSetting("clearTestNow", [value: "false", type: "bool"])
        logInfo "[TEST] Test state cleared by user."
    }
}

/**
 * initialize():
 * - Always subscribes to mode/vacationSwitch.
 * - Only schedules daily summary, start/end, and initCheck if armOk is true.
 */
def initialize() {
    if (hasModeRestriction()) {
        subscribe(location, "mode", modeChangeHandler)
    }
    if (vacationSwitch) {
        subscribe(vacationSwitch, "switch", vacationSwitchHandler)
    }

    if (armOk) {
        // Schedule daily summary only when armed
        scheduleSummary()
        // Only set up time window + scheduling if we are currently armed
        schedStartEnd()
        logInfo "Initialized while armed; scheduling checks. Settings: ${settings}"
        setSched()
    } else {
        state.schedRunning = false
        state.startendRunning = false
        logInfo "Initialized while NOT armed (mode='${location.mode}', vacationSwitch='${vacationSwitch ? vacationSwitch.currentSwitch : "n/a"}')."
    }
}

/**
 * Clear state and optionally turn off managed lights.
 * turnOff = true when we want a hard stop (wrong arm state, mode, etc.).
 */
def clearState(turnOff = false, boolean unscheduleStartEnd = false) {
    if (turnOff) {

        // Turn off any lights we have queued
        def schedule = state.lightSchedule ?: [:]
        schedule.each { devId, offTs ->
            def dev = switches?.find { it.id == devId }
            if (dev) {
                dev.off()
                state.lightsOff = (state.lightsOff ?: 0) + 1
            }
        }
        state.lightSchedule = [:]
        state.offTickScheduled = false
        unschedule(offTick)

        // Turn off anchor lights
        if (on_during_active_lights) {
            on_during_active_lights.each { dev ->
                dev.off()
                state.lightsOff = (state.lightsOff ?: 0) + 1
            }
        }

        logTrace "All OFF due to clearState(true)"
    }

    state.Running = false
    state.schedRunning = false
    state.lastUpdDt = null
    state.nextCycleAtMs = null

    // Always stop cycle engine timers
    unschedule(initCheck)
    unschedule(failsafe)
    unschedule(offTick)

    // Only remove daily start/end triggers when truly disarmed
    if (unscheduleStartEnd) {
        unschedule(startTimeCheck)
        unschedule(endTimeCheck)
        state.startendRunning = false
    }
}

/**
 * schedStartEnd():
 * - Only schedules start/end time checks when armOk is true.
 */
def schedStartEnd() {
    if (!armOk) {
        logTrace "schedStartEnd(): not armed, not scheduling start/end."
        state.startendRunning = false
        return
    }

    state.startendRunning = false

    def nowDt = new Date()

    if (starting != null || startTimeType != null) {
        def start = timeWindowStart(true)
        if (start) {
            if (start.before(nowDt)) {
                start = new Date(start.time + 24L * 60L * 60L * 1000L)
            }
            runOnce(start, startTimeCheck)
            state.startendRunning = true
        }
    }
    if (ending != null || endTimeType != null) {
        def end = timeWindowStop(true)
        if (end) {
            if (end.before(nowDt)) {
                end = new Date(end.time + 24L * 60L * 60L * 1000L)
            }
            runOnce(end, endTimeCheck)
            state.startendRunning = true
        }
    }

    logTrace "schedStartEnd(): startendRunning = ${state.startendRunning}"
}

/**
 * Schedule initial check after a delay (falseAlarmThreshold).
 * Ensures we pass an Integer to runIn() and tracks nextCycleAtMs.
 */
def setSched() {
    state.schedRunning = true

    // Use default 2 minutes if not set
    def base = (falseAlarmThreshold != null && falseAlarmThreshold != "") ? falseAlarmThreshold : 2
    Integer delaySec = ((base as BigDecimal) * 60) as Integer
    // Bounds checking
    if (delaySec < 1) {
        logDebug "setSched(): delay too small (${delaySec}), clamping to 1 second"
        delaySec = 1
    }
    if (delaySec > 86400) {  // Max 24 hours
        logDebug "setSched(): delay too large (${delaySec}), clamping to 86400 seconds (24 hours)"
        delaySec = 86400
    }

    long nowMs = now()
    state.nextCycleAtMs = nowMs + (delaySec * 1000L)

    logTrace "setSched() scheduling initCheck in ${delaySec} seconds"
    runIn(delaySec, initCheck)
}

// --------- Handlers ---------

def modeChangeHandler(evt) {
    logTrace "modeChangeHandler ${evt}, armOk=${armOk}"

    if (!armOk) {
        logDebug "modeChangeHandler: armOk=false (mode='${location.mode}', switch='${vacationSwitch ? vacationSwitch.currentSwitch : "n/a"}') - clearing and unscheduling."
        sendSessionSummary("mode changed to '${location.mode}'")
        boolean wasRunning = state.Running || state.schedRunning
        clearState(wasRunning, true)
    } else {
        logDebug "modeChangeHandler: armOk=true - scheduling vacation lighting."
        state.schedRunning = false
        state.startendRunning = false
        schedStartEnd()
        setSched()
        scheduleSummary()
    }
}

def vacationSwitchHandler(evt) {
    logTrace "vacationSwitchHandler ${evt}, armOk=${armOk}"

    if (!armOk) {
        logInfo "Vacation switch changed to ${evt.value}, armOk=false - clearing and unscheduling."
        sendSessionSummary("vacation switch turned off")
        clearState(true, true)
    } else {
        logInfo "Vacation switch changed to ${evt.value}, armOk=true - scheduling vacation lighting."
        state.schedRunning = false
        state.startendRunning = false
        schedStartEnd()
        setSched()
        scheduleSummary()
    }
}

def initCheck() {
    scheduleCheck(null)
}

def failsafe() {
    scheduleCheck(null)
}

/**
 * startTimeCheck():
 * - Only schedules next cycle if still armed.
 * - Reschedules start/end triggers for the next day.
 */
def startTimeCheck() {
    logTrace "startTimeCheck"
    // Mark trigger as consumed so self-heal logic knows to reschedule
    state.startendRunning = false

    if (armOk && daysOk) {
        setSched()
    } else {
        logDebug "startTimeCheck(): not scheduling (armOk=${armOk}, daysOk=${daysOk})."
    }

    // Reschedule start/end triggers for tomorrow while still armed
    if (armOk) {
        schedStartEnd()
    }
}

/**
 * endTimeCheck():
 * - If still armed, lets scheduleCheck handle time window.
 * - If not armed, ensures everything is cleared.
 * - Reschedules start/end triggers for the next day.
 */
def endTimeCheck() {
    logTrace "endTimeCheck"
    // Mark trigger as consumed so self-heal logic knows to reschedule
    state.startendRunning = false

    if (armOk) {
        // Send session summary before shutting down (if in session mode)
        sendSessionSummary("time window ended")
        // Let scheduleCheck handle shutting things down due to time window
        scheduleCheck(null)
        // Reschedule start/end triggers for tomorrow
        schedStartEnd()
    } else {
        logDebug "endTimeCheck(): armOk=false, ensuring everything is cleared."
        clearState(true, true)
    }
}

// --------- Time helpers ---------

def getDtNow() {
    def now = new Date()
    return formatDt(now)
}

def formatDt(dt) {
    def tf = new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy")
    if (getTimeZone()) {
        tf.setTimeZone(getTimeZone())
    } else {
        log.warn "TimeZone is not found or is not set..."
    }
    return tf.format(dt)
}

def GetTimeDiffSeconds(lastDate) {
    if (lastDate?.contains("dtNow")) { return 10000 }
    def now = new Date()
    def lastDt = Date.parse("E MMM dd HH:mm:ss z yyyy", lastDate)
    def start = Date.parse("E MMM dd HH:mm:ss z yyyy", formatDt(lastDt)).getTime()
    def stop = Date.parse("E MMM dd HH:mm:ss z yyyy", formatDt(now)).getTime()
    def diff = (int) (long) (stop - start) / 1000
    return diff
}

def getTimeZone() {
    def tz = null
    if (location?.timeZone) { tz = location.timeZone }
    if (!tz) { log.warn "getTimeZone: TimeZone is not found or is not set..." }
    return tz
}

def getLastUpdSec() {
    return !state?.lastUpdDt ? 100000 : GetTimeDiffSeconds(state?.lastUpdDt).toInteger()
}

// --------- MAIN LOGIC: cycles + queue ---------

private getAllOk() {
    armOk && daysOk && (timeOk || vacationSwitchOn())
}

/**
 * Extracted core of a single cycle:
 * - increments cycle counter
 * - chooses random lights
 * - turns them on and queues them for off
 * - turns on anchor lights
 * Does NOT schedule next cycle or touch lastUpdDt / nextCycleAtMs.
 */
private void doCycleCore() {
    def eligible = switches
    if (!eligible || !eligible.size()) {
        log.warn "No switches configured or available"
        return
    }

    def random = new Random()
    state.cycles = (state.cycles ?: 0) + 1
    Integer cycleNum = (state.cycles ?: 0) as Integer

    def numlight = (number_of_active_lights ?: 1) as Integer
    // Enforce lower bound
    if (numlight < 1) numlight = 1
    // Enforce upper bound
    if (numlight > eligible.size()) numlight = eligible.size()

    Integer freq = (frequency_minutes ?: 15) as Integer
    if (freq < 5) freq = 5
    if (freq > 180) freq = 180

    logDebug "Running cycle #${cycleNum}: freq=${freq} min, mode='${location.mode}'"

    def usedIdx = []
    def selectedNames = []
    for (int i = 0; i < numlight; i++) {
        int idx = random.nextInt(eligible.size())
        while (usedIdx.contains(idx) && usedIdx.size() < eligible.size()) {
            idx = random.nextInt(eligible.size())
        }
        if (usedIdx.contains(idx)) {
            break
        }
        usedIdx << idx

        def dev = eligible[idx]
        def dn  = dev.displayName ?: dev.name
        selectedNames << dn

        dev.on()
        state.lightsOn = (state.lightsOn ?: 0) + 1

        // Track this device as having been turned on
        def onList = (state.summaryOnNames ?: []) as List
        if (!onList.contains(dn)) {
            onList << dn
        }
        state.summaryOnNames = onList

        scheduleLightOff(dev)
        logTrace "Cycle #${cycleNum}: turned ON '${dn}' (randomized, will auto-off later)"
    }

    // High-signal line: which lights were chosen this cycle
    if (selectedNames) {
        logTrace "[cycle=${cycleNum}] Starting: choosing ${numlight} of ${eligible.size()} lights " +
                 "(mode='${location.mode}', freq=${freq} min) — ${selectedNames.join(', ')}"
    } else {
        logTrace "[cycle=${cycleNum}] Starting: choosing 0 of ${eligible.size()} lights " +
                 "(mode='${location.mode}', freq=${freq} min) — none selected"
    }

    // Anchor lights stay on while simulation is running; not queued
    def anchorNames = []
    if (on_during_active_lights) {
        on_during_active_lights.each { dev ->
            dev.on()
            state.lightsOn = (state.lightsOn ?: 0) + 1

            def dn = dev.displayName ?: dev.name
            anchorNames << dn

            def onList = (state.summaryOnNames ?: []) as List
            if (!onList.contains(dn)) {
                onList << dn
            }
            state.summaryOnNames = onList

            logTrace "Cycle #${cycleNum}: turned ON anchor '${dn}' (stays on while app is active)"
        }
    }

    // Update "last cycle" info for Status UI
    state.lastCycleRandomized = selectedNames
    state.lastCycleAnchors    = anchorNames
    state.lastCycleAt         = new Date().time
}

def scheduleCheck(evt) {
    // normalize frequency to an Integer
    Integer freq = (frequency_minutes ?: 15) as Integer
    // Clamp to UI's valid range
    if (freq < 5) freq = 5
    if (freq > 180) freq = 180

    if (allOk && getLastUpdSec() > ((freq - 1) * 60)) {
        state.lastUpdDt = getDtNow()
        logDebug "Running scheduled vacation lighting cycle (freq=${freq} min, last run ${getLastUpdSec()} sec ago)"
        state.Running = true

        doCycleCore()

        // schedule next cycle & failsafe
        def random = new Random()
        def random_int = random.nextInt(14)
        logTrace "scheduleCheck(): scheduling next cycle in ${(freq + random_int)} minutes (freq=${freq}, jitter=${random_int})"

        Integer nextSec     = ((freq + random_int) * 60) as Integer
        Integer failsafeSec = ((freq + random_int + 10) * 60) as Integer
        if (nextSec < 1) nextSec = 1
        if (failsafeSec < 1) failsafeSec = 1

        long nowMs = now()
        state.nextCycleAtMs = nowMs + (nextSec * 1000L)

        runIn(nextSec,     initCheck, [overwrite: true])
        runIn(failsafeSec, failsafe,  [overwrite: true])

    } else if (allOk && getLastUpdSec() <= ((freq - 1) * 60)) {
        Integer remaining = (freq * 60 - getLastUpdSec())
        if (remaining < 1) remaining = 1
        logTrace "scheduleCheck(): too soon since last cycle (${getLastUpdSec()} sec); will retry in ${remaining} sec"

        state.nextCycleAtMs = now() + (remaining * 1000L)

        runIn(remaining, initCheck, [overwrite: true])

    } else if (!armOk) {
        if ((state?.Running ?: false) || (state?.schedRunning ?: false)) {
            logDebug "scheduleCheck(): disarmed - stopping Vacation Lights"
            clearState(true, true)
        }

    } else if (!daysOk) {
        if ((state?.Running ?: false) || (state?.schedRunning ?: false)) {
            logDebug "scheduleCheck(): wrong day - stopping Vacation Lights"
            clearState(true, false)
        }

    } else if (armOk && daysOk && !timeOk && !vacationSwitchOn()) {
        if ((state?.Running ?: false) || (state?.schedRunning ?: false)) {
            logDebug "scheduleCheck(): wrong time window - stopping Vacation Lights"
            clearState(true, false)
        }

        // Self-heal: if start/end checks were somehow lost, recreate them
        if (!(state.startendRunning ?: false)) {
            schedStartEnd()
        }
    }

    // Only (re)schedule start/end when armed
    if (armOk && !(state.startendRunning ?: false)) {
        schedStartEnd()
    }
    return true
}

// --- Test cycle (one-off) ---
def runTestCycle() {
    logInfo "[TEST] Test cycle requested"

    if (!switches || !switches.size()) {
        logInfo "[TEST] No switches configured, aborting test."
        return
    }

    logInfo "[TEST] Running one test cycle regardless of mode/time/day. " +
             "Current mode='${location.mode}', vacationSwitch='${vacationSwitch ? vacationSwitch.currentSwitch : "n/a"}'."

    //
    // We do *not* increment or manipulate state.cycles here yet — doCycleCore() will do that.
    // We also do NOT snapshot before/after values, because tests always run exactly 1 cycle,
    //

    // Force a cycle (ignoring restrictions)
    state.Running = true
    doCycleCore()

    //
    // Build the list of lights used in this *test* cycle.
    //
    List testRandom = (state.lastCycleRandomized ?: []) as List
    List testAnchors = (state.lastCycleAnchors ?: []) as List
    List testOnNames = (testRandom + testAnchors) as List

    // dCycles = always 1 for test
    Integer dCycles = 1
    // dOn counts number of lights turned on in this cycle
    Integer dOn = testOnNames.size()
    // dOff is 0 at test completion (offs will happen asynchronously later)
    Integer dOff = 0

    // Store a short summary for display on the main page
    def tz = getTimeZone()
    def timeStr = tz ? new Date().format("MMM d, h:mm a", tz) : new Date().toString()
    def lightsStr = testOnNames ? testOnNames.join(", ") : "none"
    state.lastTestSummary = "Ran at ${timeStr}: ${dCycles} cycle, ${dOn} light(s) on — ${lightsStr}. " +
                            "Anchor lights stay on until cleared or session ends."

    sendTestSummary(dCycles, dOn, dOff, testOnNames)
    // Test cycle is done.
    // We do NOT clear queued offs — those must continue running.
    // But we DO stop reporting "Active" in the UI.
    state.Running = false
    state.schedRunning = false
}


private void sendTestSummary(Integer dCycles, Integer dOn, Integer dOff, List testOnNames) {

    if (!summaryDevice) {
        logDebug "[TEST] Test summary not sent — no summaryDevice configured"
        return
    }

    // Build unified formatted message (using our shared formatter)
    String msg = buildSummaryMessage(
        "test",
        dCycles,
        dOn,
        dOff,
        testOnNames,
        [] // no test-off names; offs happen later
    )

    logDebug "[TEST] Sending test summary: ${msg}"
    summaryDevice*.deviceNotification(msg)
}

// --- Queue-based per-light off scheduling ---

/**
 * Determine how long each light should stay on (minutes).
 * Style B: base on frequency_minutes ± ~20% jitter, clamped to 5–180 minutes.
 */
private Integer getLightOnDuration() {
    Integer base = (frequency_minutes ?: 15) as Integer
    if (base < 5) base = 5
    if (base > 180) base = 180

    // ±20% jitter around base
    Integer jitter = Math.round(base * 0.2)
    if (jitter < 1) jitter = 1

    Integer min = base - jitter
    Integer max = base + jitter
    if (min < 1) min = 1

    def rnd = new Random()
    Integer dur = rnd.nextInt(max - min + 1) + min
    logTrace "Light duration: base=${base} min, jitter=±${jitter} → range ${min}-${max} min, chosen=${dur} min"
    return dur
}

/**
 * Add a light to the off queue.
 */
private scheduleLightOff(dev) {
    def durationMins = getLightOnDuration()
    def now = new Date()
    long offAt = now.time + (durationMins * 60 * 1000L)

    def schedule = state.lightSchedule ?: [:]
    schedule[dev.id as String] = offAt
    state.lightSchedule = schedule

    Integer queueSize = schedule.size()
    def dn = dev.displayName ?: dev.name

    logTrace "Queueing off: '${dn}' in ${durationMins} min at ${new Date(offAt)} (queue size now ${queueSize})"
    ensureOffTickScheduled()
}

/**
 * Make sure the offTick loop is running if there are lights in the queue.
 */
private ensureOffTickScheduled() {
    if (!(state.offTickScheduled ?: false)) {
        state.offTickScheduled = true
        logTrace "ensureOffTickScheduled(): scheduling offTick in 60 seconds"
        runIn(60, "offTick", [overwrite: true])
    }
}

/**
 * Periodic tick: check which lights should be turned off now.
 */
def offTick() {
    def schedule = state.lightSchedule ?: [:]
    if (!schedule || schedule.isEmpty()) {
        state.offTickScheduled = false
        logTrace "offTick(): queue empty, stopping off-tick loop (no more scheduled offs)"
        return
    }

    long nowMs = now()

    def toTurnOff = []
    schedule.each { devId, offAt ->
        if ((offAt as Long) <= nowMs) {
            toTurnOff << devId
        }
    }

    if (!toTurnOff.isEmpty()) {
        logTrace "offTick(): ${toTurnOff.size()} light(s) reached their off time"
    }

    // Build map for O(1) lookup
    def switchMap = switches?.collectEntries { [(it.id as String): it] } ?: [:]

    toTurnOff.each { devId ->
        def dev = switchMap[devId]
        if (dev) {
            dev.off()
            state.lightsOff = (state.lightsOff ?: 0) + 1

            // Track this device as having been turned off
            def offList = (state.summaryOffNames ?: []) as List
            def dn = dev.displayName ?: dev.name
            if (!offList.contains(dn)) {
                offList << dn
            }
            state.summaryOffNames = offList

            logTrace "offTick: turned OFF '${dn}' (duration expired)"
        }
        schedule.remove(devId)
    }

    state.lightSchedule = schedule

    if (!schedule.isEmpty()) {
        logTrace "offTick(): ${schedule.size()} light(s) still queued for future off; next check in 60 seconds"
        runIn(60, "offTick", [overwrite: true])
    } else {
        logTrace "offTick(): all queued lights processed; not scheduling another offTick"
        state.offTickScheduled = false
    }
}

// --------- RESTRICTIONS ---------

private getDaysOk() {
    def result = true
    if (days) {
        def df = new java.text.SimpleDateFormat("EEEE")
        if (getTimeZone()) {
            df.setTimeZone(getTimeZone())
        } else {
            df.setTimeZone(TimeZone.getTimeZone("America/New_York"))
        }
        def day = df.format(new Date())
        result = days.contains(day)
    }
    result
}

/**
 * Determine if the current time is within the configured window.
 *
 * Handles both "same-day" windows (e.g., 18:00 → 23:00)
 * and "overnight" windows that cross midnight (e.g., Sunset+X → Sunrise+Y).
 */
private getTimeOk() {
    def tz    = getTimeZone()
    def start = timeWindowStart()
    def stop  = timeWindowStop(false, true) // includes small end-time adjustment
    // Help debug any timing problems
    logDebug "For debuging timing issues: getTimeOk: start=${start}, stop=${stop}, now=${new Date()}, tz=${getTimeZone()}"

    // If no window is configured (or we can't compute it), treat as "no restriction".
    if (!start || !stop || !tz) {
        return true
    }

    Date now = new Date()

    // Normal same-day window: start < stop
    if (start.before(stop)) {
        return timeOfDayIsBetween(start, stop, now, tz)
    }

    // Degenerate case: start == stop → zero-length window, treat as closed
    if (start.equals(stop)) {
        return false
    }

    // Overnight window: start > stop (e.g., Sunset+X → Sunrise+Y).
    // Interpret this as:
    //   from start time in the evening, through midnight,
    //   and up until stop time in the morning.
    //
    // Trick: the complement of the "forbidden" region (stop → start).
    // If we're NOT between stop and start, then we ARE in the overnight window.
    return !timeOfDayIsBetween(stop, start, now, tz)
}


// --- Sunrise / Sunset & time window helpers adapted for Hubitat ---

private timeWindowStart(usehhmm = false) {
    def result = null
    if (startTimeType == "sunrise") {
        def sunTimes = getSunriseAndSunset()
        result = sunTimes?.sunrise
        if (result && startTimeOffset) {
            result = new Date(result.time + Math.round(startTimeOffset * 60000))
        }
    }
    else if (startTimeType == "sunset") {
        def sunTimes = getSunriseAndSunset()
        result = sunTimes?.sunset
        if (result && startTimeOffset) {
            result = new Date(result.time + Math.round(startTimeOffset * 60000))
        }
    }
    else if (starting && getTimeZone()) {
        if (usehhmm) { result = timeToday(hhmm(starting), getTimeZone()) }
        else { result = timeToday(starting, getTimeZone()) }
    }
    result
}

private timeWindowStop(usehhmm = false, adj = false) {
    def result = null
    if (endTimeType == "sunrise") {
        def sunTimes = getSunriseAndSunset()
        result = sunTimes?.sunrise
        if (result && endTimeOffset) {
            result = new Date(result.time + Math.round(endTimeOffset * 60000))
        }
    }
    else if (endTimeType == "sunset") {
        def sunTimes = getSunriseAndSunset()
        result = sunTimes?.sunset
        if (result && endTimeOffset) {
            result = new Date(result.time + Math.round(endTimeOffset * 60000))
        }
    }
    else if (ending && getTimeZone()) {
        if (usehhmm) { result = timeToday(hhmm(ending), getTimeZone()) }
        else { result = timeToday(ending, getTimeZone()) }
    }

    if (result && adj) {
        def result1 = new Date(result.time - (2 * 60 * 1000))
        logDebug "timeWindowStop = ${result} adjusted: ${result1}"
        result = result1
    }
    return result
}

private hhmm(time, fmt = "HH:mm") {
    def t = timeToday(time, getTimeZone())
    def f = new java.text.SimpleDateFormat(fmt)
    f.setTimeZone(getTimeZone())
    f.format(t)
}

private timeIntervalLabel() {
    def start = ""
    switch (startTimeType) {
        case "time":
            if (starting) {
                start += hhmm(starting)
            }
            break
        case "sunrise":
        case "sunset":
            start += startTimeType[0].toUpperCase() + startTimeType[1..-1]
            if (startTimeOffset) {
                start += startTimeOffset > 0 ? "+${startTimeOffset} min" : "${startTimeOffset} min"
            }
            break
    }

    def finish = ""
    switch (endTimeType) {
        case "time":
            if (ending) {
                finish += hhmm(ending)
            }
            break
        case "sunrise":
        case "sunset":
            finish += endTimeType[0].toUpperCase() + endTimeType[1..-1]
            if (endTimeOffset) {
                finish += endTimeOffset > 0 ? "+${endTimeOffset} min" : "${endTimeOffset} min"
            }
            break
    }
    start && finish ? "${start} to ${finish}" : ""
}

// --------- DAILY SUMMARY ---------

def scheduleSummary() {
    // cancel any existing dailySummary schedule only
    unschedule(dailySummary)

    // Only schedule daily summaries if in daily mode (or not set, for backward compat)
    if (summaryMode == "session") {
        logDebug "Summary mode is 'session', not scheduling daily summary"
        state.nextSummaryAtMs = null
        return
    }

    if (!summaryTime) {
        logDebug "No summaryTime configured, not scheduling daily summary"
        state.nextSummaryAtMs = null
        return
    }

    def tz = getTimeZone()
    def next = timeToday(summaryTime, tz)
    def now = new Date()

    // if today's time already passed, schedule for tomorrow
    if (next.before(now)) {
        next = new Date(next.time + 24L * 60L * 60L * 1000L)
    }

    logDebug "Scheduling daily summary for ${next}"
    state.nextSummaryAtMs = next.time
    runOnce(next, dailySummary)
}

def dailySummary() {
    Integer cycles    = (state.cycles   ?: 0) as Integer
    Integer lightsOn  = (state.lightsOn ?: 0) as Integer
    Integer lightsOff = (state.lightsOff?: 0) as Integer

    List onNames  = (state.summaryOnNames  ?: []) as List
    List offNames = (state.summaryOffNames ?: []) as List

    // Only send summary if there was actual activity (cycles > 0)
    if (cycles > 0 && summaryDevice) {
        String msg = buildSummaryMessage("daily", cycles, lightsOn, lightsOff, onNames, offNames)
        logDebug "Sending daily summary: ${msg}"
        summaryDevice*.deviceNotification(msg)
    } else if (cycles == 0) {
        logDebug "Daily summary skipped: no cycles ran in the last 24 hours"
    } else {
        logDebug "Daily summary skipped: no summaryDevice configured"
    }

    // Reset counters and name lists for the next day
    state.cycles         = 0
    state.lightsOn       = 0
    state.lightsOff      = 0
    state.summaryOnNames = []
    state.summaryOffNames= []

    // Only reschedule if still armed; otherwise let arming trigger schedule it again
    if (armOk) {
        scheduleSummary()
    } else {
        state.nextSummaryAtMs = null
        logDebug "Daily summary not rescheduled: app is not armed"
    }
}

/**
 * Send a session summary if in session mode and there was activity.
 * Called when session ends (time window closes, switch off, mode change).
 */
private void sendSessionSummary(String reason) {
    // Only send if in session mode
    if (summaryMode != "session") {
        return
    }

    Integer cycles    = (state.cycles   ?: 0) as Integer
    Integer lightsOn  = (state.lightsOn ?: 0) as Integer
    Integer lightsOff = (state.lightsOff?: 0) as Integer

    List onNames  = (state.summaryOnNames  ?: []) as List
    List offNames = (state.summaryOffNames ?: []) as List

    // Only send if there was activity
    if (cycles > 0 && summaryDevice) {
        String msg = buildSummaryMessage("session", cycles, lightsOn, lightsOff, onNames, offNames, reason)
        logDebug "Sending session summary (${reason}): ${msg}"
        summaryDevice*.deviceNotification(msg)
    } else if (cycles == 0) {
        logDebug "Session summary skipped (${reason}): no cycles ran this session"
    } else {
        logDebug "Session summary skipped (${reason}): no summaryDevice configured"
    }

    // Reset counters for next session
    state.cycles         = 0
    state.lightsOn       = 0
    state.lightsOff      = 0
    state.summaryOnNames = []
    state.summaryOffNames= []
}

/**
 * Build a unified summary message for daily, session, and test summaries.
 *
 * @param mode       "daily", "session", or "test"
 * @param cycles     Number of cycles in this summary window
 * @param lightsOn   Number of lights turned on
 * @param lightsOff  Number of lights turned off
 * @param onNames    List of device names turned on during this window
 * @param offNames   List of device names turned off during this window
 * @param reason     (session only) Why the session ended
 */
private String buildSummaryMessage(String mode, Integer cycles, Integer lightsOn, Integer lightsOff, List onNames = null, List offNames = null, String reason = null) {

    cycles    = (cycles    ?: 0) as Integer
    lightsOn  = (lightsOn  ?: 0) as Integer
    lightsOff = (lightsOff ?: 0) as Integer

    onNames  = (onNames  ?: []) as List
    offNames = (offNames ?: []) as List

    boolean noActivity = (cycles == 0 && lightsOn == 0 && lightsOff == 0 && onNames.isEmpty() && offNames.isEmpty())

    if (mode == "test") {
        if (noActivity) {
            return "🧪 Vacation Lighting Test Complete:\n" +
                   "• No cycles or light actions were recorded\n" +
                   "(Check your configuration.)"
        } else {
            String msg = "🧪 Vacation Lighting Test Complete:\n" +
                         "• 🟢 ${cycles} cycle(s) simulated\n" +
                         "• 💡 ${lightsOn} light(s) turned on\n" +
                         "• 💤 Light offs will occur automatically as their random durations expire\n"

            if (!onNames.isEmpty()) {
                msg += "• 💡 Lights turned on: ${onNames.join(', ')}\n"
            }
            if (!offNames.isEmpty()) {
                msg += "• 💤 Lights turned off: ${offNames.join(', ')}\n"
            }

            msg += "\n🔁 This mirrors real vacation-mode behavior."
            return msg
        }
    }
    // Session mode
    if (mode == "session") {
        String msg = "🌙 Vacation Lighting Session Complete:\n" +
                     "• 🟢 ${cycles} cycle(s) simulated\n" +
                     "• 💡 ${lightsOn} light(s) turned on\n" +
                     "• 💤 ${lightsOff} light(s) turned off\n"

        if (!onNames.isEmpty()) {
            msg += "• 💡 Lights used: ${onNames.join(', ')}\n"
        }

        if (reason) {
            msg += "\n🛑 Session ended: ${reason}"
        }
        return msg
    }

    // Default to "daily" semantics
    if (cycles == 0 && onNames.isEmpty() && offNames.isEmpty()) {
        return "📊 Vacation Lighting Daily Summary:\n" +
               "• No cycles ran in the last 24 hours\n" +
               "• Likely outside the time window, not armed, or not triggered"
    } else {
        String msg = "📊 Vacation Lighting Daily Summary:\n" +
                     "• 🟢 ${cycles} cycle(s) simulated\n" +
                     "• 💡 ${lightsOn} light(s) turned on\n" +
                     "• 💤 ${lightsOff} light(s) turned off\n"

        if (!onNames.isEmpty()) {
            msg += "• 💡 Lights turned on: ${onNames.join(', ')}\n"
        }
        if (!offNames.isEmpty()) {
            msg += "• 💤 Lights turned off: ${offNames.join(', ')}\n"
        }

        msg += "• 🔁 Additional light offs may still occur (queued)\n\n" +
               "🏠 Summary covers the last 24 hours of vacation-mode behavior."
        return msg
    }
}

// --------- SETUP COMPLETENESS FLAGS ---------

// sets complete/not complete for the setup section on the main dynamic page
def greyedOut() {
    def result = ""
    if (switches) {
        result = "complete"
    }
    result
}

// sets complete/not complete for the settings section on the main dynamic page
def greyedOutSettings() {
    def result = ""
    if (days || falseAlarmThreshold || summaryDevice || summaryTime || descriptiveLogging != null || debugLogging != null) {
        result = "complete"
    }
    result
}
