/**
 *  Vacation Lighting Simulator Suite (Parent)
 *  V0.3.1 - December 2025
 *    - Introduces parent/child structure for multiple schedules plus analyzer child
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "v0.3.1 • Dec 2025"

definition(
    name: "Vacation Lighting Simulator Suite",
    namespace: "Logicalnonsense",
    author: "Jed Brown",
    description: "Parent controller for simulating and analyzing light and switch behaviors of an occupied home while you are away or on Vacation.",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

private String appVersion() { APP_VERSION }

// --------- UI formatting helpers ---------

private String getFormat(String type, String myText = "") {
    switch(type) {
        case "header-blue":
            return "<div style='color:#ffffff;background-color:#1A77C9;border:1px solid #1A77C9;padding:4px 8px;margin:4px 0;'><b>${myText}</b></div>"
        case "line-blue":
            return "<hr style='background-color:#1A77C9;height:1px;border:0;margin-top:8px;margin-bottom:8px;'>"
        default:
            return myText
    }
}

private List getScheduleChildren() {
    return getChildApps()?.findAll { it?.name == "Vacation Lighting Simulator" } ?: []
}

private List getAnalyzerChildren() {
    return getChildApps()?.findAll { it?.name == "Vacation Lighting Analyzer" } ?: []
}

private String childSummary() {
    def schedules = getScheduleChildren()
    def analyzers = getAnalyzerChildren()
    int scheduleCount = schedules?.size() ?: 0
    int analyzerCount = analyzers?.size() ?: 0

    StringBuilder sb = new StringBuilder()
    sb << "<b>Lighting schedules:</b> ${scheduleCount}<br>"
    schedules.each { child ->
        sb << "&nbsp;&nbsp;• ${child.label ?: child.name}<br>"
    }

    sb << "<b>Analyzer children:</b> ${analyzerCount}<br>"
    analyzers.each { child ->
        sb << "&nbsp;&nbsp;• ${child.label ?: child.name}<br>"
    }

    if (!scheduleCount && !analyzerCount) {
        sb << "No children yet. Add a schedule to get started."
    }
    return sb.toString()
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("") {
            paragraph "<div style='text-align:right;font-size:11px;color:#888;'>${appVersion()}</div>"
            paragraph getFormat("header-blue", "Vacation Lighting Simulator Suite")
            paragraph "Manage multiple Vacation Lighting Simulator schedules plus an optional Analyzer child from one dashboard."
        }

        section("") {
            paragraph getFormat("header-blue", "Your children")
            paragraph childSummary()
        }

        section("") {
            paragraph getFormat("header-blue", "Create children")
            paragraph "Add as many schedule children as you need, then optionally create the Analyzer when you want history charts."
            app(name: "vacationScheduleChild", appName: "Vacation Lighting Simulator", namespace: "Logicalnonsense", title: "➕ Add lighting schedule", multiple: true)
            app(name: "vacationAnalyzerChild", appName: "Vacation Lighting Analyzer", namespace: "Logicalnonsense", title: "➕ Add analyzer", multiple: false)
        }

        section("") {
            paragraph getFormat("header-blue", "Suite options")
            label title: "Suite label (optional)", required: false
        }
    }
}

def installed() {
    log.info "Vacation Lighting Simulator Suite installed"
}

def updated() {
    log.info "Vacation Lighting Simulator Suite updated"
}
