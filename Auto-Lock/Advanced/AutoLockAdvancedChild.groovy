def setVersion() {
    state.name = "Auto Lock"
	state.version = "1.0.5"
}
    
definition(
    name: "Auto Lock Child",
    namespace: "chris.sader",
    author: "Chris Sader",
    description: "Automatically locks a specific door after X minutes/seconds when closed and unlocks it when open after X seconds.",
    category: "Convenience",
    parent: "chris.sader:Auto Lock",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")

preferences {
    page(name: "mainPage")
    page(name: "timeIntervalInput", title: "Only during a certain time") {
		section {
			input "starting", "time", title: "Starting", required: false
			input "ending", "time", title: "Ending", required: false
        }
    }
}
    
def mainPage() {
    ifTrace("mainPage")
    if (!isDebug) {
        app.updateSetting("isDebug", false)
    }
    if (isTrace == true) {
        runIn(1800, traceOff)
    }
    if (isDebug == true) {
        runIn(1800, debugOff)
    }
    if (isTrace == true) {
        runIn(1800, traceOff)
    }
    
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        ifDebug("mainPage: [state.status = ${state?.status}] [state.paused = ${state?.paused}] [state.disabled = ${state.disabled}]")
        if (state?.disabled == "Disabled") {
            state.pauseButtonName = "Enable"
            unsubscribe()
            unschedule()
            subscribe(disabledSwitch, "switch", disabledHandler)
        } else if (state.paused == true) {
            state.pauseButtonName = "Resume"
            unsubscribe()
            unschedule()
            subscribe(disabledSwitch, "switch", disabledHandler)
        } else {
            state.pauseButtonName = "Pause"
            initialize()
        }
    section("") {
      input name: "Pause", type: "button", title: state.pauseButtonName, submitOnChange:true
    }  
    section("") {
    String defaultName = "Enter a name for this child app"
        if (state.displayName) {
            defaultName = state.displayName
            app.updateLabel(defaultName)
        }
    label title: "Enter a name for this child app", required:false, defaultValue: defaultName, submitOnChange:true   
    }
    section("When a door unlocks...") {
    input "lock1", "capability.lock", title: "Lock Location:", required: true
    }
    section() {
        input "duration", "number", title: "Lock it how many minutes/seconds later?"
    }
    section() {
        input type: "bool", name: "minSec", title: "Default is minutes. Use seconds instead?", required: true, defaultValue: false
    }
    section("Lock it only when this door is closed.") {
    input "openSensor", "capability.contactSensor", title: "Choose Door Contact Sensor"
    }
    section("Logging Options", hideable: true, hidden: hideLoggingSection()) {
            input "isInfo", "bool", title: "Enable Info logging for 30 minutes", submitOnChange: true, defaultValue: false
            input "isDebug", "bool", title: "Enable debug logging for 30 minutes", submitOnChange: true, defaultValue: false
		    input "isTrace", "bool", title: "Enable Trace logging for 30 minutes", submitOnChange: true, defaultValue: false
            input "ifLevel","enum", title: "IDE logging level",required: true, options: getLogLevels(), defaultValue : "1"
            paragraph "NOTE: IDE logging level overrides the temporary logging selections."
    }
    section(title: "Only Run When:", hideable: true, hidden: hideOptionsSection()) {
			def timeLabel = timeIntervalLabel()
			href "timeIntervalInput", title: "Only during a certain time", description: timeLabel ?: "Tap to set", state: timeLabel ? "complete" : null
			input "days", "enum", title: "Only on certain days of the week", multiple: true, required: false,
			options: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
			input "modes", "mode", title: "Only when mode is", multiple: true, required: false
            input "disableSwitch", "capability.switch", title: "Switch to Enable and Disable this app", submitOnChange:true, required:false, multiple:true
		}
    }
}

// Application settings and startup
def installed() {
    ifDebug("Auto Lock Door installed.")
    state.installed = true
    initialize()
}

def updated() {
    ifDebug("Auto Lock Door updated.")
    unsubscribe()
    unschedule()
    initialize()
    updateLabel()
}

def initialize() {
    ifTrace("initialize")
    ifDebug("Settings: ${settings}")
    ifDebug("Settings: ${settings}")
    subscribe(lock1, "lock", doorHandler)
    subscribe(openSensor, "contact.closed", doorHandler)
    subscribe(openSensor, "contact.open", doorHandler)
    subscribe(disabledSwitch, "switch", disabledHandler)
    subscribe(deviceActivationSwitch, "switch", deviceActivationSwitchHandler)
    checkPausedOrDisabled()
    updateLabel
}

// Device Handlers


def doorHandler(evt) {
    ifTrace("doorHandler")
    checkPausedOrDisabled()
    if (state.pausedOrDisabled == false) {
        if (evt.value == "contact.closed") {ifDebug("Door Closed")}
        if (evt.value == "contact.opened") {
            ifDebug("Door Open")
            checkPausedOrDisabled()
            if (state.pausedOrDisabled == false) {
                ifDebug("Door open reset previous lock task...")
                unschedule(lockDoor)
                if (minSec) {
	                def delay = duration
                    runIn(delay, lockDoor)
	            } else {
	                def delay = duration * 60
                    runIn(delay, lockDoor)
                }
            }
        }
        if (evt.value == "locked") {                  // If the human locks the door then...
            ifDebug("Cancelling previous lock task...")
            unschedule(lockDoor)                  // ...we don't need to lock it later.
            state.status = "(Locked)"
        } else {                                      // If the door is unlocked then...
            state.status = "(Unlocked)"
            if (minSec) {
	            def delay = duration
                ifDebug("Re-arming lock in in $duration second(s)")
                runIn( delay, lockDoor )
	        } else {
	            def delay = duration * 60
	            ifDebug("Re-arming lock in in $duration minute(s)")
                runIn( delay, lockDoor )
          }    
       }
    }
    updateLabel()
}

def disabledHandler(evt) {
    ifTrace("disabledHandler")
    if(disabledSwitch) {
        disabledSwitch.each { it ->
        disabledSwitchState = it.currentValue("switch")
            if (disabledSwitchState == "on") {
                ifTrace("disabledHandler: disabledSwitchState = ${disabledSwitchState}")
				state.disabled = ""
                if (state?.paused) {
                    state.status = "(Paused)"
                    updateLabel()
                } else {
                    state.status = "${lockStatus()}"
                    updateLabel()
                }
            } else if (disabledSwitchState == "off") {
                state.pauseButtonName = "Enable"
                state.status = "(Disabled)"
                state.disabled = "Disabled"
                updateLabel()
                ifTrace("disabledHandler: Disabled")
            }
        }
    } else {
        state.disabledSwitchState = false
        state.disabledSwitch = false
        state.disabled = false
        if(!state.paused) {
            state.status = "${lockStatus()}"
            updateLabel()
        }
    }
}

def deviceActivationSwitchHandler(evt) {
    ifTrace("deviceActivationSwitchHandler")
    ifTrace("DeviceActivationSwitchHandler: state.status = ${state.status} [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
    checkPausedOrDisabled()
    if (state.pausedOrDisabled == false) {
        if(deviceActivationSwitch) {
            deviceActivationSwitch.each { it ->
                deviceActivationSwitchState = it.currentValue("switch")
            }
                if (deviceActivationSwitchState == "on") {
                    ifDebug("deviceActivationSwitchHandler: Locking the door now")
                    lockDoor()
                } else if (deviceActivationSwitchState == "off") {
                    ifDebug("deviceActivationSwitchHandler: Unlocking the door now")
                    unlockDoorSwitch()
                    unschedule()
                    updateLabel()
                }
        }
    } else {
        ifDebug("deviceActivationSwitchHandler: Application is paused or disabled.")
    }
}

// Application Functions
def lockDoor() {
    ifDebug("Locking Door if Closed")
    if((openSensor?.latestValue("contact") == "closed") || (!openSensor)) {
        ifDebug("Is bypass sensor triggering? openSensor == ${!openSensor}")
    	ifDebug("Door Closed")
    	lock1.lock()
        state.status = "(Locked)"
        updateLabel()
    } else {
    	if ((openSensor?.latestValue("contact") == "open")) {
	        if (minSec) {
	            def delay = duration
                ifDebug("Door open will try again in $duration second(s)")
                runIn(delay, lockDoor)
	        } else {
	            def delay = duration * 60
	            ifDebug("Door open will try again in $duration minute(s)")
                runIn(delay, lockDoor)
	        }
        }
     }
}

def unlockDoor() {
    ifTrace("unlockDoor")
    ifDebug("Unlocking Door")
    checkPausedOrDisabled()
    if (state.pausedOrDisabled == false) {
        if (lock1.currentValue("lock") == "locked") {
            ifInfo("unlockDoor: Unlocking the door now")
            lock1.unlock()
            state.status = "(Unlocked)"
            updateLabel()
        }
    }
}

def lockStatus() {
    ifTrace("lockStatus")
    ifTrace("lockStatus: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
    ifTrace("lockStatus: [lock1.currentValue(lock) = ${lock1?.currentValue("lock")}]")
    if (lock1?.currentValue("lock") == "locked") {
        ifTrace("lockStatus - lock1.CurrentValue locked: [state.paused = ${state?.paused}] [state.disabled = ${state?.disabled}] [state.disabled = ${state?.disabled == ""}])")
        if ((state?.paused == false) && ((state?.disabled == false) || (state?.disabled == ""))) {
            ifTrace("lockStatus: [state.paused = ${state?.paused}] [state.disabled = ${state?.disabled}] [state.disabled = ${state?.disabled == ""}])")
            state.status = "(Locked)"
            ifDebug("lockStatus - locked: state.status = ${state.status}")
            if (state.status == "(Locked)") return "(Locked)"
        } else {
            log.info "${app.label} is Paused or Disabled"
            ifTrace("lockStatus - lock1.CurrentValue locked: [state.paused = ${state?.paused}] [state.disabled = ${state?.disabled}]")
        }
    }
    if (lock1?.currentValue("lock") == "unlocked") {
        ifTrace("lockStatus - lock1.CurrentValue unlocked: [state.paused = ${state?.paused}] [state.disabled = ${state?.disabled}] [state.disabled = ${state?.disabled == false}]")
        if ((state?.paused == false) && (state?.disabled == false)) {
            state.status = "(Unlocked)"
            ifDebug("lockStatus - Unlocked: state.status = ${state.status}")
            if (state.status == "(Unlocked)") return "(Unlocked)"
        } else {
            log.info "${app.label} is Paused or Disabled"
            ifTrace("lockStatus - lock1.CurrentValue unlocked: [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
        }
    }
}

def checkPausedOrDisabled() {
    ifTrace("checkPausedOrDisabled")
    if (state.disabledSwitchState == true) {
        if (disabledSwitchState == "Enabled") {
            state.disabled = false
            state.status = "${lockStatus()}"
        } else if (disabledSwitchState == "Disabled") {
            state.disabled = "Disabled"
            state.status = "(Disabled)"
        }
    } else if (!disabledSwitchState) {
        state.disabled = false
        state.status = "${lockStatus()}"
    } else {
    state.disabled = false
    state.status = "${lockStatus()}"
    }
    lockStatus()
    if (state?.disabled || state?.paused) { state.pausedOrDisabled = true } else { state.pausedOrDisabled = false }
    ifTrace("checkPausedOrDisabled: [state.paused = ${state.paused}] [state.disabled = ${state.disabled}] [${state.pausedOrDisabled}]")
}

def changeMode(mode) {
    ifTrace("changeMode")
    ifDebug("Changing Mode to: ${mode}")
	if (location.mode != mode && location.modes?.find { it.name == mode}) setLocationMode(mode)
}



//Label Updates
void updateLabel() {
    if (!app.label.contains("<span") && !app.label.contains("Paused") && !app.label.contains("Disabled") && state?.displayName != app.label) {
        state.displayName = app.label
    }
    if (state?.status || state?.paused || state?.enableSwitch || !state?.enableSwitch) {
        def status = state?.status
        String label = "${state.displayName} <span style=color:"
        if (state?.enableSwitch == true) {
            status = "(Disabled)"
            ifDebug("updateLabel: Status set to (Disabled)")
            label += "red"
        } else if (state?.paused) {
            status = "(Paused)"
            ifDebug("updateLabel: Status set to (Paused)")
            label += "red"
        } else if (state.status == "(Locked)") {
            status = "(Locked)"
            ifDebug("updateLabel: Status set to (Locked)")
            label += "green"
        } else if (state.status == "(Unlocked)") {
            status = "(Unlocked)"
            ifDebug("updateLabel: Status set to (Unlocked)")
            label += "orange"
        } else {
            status = ""
            label += "white"
        }
    label += ">${status}</span>"
    app.updateLabel(label)
    }
}

//Enable, Resume, Pause button
def appButtonHandler(btn) {
    ifTrace("appButtonHandler")
    ifTrace("appButtonHandler: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
    if (btn == "Enable") {
        ifTrace("appButtonHandler - Enable button before updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
        updateLabel()
        ifTrace("appButtonHandler - Enable button after updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
    } else if (btn == "Resume") {
        state.disabled = false
        state.paused = false
        state.status = ""
        ifTrace("appButtonHandler - Resume button before updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
        updateLabel()
        ifTrace("appButtonHandler - Resume after updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
    } else if (btn == "Pause") {
        state.disabled = false
        state.paused = !state.paused
        if (state.paused) {
            unschedule()
            unsubscribe()
            ifTrace("appButtonHandler - Pause button before updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
            updateLabel()
            ifTrace("appButtonHandler - Pause after updateLabel: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
        } else {
            initialize()
            ifTrace("appButtonHandler: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
            updateLabel()
            ifTrace("appButtonHandler: [state.status = ${state.status}] [state.paused = ${state.paused}] [state.disabled = ${state.disabled}]")
        }
    }
}

// Application Page settings
private hideLoggingSection() {
	(isInfo || isDebug || isTrace || ifLevel) ? true : true
}

private hideOptionsSection() {
	(starting || ending || days || modes || manualCount) ? false : true
}

private getAllOk() {
	modeOk && daysOk && timeOk
}

private getModeOk() {
	def result = !modes || modes.contains(location.mode)
    ifDebug("modeOk = ${result}")
	result
}

private getDaysOk() {
	def result = true
	if (days) {
		def df = new java.text.SimpleDateFormat("EEEE")
		if (location.timeZone) {
			df.setTimeZone(location.timeZone)
		}
		else {
			df.setTimeZone(TimeZone.getTimeZone("America/New_York"))
		}
		def day = df.format(new Date())
		result = days.contains(day)
	}
    ifDebug("daysOk = ${result}")
	result
}

private getTimeOk() {
	def result = true
	if (starting && ending) {
		def currTime = now()
		def start = timeToday(starting).time
		def stop = timeToday(ending).time
		result = start < stop ? currTime >= start && currTime <= stop : currTime <= stop || currTime >= start
	}
    ifDebug{"timeOk = ${result}"}
	result
}

private hhmm(time, fmt = "h:mm a") {
	def t = timeToday(time, location.timeZone)
	def f = new java.text.SimpleDateFormat(fmt)
	f.setTimeZone(location.timeZone ?: timeZone(time))
	f.format(t)
}

private timeIntervalLabel() {
	(starting && ending) ? hhmm(starting) + "-" + hhmm(ending, "h:mm a z") : ""
}

// Logging functions
def getLogLevels() {
    return [["0":"None"],["1":"Info"],["2":"Debug"],["3":"Trace"]]
}

def infoOff() {
    app.updateSetting("isInfo", false)
    log.info "${state.displayName}: Info logging auto disabled."
}

def debugOff() {
    app.updateSetting("isDebug", false)
    log.info "${state.displayName}: Debug logging auto disabled."
}

def traceOff() {
    app.updateSetting("isTrace", false)
    log.trace "${state.displayName}: Trace logging auto disabled."
}

def disableInfoIn30() {
    if (isInfo == true) {
        runIn(1800, infoOff)
        log.info "Info logging disabling in 30 minutes."
    }
}

def disableDebugIn30() {
    if (isDebug == true) {
        runIn(1800, debugOff)
        log.debug "Debug logging disabling in 30 minutes."
    }
}

def disableTraceIn30() {
    if (isTrace == true) {
        runIn(1800, traceOff)
        log.trace "Trace logging disabling in 30 minutes."
    }
}

def ifWarn(msg) {
    log.warn "${state.displayName}: ${msg}"
}

def ifInfo(msg) {       
    def logL = 0
    if (ifLevel) logL = ifLevel.toInteger()
    if (logL == 1 && isInfo == false) {return}//bail
    else if (logL > 0) {
		log.info "${state.displayName}: ${msg}"
	}
}

def ifDebug(msg) {
    def logL = 0
    if (ifLevel) logL = ifLevel.toInteger()
    if (logL < 2 && isDebug == false) {return}//bail
    else if (logL > 1) {
		log.debug "${state.displayName}: ${msg}"
    }
}

def ifTrace(msg) {       
    def logL = 0
    if (ifLevel) logL = ifLevel.toInteger()
    if (logL < 3 && isTrace == false) {return}//bail
    else if (logL > 2) {
		log.trace "${state.displayName}: ${msg}"
    }
}