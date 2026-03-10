import groovy.transform.Field

/**
 * Samsung Frame TV Driver
 * Talks to the TheFrame sidecar service running on a local server (e.g. Raspberry Pi).
 *
 * Version: 1.2.0
 *
 * Changelog:
 *   1.2.0 - Free-form poll interval (1–999 min), add currentApp attribute,
 *            remove unsupported art content commands (list/select/upload/slideshow)
 *   1.1.0 - Add paired attribute + pair() command, currentInput from state,
 *            artMode defaults to 'unknown' when unpaired
 *   1.0.0 - Initial release: power, inputs, art mode, art selection, slideshow
 *
 * Capabilities:
 *   - Switch (on/off)
 *   - Art Mode on/off
 *   - Input switching (Apple TV, HDMI, etc.)
 *   - isWatching detection (safe for night-time automations)
 *   - Paired status + one-click pairing
 *
 * GitHub: https://github.com/jedbro/Hubitat-Projects/tree/main/TheFrame
 */
metadata {
    definition(
        name: "Samsung Frame TV",
        namespace: "Logicalnonsense",
        author: "Jed Brown",
        description: "Controls a Samsung Frame TV via the TheFrame sidecar API"
    ) {
        capability "Switch"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "driverVersion", "string"
        attribute "paired",        "enum", ["true", "false"]
        attribute "artMode",       "enum", ["on", "off", "unknown"]
        attribute "isWatching",    "enum", ["true", "false"]
        attribute "currentInput",  "string"
        attribute "currentApp",    "string"
        attribute "lastUpdated",   "string"

        command "pair"
        command "artModeOn"
        command "artModeOff"
        command "setInput", [[name: "Input Name*", type: "STRING",
                              description: "Configured input name (e.g. appletv, hdmi2)"]]
    }

    preferences {
        input name: "sidecarHost", type: "text",   title: "Sidecar Host/IP",
              description: "IP or hostname of the Raspberry Pi", required: true
        input name: "sidecarPort", type: "number", title: "Sidecar Port",
              defaultValue: 8088, required: true
        input name: "pollInterval", type: "number",
              title: "Poll Interval (minutes, 1–999; leave blank to disable)",
              defaultValue: 5, range: "1..999", required: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

@Field static final String DRIVER_VERSION = "1.2.0"

def installed() {
    log.info "Samsung Frame TV driver installed (v${DRIVER_VERSION})"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    initialize()
}

def updated() {
    log.info "Samsung Frame TV driver updated to v${DRIVER_VERSION}"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    initialize()
}

def initialize() {
    unschedule()
    refresh()
    scheduleNextPoll()
}

private void scheduleNextPoll() {
    def interval = (pollInterval as Integer) ?: 0
    if (interval > 0) {
        runIn(interval * 60, "scheduledRefresh")
    }
}

def scheduledRefresh() {
    refresh()
    scheduleNextPoll()
}

// ---------------------------------------------------------------------------
// Switch capability
// ---------------------------------------------------------------------------

def on() {
    logDebug "on()"
    apiPost("/api/tv/power/on")
    // Optimistically update — state will correct on next poll
    sendEvent(name: "switch", value: "on")
}

def off() {
    logDebug "off()"
    apiPost("/api/tv/power/off")
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "artMode",    value: "off")
    sendEvent(name: "isWatching", value: "false")
}

// ---------------------------------------------------------------------------
// Refresh / polling
// ---------------------------------------------------------------------------

def refresh() {
    logDebug "refresh()"
    def resp = apiGet("/api/tv/state")
    if (resp == null) {
        log.warn "Could not reach sidecar"
        return
    }

    def power         = resp.power ?: "off"
    def paired        = resp.paired ? "true" : "false"
    def artMode       = resp.artMode ?: "unknown"
    def isWatching    = resp.isWatching ? "true" : "false"
    def currentSource = resp.currentSource ?: ""

    sendEvent(name: "switch",      value: power == "on" ? "on" : "off")
    sendEvent(name: "paired",      value: paired)
    sendEvent(name: "artMode",     value: artMode)
    sendEvent(name: "isWatching",  value: isWatching)
    sendEvent(name: "lastUpdated", value: new Date().toString())
    if (currentSource) {
        sendEvent(name: "currentInput", value: currentSource)
    }
    if (resp.currentApp != null) {
        sendEvent(name: "currentApp", value: resp.currentApp ?: "")
    }

    if (paired == "false") {
        log.warn "Samsung Frame TV: not paired — run 'curl -X POST http://${sidecarHost}:${sidecarPort}/api/tv/pair' on the Pi to pair"
    }

    logDebug "State: power=${power}, paired=${paired}, artMode=${artMode}, isWatching=${isWatching}, source=${currentSource}, app=${resp.currentApp}"
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

def pair() {
    logDebug "pair()"
    def resp = apiPost("/api/tv/pair")
    if (resp?.paired) {
        log.info "Samsung Frame TV: paired successfully"
        sendEvent(name: "paired", value: "true")
    } else {
        log.warn "Samsung Frame TV: pairing pending — accept the prompt on the TV, then click Pair again"
        sendEvent(name: "paired", value: "false")
    }
}

// ---------------------------------------------------------------------------
// Art Mode
// ---------------------------------------------------------------------------

def artModeOn() {
    logDebug "artModeOn()"
    apiPost("/api/tv/artmode/on")
    sendEvent(name: "artMode",    value: "on")
    sendEvent(name: "isWatching", value: "false")
}

def artModeOff() {
    logDebug "artModeOff()"
    apiPost("/api/tv/artmode/off")
    sendEvent(name: "artMode",    value: "off")
    sendEvent(name: "isWatching", value: "true")
}

// ---------------------------------------------------------------------------
// Input switching
// ---------------------------------------------------------------------------

def setInput(String inputName) {
    logDebug "setInput(${inputName})"
    apiPost("/api/tv/input/${inputName}")
    sendEvent(name: "currentInput", value: inputName)
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

private String baseUrl() {
    return "http://${sidecarHost}:${sidecarPort}"
}

private def apiGet(String path) {
    try {
        def params = [uri: "${baseUrl()}${path}", timeout: 10]
        def result = null
        httpGet(params) { resp ->
            if (resp.status == 200) result = resp.data
        }
        return result
    } catch (Exception e) {
        log.error "GET ${path} failed: ${e.message}"
        return null
    }
}

private def apiPost(String path, Map body = [:]) {
    try {
        def params = [
            uri:         "${baseUrl()}${path}",
            contentType: "application/json",
            body:        groovy.json.JsonOutput.toJson(body),
            timeout:     10
        ]
        def result = null
        httpPost(params) { resp ->
            if (resp.status == 200) result = resp.data
        }
        return result
    } catch (Exception e) {
        log.error "POST ${path} failed: ${e.message}"
        return null
    }
}

private void logDebug(String msg) {
    if (logEnable) log.debug msg
}
