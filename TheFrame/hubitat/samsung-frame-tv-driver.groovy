/**
 * Samsung Frame TV Driver
 * Talks to the TheFrame sidecar service running on a local server (e.g. Raspberry Pi).
 *
 * Capabilities:
 *   - Switch (on/off)
 *   - Art Mode on/off
 *   - Input switching (Apple TV, HDMI, etc.)
 *   - Art selection and slideshow control
 *   - isWatching detection (safe for night-time automations)
 *
 * GitHub: https://github.com/your-repo/Hubitat-Projects/TheFrame
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

        attribute "paired",       "enum", ["true", "false"]
        attribute "artMode",      "enum", ["on", "off", "unknown"]
        attribute "isWatching",   "enum", ["true", "false"]
        attribute "currentInput", "string"
        attribute "currentArtId", "string"
        attribute "artList",      "string"   // JSON array of available art IDs
        attribute "lastUpdated",  "string"

        command "pair"
        command "artModeOn"
        command "artModeOff"
        command "setInput", [[name: "Input Name*", type: "STRING",
                              description: "Configured input name (e.g. appletv, hdmi2)"]]
        command "selectArt", [[name: "Content ID*", type: "STRING",
                               description: "Art content ID from listArt"]]
        command "nextArt"
        command "listArt"
        command "slideshowOn",  [[name: "Interval (minutes)", type: "NUMBER",
                                  description: "Minutes between art changes (default 30)"]]
        command "slideshowOff"
        command "uploadArtUrl", [[name: "Image URL*", type: "STRING",
                                  description: "URL of image to upload to TV art collection"]]
    }

    preferences {
        input name: "sidecarHost", type: "text",   title: "Sidecar Host/IP",
              description: "IP or hostname of the Raspberry Pi", required: true
        input name: "sidecarPort", type: "number", title: "Sidecar Port",
              defaultValue: 8088, required: true
        input name: "pollInterval", type: "enum",  title: "Poll Interval",
              options: ["Disabled", "1 minute", "2 minutes", "5 minutes", "10 minutes"],
              defaultValue: "5 minutes"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    log.info "Samsung Frame TV driver installed"
    initialize()
}

def updated() {
    log.info "Samsung Frame TV driver updated"
    initialize()
}

def initialize() {
    unschedule()
    switch (pollInterval) {
        case "1 minute":  runEvery1Minute("refresh");  break
        case "2 minutes": schedule("0 */2 * * * ?", "refresh"); break
        case "5 minutes": runEvery5Minutes("refresh"); break
        case "10 minutes": runEvery10Minutes("refresh"); break
    }
    refresh()
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
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "artMode", value: "off")
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

    sendEvent(name: "switch",        value: power == "on" ? "on" : "off")
    sendEvent(name: "paired",        value: paired)
    sendEvent(name: "artMode",       value: artMode)
    sendEvent(name: "isWatching",    value: isWatching)
    sendEvent(name: "lastUpdated",   value: new Date().toString())
    if (currentSource) {
        sendEvent(name: "currentInput", value: currentSource)
    }

    if (paired == "false") {
        log.warn "Samsung Frame TV: not paired — run 'curl -X POST http://${sidecarHost}:${sidecarPort}/api/tv/pair' on the Pi to pair"
    }

    logDebug "State: power=${power}, paired=${paired}, artMode=${artMode}, isWatching=${isWatching}, source=${currentSource}"
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
// Art management
// ---------------------------------------------------------------------------

def listArt() {
    logDebug "listArt()"
    def resp = apiGet("/api/art/list")
    if (resp?.items) {
        def ids = resp.items.collect { it.content_id ?: it }.join(", ")
        sendEvent(name: "artList", value: groovy.json.JsonOutput.toJson(resp.items))
        log.info "Available art IDs: ${ids}"
    }
}

def selectArt(String contentId) {
    logDebug "selectArt(${contentId})"
    apiPost("/api/art/select", [contentId: contentId])
    sendEvent(name: "currentArtId", value: contentId)
}

def nextArt() {
    logDebug "nextArt()"
    // Get list, find current, advance to next
    def listResp    = apiGet("/api/art/list")
    def currentResp = apiGet("/api/art/current")
    if (!listResp?.items || !currentResp) return

    def items     = listResp.items
    def currentId = currentResp.content_id
    def idx       = items.findIndexOf { (it.content_id ?: it) == currentId }
    def nextIdx   = (idx + 1) % items.size()
    def nextId    = items[nextIdx].content_id ?: items[nextIdx]

    selectArt(nextId as String)
}

def slideshowOn(Number intervalMinutes = 30) {
    logDebug "slideshowOn(${intervalMinutes}m)"
    apiPost("/api/art/slideshow", [enabled: true, intervalSeconds: (intervalMinutes * 60).toInteger()])
}

def slideshowOff() {
    logDebug "slideshowOff()"
    apiPost("/api/art/slideshow", [enabled: false])
}

def uploadArtUrl(String url) {
    logDebug "uploadArtUrl(${url})"
    apiPost("/api/art/upload/url", [url: url])
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
