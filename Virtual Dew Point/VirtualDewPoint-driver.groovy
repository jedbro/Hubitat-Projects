// V0.02  Virtual Dew Point Device

metadata {
    definition (name: "Virtual DewPoint", namespace: "JohnRob", author: "several", importUrl: "https://raw.githubusercontent.com/jedbro/Hubitat-Projects/main/Virtual%20Dew%20Point/VirtualDewPoint-driver.groovy") {
        capability  "Sensor"
        command     "setDewPoint", ["NUMBER"]   // this will be a method.  [] may cause an input box to be created must test.
        attribute   "DewPoint", "Number"      // this will go into the Hub database

    }
    preferences {       // These become entries in the "device" page to ask us for our preferences!
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() {
    log.warn "installed..."
    setDewPoint(0)
}

def updated() {
    log.info "updated..."
    log.warn "description logging is: ${txtEnable == true}"
}

def parse(String description) {
}       // basically useless.  Included because it is included in the template.

def setDewPoint(dewpoint) {
    //log.debug "   29  dewpoint =   ${dewpoint}"
    def descriptionText = "${device.displayName} was set to $dewpoint"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "DewPoint", value: dewpoint, unit: "°F", descriptionText: descriptionText)
}
