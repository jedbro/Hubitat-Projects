/**
 *  Pi-hole Virtual Switch Device Driver (Updated for Pi-hole v6 API)
 *
 *  Originally created by:
 *    - Nick Veenstra (2018)
 *    - Converted to Hubitat by cuboy29
 *    - Community contributions from harriscd & Jed Brown
 *
 *  Revision History:
 *    - 2020.08: Hubitat Community Forum Release
 *    - 2023.01.10: Updated to fix polling per API changes in Pi-hole
 *    - 2023.01.12: Added debugging toggle, optional API token, simplified code
 *    - 2023.01.13: Fixed issue for Pi-holes without passwords
 *    - 2025.02.20: Updated for Pi-hole v6 API changes, added HPM support (by WalksOnAir)
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


metadata {
    definition (name: "Pi-hole Virtual Switch v6", namespace: "WalksOnAir", author: "WalksOnAir") {
        capability "Switch"
        capability "Refresh"
        
        command "poll"
        command "refresh"

        attribute "lastupdate", "string"
        attribute "sessionValid", "string"
    }
   
    preferences {
        section ("Settings") {
            input name: "deviceIP", type: "text", title: "Pi-hole IP address", required: true
            input name: "piPassword", type: "password", title: "Pi-hole Password (required):", required: true
            input name: "disableTime", type: "number", title: "Disable time in minutes (1..1440; Blank = indefinitely):", required: false, range: "1..1440"
            input name: "isDebug", type: "bool", title: "Enable Debug Logging", required: false, defaultValue: false
        }
    }
}

def installed() {
    logDebug("Installed with settings: ${redactSettings(settings)}")
    initialize()
}

def updated() {
    logDebug("Updated with settings: ${redactSettings(settings)}")
    initialize()
}

def initialize() {
    state.sid = null
    sendEvent(name: "sessionValid", value: "unknown")
    authenticate()
    runEvery15Minutes("poll")
}

def refresh() {
    poll()
}

def poll() {
    if (!state.sid) {
        log.warn "No valid session ID. Attempting to re-authenticate."
        authenticate()
    }
    sendRequest("GET", "/dns/blocking", null, "handleStatusResponse")
}

def on() {
    ensureSessionValid()
    def payload = [ "blocking": true ]
    sendRequest("POST", "/dns/blocking", payload, "handleOnOffResponse")
}

def off() {
    ensureSessionValid()
    def disableTimeInSeconds = (disableTime && disableTime > 0) ? disableTime * 60 : 0
    def payload = disableTimeInSeconds > 0 ? [ "blocking": false, "timer": disableTimeInSeconds ] : [ "blocking": false ]
    sendRequest("POST", "/dns/blocking", payload, "handleOnOffResponse")
}

def authenticate() {
    if (!settings.piPassword) {
        log.error "Pi-hole password is not set. Cannot authenticate."
        return
    }

    state.sid = null
    sendEvent(name: "sessionValid", value: "authenticating")

    def cleanedPassword = settings.piPassword.trim()
    
    log.warn("DEBUG: Hubitat sending password: '${cleanedPassword}'")

    def payload = [ "password": cleanedPassword ]
    sendRequest("POST", "/auth", payload, "handleAuthResponse", true)
}

def handleAuthResponse(hubitat.device.HubResponse response) {
    logDebug("Authentication response received")
    if (response.status == 200) {
        def json = response.getJson()
        if (json?.session?.valid == true && json.session?.sid) {
            state.sid = json.session.sid
            sendEvent(name: "sessionValid", value: "true") 
            log.info "Authenticated successfully. Session ID obtained."
        } else {
            log.warn "Authentication failed. Retrying in 3 seconds..."
            state.sid = null
            sendEvent(name: "sessionValid", value: "false")
            runIn(3, authenticate)
        }
    } else {
        log.error "Authentication failed with status ${response.status}: ${response.body}"
        state.sid = null
        sendEvent(name: "sessionValid", value: "false")
    }
}

def ensureSessionValid() {
    if (!state.sid) {
        log.warn "No valid session. Re-authenticating..."
        authenticate()
    }
}

def handleStatusResponse(hubitat.device.HubResponse response) {
    logDebug("Status response received")
    if (response.status == 200) {
        def json = response.getJson()
        if (json?.blocking != null) {
            def switchState = (json.blocking == "enabled") ? "on" : "off"
            sendEvent(name: "switch", value: switchState)
            log.info "Pi-hole status updated in Hubitat: ${switchState}"
        } else {
            log.error "Failed to retrieve Pi-hole status."
        }
    } else {
        log.error "Status request failed with status ${response.status}: ${response.body}"
    }
}

def handleOnOffResponse(hubitat.device.HubResponse response) {
    logDebug("On/Off response received")
    if (response.status == 200) {
        log.info "Pi-hole successfully updated."
        poll()
    } else {
        log.error "Failed to update Pi-hole with status ${response.status}: ${response.body}"
    }
}

private sendRequest(String method, String endpoint, Map payload, String callbackMethod, boolean isAuth = false) {
    if (!deviceIP) {
        log.error "Device IP is not set. Check driver settings."
        return
    }

    def headers = [
        "Content-Type": "application/json",
        "HOST": "${deviceIP}:${getPort()}"
    ]

    if (!isAuth && state.sid) {
        headers["X-FTL-SID"] = state.sid
    }

    def safePayload = payload ? new groovy.json.JsonBuilder(payload).toString() : null

    logDebug("Sending request - Method: ${method}, Path: /api${endpoint}")
    logDebug("Headers: ${headers}")
    logDebug("Payload: ${safePayload}")

    def requestParams = [
        method: method,
        path: "/api${endpoint}",
        headers: headers,
        body: safePayload
    ]

    try {
        def hubAction = new hubitat.device.HubAction(requestParams, null, [callback: callbackMethod])
        sendHubCommand(hubAction)
    } catch (Exception e) {
        log.error "Error sending request: ${e.message}"
    }
}

def redactSettings(settingsMap) {
    def safeSettings = settingsMap.clone()
    if (safeSettings.piPassword) safeSettings.piPassword = "[REDACTED]"
    return safeSettings
}

def logDebug(msg) {
    if (isDebug) {
        log.debug "Pi-Hole v6 Switch DEBUG:: ${msg}"
    }
}

private getPort() {
    return 80
}

def makeCall() {
    log.error "makeCall() was invoked but is not implemented."
}