/**
 *  **************** Auto Lock Door CHILD ****************
 *
 *  Design Usage:
 *  Automatically locks a specific door after X minutes when closed and unlocks it when open after X seconds. Requires a contact sensor to prevent auto locking when the door is open.
 *
 *  Copyright 2019-2020 Chris Sader (@chris.sader)
 *
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Donations to support development efforts are accepted via:
 *
 *  Paypal at: https://paypal.me/csader
 *
 *-------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  If modifying this project, please keep the above header intact and add your comments/credits below - Thank you! -  @chris.sader
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Changes:
 *
 *  1.0.4 Parent / Child mode by heidrickla (@lewis.heidrick)
 *  1.0.3 Fixed debug toggle (thanks @lewis.heidrick)
 *  1.0.2 Added toggle for debug and seconds
 *  1.0.1 Fixed null bug, removed "optional" from contact sensor (thanks @SoundersDude and @chipworkz)
 *  1.0.0 Initial Release
 *
 */
 def setVersion(){
    state.name = "Auto Lock Door Advanced"
	state.version = "1.0.4"
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
    page(name: "page1")
}

def page1() {
  section() {
  }
    state.isDebug = isDebug
    dynamicPage(name: "page1", install: true, uninstall: true) {
    section("What do you want to name the new app") {
       label title: "Enter a name for this child app", required:false, submitOnChange:true
    }
    section("When a door unlocks...") {
        input "lock1", "capability.lock"
    }
    section("Lock it how many minutes/seconds later?") {
        input "duration", "number", title: "Enter # minutes/seconds"
    }
    section() {
        input (
		type:               "bool",
		name:               "minSec",
		title:              "Default is minutes. Use seconds instead?",
		required:           true,
		defaultValue:       false
	       )
        }

    section("Lock it only when this door is closed.") {
    input "openSensor", "capability.contactSensor", title: "Choose Door Contact Sensor"
    }
    section() {
    }
    section("") {
       input "isDebug", "bool", title: "Enable Debug Logging", required: false, multiple: false, defaultValue: false, submitOnChange: true
    }
    } 
}

def installed()
{
    ifDebug("Auto Lock Door installed.")
    initialize()
}

def updated()
{
    unsubscribe()
    unschedule()
    ifDebug("Auto Lock Door updated.")
    initialize()
}

def initialize()
{
    ifDebug("Settings: ${settings}")
    subscribe(lock1, "lock", doorHandler)
    subscribe(openSensor, "contact.closed", doorClosed)
    subscribe(openSensor, "contact.open", doorOpen)
}
    
def lockDoor()
{
    ifDebug("Locking Door if Closed")
    if((openSensor.latestValue("contact") == "closed")){
    	ifDebug("Door Closed")
    	lock1.lock()
    } else {
    	if ((openSensor.latestValue("contact") == "open")) {
	if (minSec) {
	def delay = duration
        ifDebug("Door open will try again in $duration second(s)")
        runIn( delay, lockDoor )
	} else {
	    def delay = duration * 60
	    ifDebug("Door open will try again in $duration minute(s)")
            runIn( delay, lockDoor )
	}
    }
}
}

def doorOpen(evt) {
    ifDebug("Door open reset previous lock task...")
    unschedule( lockDoor )
    if (minSec) {
	def delay = duration
        runIn( delay, lockDoor )
	} else {
	    def delay = duration * 60
            runIn( delay, lockDoor )
	}
}

def doorClosed(evt) {
    ifDebug("Door Closed")
}

def doorHandler(evt)
{
    ifDebug("Door ${openSensor.latestValue("contact")}")
    ifDebug("Lock ${evt.name} is ${evt.value}.")

    if (evt.value == "locked") {                  // If the human locks the door then...
        ifDebug("Cancelling previous lock task...")
        unschedule( lockDoor )                  // ...we don't need to lock it later.
    }
    else {                                      // If the door is unlocked then...
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

private ifDebug(msg) {  
    if (msg && state.isDebug)  log.debug 'Auto Lock: ' + msg  
}
