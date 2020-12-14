/*
Dew Point App V004

2020-07-05
    Think we don't need to store the lastTEMP and lastHUMID.  We can just read them when we need to make a calc.
2020-07-20 (open) added Virtual DewPoint Calc device to display the below results.
2020-12-14 Fixed namespace errors that prevented the app and driver to load (@jedbro)

*/

definition(
    name: "DEW Point Calculator",
    namespace: "JohnRob",
    author: "JohnRob",
    description: "DEW Point Calculator",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jedbro/Hubitat-Projects/main/Virtual%20Dew%20Point/VirtualDewPoint-app.groovy")

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section {
            //log.debug ("   25   beginning of section")
            input "thisName", "text", title: "Name this DEW Point Calculator", submitOnChange: true
            if(thisName) app.updateLabel("$thisName")
            input "tempSensor", "capability.temperatureMeasurement", title: "Select Temperature Sensor", submitOnChange: true, required: true,     multiple: false
            input "humidSensor", "capability.relativeHumidityMeasurement", title: "Select Humidity Sensor", submitOnChange: true, required:     true, multiple: false
            //log.debug ("  30   end of section")
        } // section
    }   // dymanicPage
}   // mainPage

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    //log.debug ("  45   begin initialize")

    def dewpointDev = getChildDevice("DEWPoint_${app.id}")
    if(!dewpointDev) dewpointDev = addChildDevice("JohnRob", "Virtual DewPoint", "DEWPoint_${app.id}", null, [label: thisName, name: thisName])
    dewpointDev.setDewPoint(0)
    subscribe(tempSensor, "temperature", handlerTEMP)
    subscribe(humidSensor, "humidity", handlerHUMID)

    state.lastHUMID = 50        // these are in the app and will not display in the child
    state.lastTEMP = 50     //  50/50 DEWPoint = 32
}

def calcDEW() {
    def dewpointDev = getChildDevice("DEWPoint_${app.id}")
    //log.debug "  56 state.lastTEMP ${state.lastTEMP}"
    //log.debug "  57 state.lastHUMID ${state.lastHUMID}"
    operandHUMID = state.lastHUMID.toDouble()
    operandTEMP = state.lastTEMP.toDouble()
    
    def dewPoint = (operandTEMP - (9 / 25) * (100 - operandHUMID))
    //log.debug "   62  dewPoint =     ${dewPoint}"
    dewpointDev.setDewPoint(dewPoint.toInteger())
    //return
}

def handlerHUMID(evt) {
    state.lastHUMID = evt.value
    //log.debug " 65 last Humidity = ${evt.value}"
   calcDEW()
}

def handlerTEMP(evt) {
    state.lastTEMP = evt.value
    //log.debug " 71 last Temperature = ${evt.value}"
   calcDEW()
}
