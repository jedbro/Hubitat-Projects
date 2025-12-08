/**
 *  Vacation Lighting Simulator
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

@Field static final String APP_VERSION = "v0.2.5 • Dec 2025"

definition(
    name: "Vacation Lighting Simulator",
    namespace: "Logicalnonsense",
    author: "Jed Brown",
    category: "Safety & Security",
    description: "Simulate light and switch behaviors of an occupied home while you are away or on Vacation.",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name:"pageSetup")
    page(name:"Setup")
    page(name:"Settings")
    page(name:"timeIntervalPage")
}

// --------- Logging helpers ---------

private Boolean isLogEnabled() {
    // default to true if user hasn't set it yet
    return (logEnable != null ? logEnable : true)
}

private logDebug(msg) {
    if (isLogEnabled()) {
        log.debug msg
    }
}

private logTrace(msg) {
    if (isLogEnabled()) {
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

/**
 * armOk:
 *  - If no vacationSwitch configured: rely on modes only
 *  - If vacationSwitch configured: require switch ON, and also enforce modes if configured
 */
private getArmOk() {
    boolean byMode   = (!newMode || newMode.contains(location.mode))
    boolean bySwitch = (!vacationSwitch || vacationSwitch.currentSwitch == "on")
    return byMode && bySwitch
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
 */
private String whyNotRunning() {
    def reasons = []

    if (newMode && !modeOk) {
        reasons << "Mode '${location.mode}' is not in allowed modes ${newMode}"
    }

    if (vacationSwitch) {
        def sw = vacationSwitch.currentSwitch
        if (sw != "on") {
            reasons << "Vacation switch '${vacationSwitch.displayName}' is ${sw ?: 'unknown'} (must be ON)"
        }
    }

    if (!daysOk && days) {
        reasons << "Today is not in allowed days ${days}"
    }

    if (!timeOk && (startTimeType || endTimeType || starting || ending)) {
        def win = timeIntervalLabel()
        if (win) {
            reasons << "Current time is outside the configured window (${win})"
        } else {
            reasons << "Current time is outside the configured time window"
        }
    }

    if (!reasons) {
        return "Not running, but no specific blockers detected (check configuration)."
    }

    return "Not running because: " + reasons.join("; ")
}

private String appStatus() {
    def modeName = location?.mode ?: "Unknown"
    def configured = (switches && (newMode || vacationSwitch))

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
        sb << "Configure Modes and/or a Vacation switch plus your lights to enable vacation lighting."
        return sb.toString()
    }

    boolean armed   = armOk
    boolean running = state?.Running ?: false
    boolean sched   = state?.schedRunning ?: false
    boolean queued  = (state?.lightSchedule instanceof Map) && !state.lightSchedule.isEmpty()

    String icon
    String color
    String label

    if (running || sched || queued) {
        icon  = "✅"
        color = "#008800"
        label = "Active (simulating occupancy)"
    } else if (armed) {
        icon  = "🟢"
        color = "#008800"
        label = "Armed (ready; waiting for next cycle within the allowed time window)"
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

    if (!lastRand.isEmpty()) {
        sb << "<b>Last cycle randomized:</b> ${lastRand.join(', ')}<br>"
    }
    if (!lastAnch.isEmpty()) {
        sb << "<b>Anchor lights in last cycle:</b> ${lastAnch.join(', ')}<br>"
    }

    sb << "<b>Since last summary:</b> ${cycles} cycles, ${lightsOn} light-ons, ${lightsOff} light-offs.<br>"
    sb << "<b>Next cycle:</b> ${nextCycleStatus()}<br>"

    def warn = activeLightsWarning()
    if (warn) {
        sb << "<b>Notes:</b> ${warn}<br>"
    }

    if (!armed) {
        def debugLine = whyNotRunning()
        if (debugLine) {
            sb << "<b>Why not running:</b> ${debugLine}"
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

        // --- OPTIONS (combined with Tools & Testing) ---
        section("") {
            paragraph getFormat("header-blue", "Options")
            paragraph "By turning on this Test Cycle toggle and selecting 'Done' to save the setting, a single cycle will be kicked off for testing. Please not that anchor lights will be turned on and not back off since this only simulates the first cycle and summary."
            input "runTestNow", "bool",
                title: "Run a test cycle now?",
                defaultValue: false,
                submitOnChange: false,
                description: "Optional. Set to ON and click Done to trigger one test cycle."

            label title:"Assign a name for this app (Useful if you have multiple instances):", required:false
        }
    }
}

// Show "Setup" page
def Setup() {

    def newModeInput = [
        name:         "newMode",
        type:         "mode",
        title:        "Modes (e.g., Away/Vacation)",
        multiple:     true,
        required:     false,
        description:  "Optional. If left blank, modes are ignored."
    ]
    def vacationSwitchInput = [
        name:         "vacationSwitch",
        type:         "capability.switch",
        title:        "Vacation switch (optional – ON enables app)",
        required:     false,
        multiple:     false,
        description:  "Optional. If set, this switch must be ON for the app to run."
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

        // --- Daily summary ---
        section("") {
            paragraph getFormat("header-blue", "Daily summary notifications")
            paragraph "Get a once-per-day summary of how many cycles and light changes ran while the app was armed."
            input "summaryDevice", "capability.notification",
                title: "Notification devices for daily summary",
                required: false,
                multiple: true,
                description: "Optional. Select one or more devices to receive a daily summary."
            input "summaryTime", "time",
                title: "Time each day to send summary",
                required: false,
                description: "Optional. If not set, no daily summary will be sent."
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
            input "logEnable", "bool",
                title: "Enable debug logging?",
                defaultValue: true,
                required: false,
                description: "Optional. Turn off to reduce log noise once things are stable."
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
    // Snapshot test flag before re-init
    def doTest = runTestNow

    unsubscribe()
    clearState(true)
    initialize()

    // Handle test AFTER normal initialization
    if (doTest) {
        runTestCycle()
        // reset the toggle so it doesn't re-run next time
        app.updateSetting("runTestNow", [value: "false", type: "bool"])
    }
}

/**
 * initialize():
 * - Always subscribes to mode/vacationSwitch.
 * - Only schedules start/end + initCheck + summary if armOk is true.
 */
def initialize() {
    if (newMode != null) {
        subscribe(location, "mode", modeChangeHandler)
    }
    if (vacationSwitch) {
        subscribe(vacationSwitch, "switch", vacationSwitchHandler)
    }

    if (armOk) {
        // Only set up time window + scheduling if we are currently armed
        schedStartEnd()
        logDebug "Initialized while armed; scheduling checks. Settings: ${settings}"
        setSched()
        scheduleSummary()
    } else {
        state.schedRunning = false
        state.startendRunning = false
        logDebug "Initialized while NOT armed (mode='${location.mode}', vacationSwitch='${vacationSwitch ? vacationSwitch.currentSwitch : "n/a"}')."
    }
}

/**
 * Clear state and optionally turn off managed lights.
 * turnOff = true when we want a hard stop (wrong arm state, mode, etc.).
 */
def clearState(turnOff = false) {
    if (turnOff && (state?.Running ?: false)) {

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
    state.startendRunning = false
    state.lastUpdDt = null
    state.nextCycleAtMs = null

    // cancels all schedules, including summary; will be recreated when armOk becomes true again
    unschedule()
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

    if (starting != null || startTimeType != null) {
        def start = timeWindowStart(true)
        if (start) {
            runOnce(start, startTimeCheck)
            state.startendRunning = true
        }
    }
    if (ending != null || endTimeType != null) {
        def end = timeWindowStop(true)
        if (end) {
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
        clearState(true)
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
        logDebug "Vacation switch changed to ${evt.value}, armOk=false - clearing and unscheduling."
        clearState(true)
    } else {
        logDebug "Vacation switch changed to ${evt.value}, armOk=true - scheduling vacation lighting."
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
 */
def startTimeCheck() {
    logTrace "startTimeCheck"
    if (armOk) {
        setSched()
    } else {
        logDebug "startTimeCheck(): armOk=false, not scheduling."
    }
}

/**
 * endTimeCheck():
 * - If still armed, lets scheduleCheck handle time window.
 * - If not armed, ensures everything is cleared.
 */
def endTimeCheck() {
    logTrace "endTimeCheck"
    if (armOk) {
        // Let scheduleCheck handle shutting things down due to time window
        scheduleCheck(null)
    } else {
        logDebug "endTimeCheck(): armOk=false, ensuring everything is cleared."
        clearState(true)
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
    armOk && daysOk && timeOk
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

    } else if (!armOk || !daysOk) {
        if ((state?.Running ?: false) || (state?.schedRunning ?: false)) {
            logDebug "scheduleCheck(): wrong arm state or day - stopping Vacation Lights"
            clearState(true)
        }

    } else if (armOk && daysOk && !timeOk) {
        if ((state?.Running ?: false) || (state?.schedRunning ?: false)) {
            logDebug "scheduleCheck(): wrong time window - stopping Vacation Lights"
            clearState(true)
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
    logDebug "[TEST] Test cycle requested"

    if (!switches || !switches.size()) {
        logDebug "[TEST] No switches configured, aborting test."
        return
    }

    logDebug "[TEST] Running one test cycle regardless of mode/time/day. " +
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

private getTimeOk() {
    def result = true
    def start = timeWindowStart()
    def stop = timeWindowStop(false, true)
    if (start && stop && getTimeZone()) {
        result = timeOfDayIsBetween(start, stop, new Date(), getTimeZone())
    }
    // Help debug any timing problems
    logDebug "getTimeOk(): start=${start}, stop=${stop}, now=${new Date()}, tz=${getTimeZone()}"
    
    result
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

    if (!summaryTime) {
        logDebug "No summaryTime configured, not scheduling daily summary"
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
    runOnce(next, dailySummary)
}

def dailySummary() {
    Integer cycles    = (state.cycles   ?: 0) as Integer
    Integer lightsOn  = (state.lightsOn ?: 0) as Integer
    Integer lightsOff = (state.lightsOff?: 0) as Integer

    List onNames  = (state.summaryOnNames  ?: []) as List
    List offNames = (state.summaryOffNames ?: []) as List

    String msg = buildSummaryMessage("daily", cycles, lightsOn, lightsOff, onNames, offNames)

    if (summaryDevice && armOk) {
        logDebug "Sending daily summary: ${msg}"
        summaryDevice*.deviceNotification(msg)
    } else {
        logDebug "Daily summary (not sent): ${msg} (no summaryDevice or not armed)"
    }

    // reset counters and name lists for the next day
    state.cycles         = 0
    state.lightsOn       = 0
    state.lightsOff      = 0
    state.summaryOnNames = []
    state.summaryOffNames= []

    // schedule the next summary, but only if we are still armed
    if (armOk) {
        scheduleSummary()
    } else {
        logDebug "dailySummary(): not rescheduling because armOk=false"
    }
}

/**
 * Build a unified summary message for both daily and test summaries.
 *
 * @param mode       "daily" or "test"
 * @param cycles     Number of cycles in this summary window
 * @param lightsOn   Number of lights turned on
 * @param lightsOff  Number of lights turned off
 * @param onNames    List of device names turned on during this window
 * @param offNames   List of device names turned off during this window
 */
private String buildSummaryMessage(String mode, Integer cycles, Integer lightsOn, Integer lightsOff, List onNames = null, List offNames = null) {

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
    if (days || falseAlarmThreshold || summaryDevice || summaryTime || logEnable != null) {
        result = "complete"
    }
    result
}
