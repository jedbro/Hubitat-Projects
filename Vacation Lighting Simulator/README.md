# Vacation Lighting Simulator (Hubitat)

**Vacation Lighting Simulator** is a smart and flexible occupancy-simulation app for **Hubitat Elevation**.  
The suite lets you run multiple independent lighting schedules plus an optional Analyzer child. Together they help your home look lived-in while you're away by turning lights on and off in a realistic, human-like pattern — using randomized cycles, anchor lights, time-window restrictions, and intelligent scheduling.

This app is optimized specifically for Hubitat’s automations model, event-driven architecture, and lightweight Groovy runtime.  
It is fast, dependable, and configurable for both simple and advanced use cases.

---

## ✨ Key Features

### 🧩 Vacation Lighting Suite Architecture
- Parent app manages multiple lighting-schedule children plus the Analyzer child from a single dashboard.
- Each schedule child keeps its own triggers, devices, anchor lights, and summaries so you can model different rooms or time windows.
- Analyzer child stays lightweight and on-demand—install it only if you want history charts.

### 🕒 Realistic Occupancy Simulation
- Lights turn on randomly within your configured set.
- Per-light on durations vary using **frequency ± ~20% jitter** (style B randomness).
- Cycles run only within your allowed **time window** and **day-of-week** constraints.
- Automatically stops when outside allowed modes or when vacation switch is turned off.
- Manual override: turning the optional vacation switch ON bypasses the time window *and* the mode restriction so you can force a cycle early.

### 💡 Anchor Lights (Always-On During Session)
Choose certain “anchor” lights that remain on whenever the app is active.  
Great for porch lights, hallway lamps, or areas typically left lit during the evening.

### 🔀 Intelligent Randomization
- Each cycle selects `N` lights at random (configurable).
- Per-light on durations are randomized.
- Lights turn off automatically and independently via a fast queue-based scheduler.

### 🔧 Hubitat Performance Optimizations
- Uses a **single off-queue loop** for per-light shutdowns.
- Avoids multi-schedule overhead common in older VLD-style apps.
- Uses `state` instead of `atomicState` for efficiency.
- Carefully avoids unnecessary triggers and timers.

### 💤 Auto-Shutoff When Leaving the Allowed Window
The app instantly stops and turns off:
- All randomized lights still running  
- All anchor lights  
when:
- Mode changes  
- Vacation switch turns off  
- Time window ends  

### 📬 Daily Summary Notifications (Optional)
Receive a once-per-day summary that includes:
- Cycles run
- Lights turned on / off
- Devices involved

### 🧪 One-Tap Test Cycle (Manual)
Use the built-in “Run a test cycle now” toggle to run a **single preview cycle**.  
This is helpful for verifying:
- Light selection  
- Integration with hubs  
- Anchor behavior  
- Notification delivery  

### 📊 Analyzer Child (History Timeline)
- Optional child that lives under the Vacation Lighting Suite parent.  
- Pick any date/time window and render a per-device timeline straight from Hubitat event history (1000-event cap, 24h lookback to catch anchor lights that were already on).  
- Includes per-device stats and percentage-on calculations to validate that your vacation schedules behave the way you expect.

---

# 📥 Installation

You may install the app one of two ways.

---

## 🔌 **Option 1: Hubitat Package Manager (HPM)**  
**Recommended — easiest and keeps you updated automatically.**

1. Install **Hubitat Package Manager** (if not already installed).  
2. In HPM → *“Install”* → *“Search by Keywords”* and search:  
   **Vacation Lighting Simulator**
3. Select the package → Install.
4. App will now appear under **Apps** in your Hubitat home.

_Note:_ If you aren't finding it, try turning 'Fast Search' off in HPM.
---

## 🧩 **Option 2: Manual Installation**

1. Open your Hubitat hub web UI  
2. Navigate to **Apps Code**  
3. Click **+ New App**  
4. Add each of the following App Code files (parent first, then children):

   - **Parent:** [Vacation Lighting Suite](https://raw.githubusercontent.com/jedbro/Hubitat-Projects/refs/heads/main/Vacation%20Lighting%20Simulator/Vacation%20Lighting%20Suite.groovy)
   - **Schedule child:** [Vacation Lighting Simulator](https://raw.githubusercontent.com/jedbro/Hubitat-Projects/refs/heads/main/Vacation%20Lighting%20Simulator/Vacation%20Lighting%20Simulator.groovy)
   - **Analyzer child (optional):** [Vacation Lighting Analyzer](https://raw.githubusercontent.com/jedbro/Hubitat-Projects/refs/heads/main/Vacation%20Lighting%20Simulator/Vacation%20Lighting%20Analyzer.groovy)

5. Save each file  
6. Go to **Apps** → **Add User App** → select **Vacation Lighting Suite**, then create lighting schedule children (and the Analyzer child if desired) from within the parent UI

---

# ⚙️ Configuration Overview

The app is organized into three pages:

## 1. 🛠 Setup  
Where you configure:
- **Modes** that allow vacation lighting  
- **Optional vacation switch** (virtual or physical)  
   - When turned ON it overrides both the configured time window and the mode restriction, letting you start cycles ahead of schedule.
- **Time window** (specific times, sunrise/sunset ± offsets)  
- **Lights to randomize**  
- **Number of active lights per cycle**  
- **Frequency between cycles**  
- **Anchor lights** (always-on during sessions)

This is where you define **when** the algorithm is allowed to run and **which** lights it controls.

---

## 2. ⚙️ Settings  
Advanced behavior and optional add-ons:
- Delay before first cycle (`falseAlarmThreshold`)
- Day-of-week restrictions
- Daily Summary notifications
- Debug logging toggle

This page is primarily for fine-tuning or power users.

---

## 3. 🧪 Test Cycle  
A dedicated toggle on the main page:

**“Run a test cycle now?”**

Turning it ON and pressing **Done** runs a single simulated cycle immediately, regardless of:
- mode  
- day of week  
- time window  
- vacation switch configuration  

The result is sent as a notification if you have configured notifications.

---

# 📬 Example Daily Summary Notification

This is what a typical summary looks like:

📊 Vacation Lighting Daily Summary:
• 🟢 7 cycle(s) simulated
• 💡 18 light(s) turned on
• 💤 18 light(s) turned off
• 💡 Lights turned on: Kitchen, Living Room, Hallway, Porch, Bedroom Lamp, Office Desk Light
• 💤 Lights turned off: Kitchen, Living Room, Hallway, Porch, Bedroom Lamp, Office Desk Light

• 🔁 Additional light offs may still occur (queued)
🏠 Summary covers the last 24 hours of vacation-mode behavior.


---

# 🛠 Troubleshooting

### 💡 Lights never turn on
Check:
- Current **Mode** is included in your allowed modes  
- Vacation switch (if set) is **ON**  
- You are **within your configured time window**  
- Today is allowed based on your **days-of-week** setting  
- At least **one light** is selected in “Switches to randomize”

### 💡 Anchor lights stay on after a Test Cycle
This is **expected**.  
Test cycles simulate exactly one cycle, *not* a full active session.  
Anchor lights only turn off:
- When mode changes  
- When vacation switch turns off  
- When you exit the time window  
- Or when an armed session ends normally

### 💡 Daily Summary says "No cycles ran"
Usually caused by:
- Being outside the allowed time window the entire day  
- Vacation switch off  
- Wrong mode  
- No lights selected  

### 🕒 Cycles not running as often as expected
Check your **frequency_minutes** setting.  
The app enforces:
- A minimum of **5 minutes**
- A grid-based cycle schedule that adds **± 0–14 minutes** of jitter

### 🔁 “Next cycle” shows “not scheduled”
This typically means:
- The app is not armed  
- You are outside the time window  
- Or the next `initCheck` has not yet been scheduled (new install/update)

---

# 🔄 Changelog

## **v0.3.0 — December 2025**  
✔ Introduced the **Vacation Lighting Suite** parent so you can run multiple lighting schedule children.  
✔ Added the **Vacation Lighting Analyzer** child for on-demand history timelines and per-device stats.  
✔ Vacation switch ON bypasses both the configured time window and the mode restriction for manual overrides.  
✔ Existing schedule logic moved into the child app with no behavioral changes required.  

## **v0.2.4 — December 2025 (Initial Release)**  
✔ Per-light randomized durations (frequency ±20%)  
✔ Queue-based off scheduler (performance optimized)  
✔ Optional time windows (fixed time or sunrise/sunset ± offsets)  
✔ Optional vacation switch gating  
✔ Anchor lights (always on during an active session)  
✔ Daily summary notifications  
✔ Test Cycle trigger  
✔ Smart “next cycle” logic with jitter  
✔ Detailed status dashboard  
✔ Debug logging improvements  
✔ Hubitat runtime optimizations (`state`, minimal schedules)

---

# 📄 License

Licensed under the Apache License, Version 2.0.  
See: http://www.apache.org/licenses/LICENSE-2.0

---

# 🙌 Contributions

Pull requests welcome!  
If you create improvements, optimizations, or enhancements, feel free to submit a PR or open an issue on GitHub.

---

## 🙏 Project Lineage & Inspiration

This app is heavily inspired by the original **Vacation Lighting Director (VLD)** project,  
created and maintained here:  
https://github.com/imnotbob/vacation-lighting-director/tree/beta

While the core *idea* of randomized lighting patterns comes from VLD,  
**Vacation Lighting Simulator** is a substantially rewritten and extended version built specifically
for my own use cases and the modern Hubitat platform.

Major differences and improvements include:

- Complete rewrite of scheduling logic  
- Queue-based per-light off management (far more efficient on Hubitat)  
- Smart jitter-based cycle timing  
- Anchor-light support  
- Daily summary engine  
- Test cycle system  
- App-state consolidation for performance  
- Stronger guardrails around time windows, modes, and switches  
- Clearer UI organization (Setup / Settings / Test)  
- More robust stopping behavior when leaving the allowed time range  
- Extensive logging and debug tools  


