/**
 *  **************** Auto Lock Door PARENT ****************
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


definition(
    name: "Auto Lock",
    namespace: "chris.sader",
    singleInstance: true,
    author: "Chris Sader",
    description: "Auto Lock - Parent Manager",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
)

preferences {
	page(name: "mainPage")
}

def mainPage() {
	return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if(!state.AldInstalled) {
            section("Hit Done to install Auto Lock App") {
        	}
        }
        else {
        	section("Create a new Auto Lock Instance.") {
            	app(name: "childApps", appName: "Auto Lock Child", namespace: "chris.sader", title: "New Auto Lock Instance", multiple: true)
        	}
    	}
    }
}

def installed() {
    state.AldInstalled = true
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
}
