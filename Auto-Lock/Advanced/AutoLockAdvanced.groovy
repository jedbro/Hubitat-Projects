/*
 *	Auto Lock (Parent)
 *
 *  Code based on Chris Sader's Auto Lock Door app and he gets full credit.  I just wanted a parent/child version.
 * 
 * 
 */

def setVersion() {
    state.name = "Auto Lock"
	state.version = "1.0.5"
}

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
