/**
 *  Pi-hole Virtual Switch Device Driver
 *
 *  Copyright 2018 Nick Veenstra
 *  Convert to Hubitat by cuboy29
 * Revision History
 *  v 2020.08 - Hubitat Community Forum Release with contributions from harriscd & Jed Brown
 *  v 2023.01.10 - Updated to fix 'polling' per the API status changes in Pi-Hole.
 *  v 2023.01.12 - Added toggle for debuging & authkey is optional
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
    definition (name: "Pi-hole Virtual Switch", namespace: "Logicalnonsense", author: "Jed Brown") {
        capability "Switch"
        capability "Refresh"
        
        command "poll"
        command "refresh"

        attribute "lastupdate", "string"
    }
   
    preferences {
        section ("Settings") {
            input name: "deviceIP", type:"text", title:"Pi-home IP address", required: true
            input name: "apiToken", type: "text", title: "API token", required: false
            input "disableTime", "number", title: "Disable time in minutes.<br>(1..1440; Blank = indefinitely)", required: false, range: "1..1440"
            input "isDebug", "bool", title: "Enable Debug Logging", required: false, multiple: false, defaultValue: false, submitOnChange: true

         }
    }

}

private getPort() {
    return 80
}

private getApiPath() { 
    "/admin/api.php" 
}

def installed() {
    if (isDebug)  { log.debug "Pi-Hole vSwitch: Installed with settings: ${settings}" }
}

def uninstalled() {
    if (isDebug)  { log.debug "Pi-Hole vSwitch: uninstalled()" }
}

def updated() {
    if (isDebug)  { log.debug "Pi-Hole vSwitch: Updated with settings: ${settings}" }

    initialize()
}

def initialize() {  
    state.combinedState = ""
    // Do the initial poll
    poll()
    // Schedule it to run every 15 minutes
    //runEvery3Hours("poll")
    runEvery15Minutes("poll")
    //runEvery5Minutes("poll") 
    //options runEvery__Minutes = 1, 5, 10, 15, 30
    //options runEvery1Hour, runEvery3Hours

}
def refresh() {
    poll()
}

def poll() {

    if (deviceIP == null) {
        if (isDebug)  { log.debug "IP address missing in preferences" }
        return
    }
    def hosthex = convertIPtoHex(deviceIP).toUpperCase()
    def porthex = convertPortToHex(getPort()).toUpperCase()
    def path = getApiPath() + "?summaryRaw"
    if (apiToken != null) {
        path = path + "&auth=" + apiToken
    }
    if (isDebug)  { log.debug "Pi-Hole vSwitch: API Path: " + path }

    device.deviceNetworkId = "$hosthex:$porthex" 
    def hostAddress = "$deviceIP:$port"
    def headers = [:] 
    headers.put("HOST", hostAddress)

    def hubAction = new hubitat.device.HubAction(
        method: "GET",
        path: path,
        headers: headers,
        null,
        [callback : parse] 
    )
    sendHubCommand(hubAction)
}

def parse(response) {
    
    if (isDebug)  { log.debug "Pi-Hole vSwitch: Parsing '${response}'" }

    def json = response.json
    if (isDebug)  { log.debug "Pi-Hole vSwitch: Received '${json}'" }

    if (json.FTLnotrunning) {
        return
    }
    if (json.status == "enabled") {
        sendEvent(name: "switch", value: "on")
    }
    if (json.status == "disabled") {
        sendEvent(name: "switch", value: "off")
    }
    if (json.dns_queries_today){
        if (json.dns_queries_today.toInteger() >= 0) {
            def combinedValue = "Queries today: " +json.dns_queries_today + " Blocked: " + json.ads_blocked_today + "\nClients: " + json.unique_clients
            state.combinedState = combinedValue
            sendEvent(name: "combined", value: combinedValue, unit: "")
        }
    }
    
    sendEvent(name: 'lastupdate', value: lastUpdated(now()), unit: "")
   
}

def on() {
    doSwitch("enable")
}


def off() {
    // Empty will remain off. Any value will be a countdown in minutes to turn back on
    doSwitch((disableTime) ? "disable=" + (disableTime * 60) : "disable")
    //doSwitch("disable")
}

def doSwitch(toggle) {
    
    if (deviceIP == null) {
        if (isDebug)  { log.debug "Pi-Hole vSwitch: IP address missing in preferences" }
        return
    }
    def hosthex = convertIPtoHex(deviceIP).toUpperCase()
    def porthex = convertPortToHex(getPort()).toUpperCase()
    //def path = getApiPath() + "?" + toggle + "&auth=" + apiToken
    def path = getApiPath() + "?" + toggle
    if (apiToken != null) {
        path = path + "&auth=" + apiToken
    }
    if (isDebug)  { log.debug "Pi-Hole vSwitch: API Path: " + path }

    device.deviceNetworkId = "$hosthex:$porthex" 
    def hostAddress = "$deviceIP:$port"
    def headers = [:] 
    headers.put("HOST", hostAddress)

    def hubAction = new hubitat.device.HubAction(
        method: "GET",
        path: path,
        headers: headers,
        null,
        [callback : parse] 
    )
    sendHubCommand(hubAction)
}


def lastUpdated(time) {
    def timeNow = now()
    def lastUpdate = ""
    if(location.timeZone == null) {
        if (isDebug)  { log.debug "Pi-Hole vSwitch: Cannot set update time : location not defined in app" }
    }
    else {
        lastUpdate = new Date(timeNow).format("MMM dd yyyy HH:mm", location.timeZone)
    }
    return lastUpdate
}
private String convertIPtoHex(ipAddress) { 
    String hex = ipAddress.tokenize( '.' ).collect {  String.format( '%02x', it.toInteger() ) }.join()
    return hex

}

private String convertPortToHex(port) {
    String hexport = port.toString().format( '%04x', port.toInteger() )
    return hexport
}
