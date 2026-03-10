# TheFrame — Samsung Frame TV for Hubitat

Control your Samsung Frame TV from Hubitat: power, input switching, art mode, art selection, and slideshow — all automatable through Rules Machine.

## Architecture

```
Hubitat Hub  ──HTTP──▶  TheFrame Sidecar (Raspberry Pi)  ──WebSocket──▶  Samsung Frame TV
 (driver)                  FastAPI + samsungtvws
```

## Setup

### 1. Install the Sidecar on your Raspberry Pi

```bash
git clone https://github.com/your-repo/Hubitat-Projects.git
cd Hubitat-Projects/TheFrame/sidecar
sudo bash install.sh
```

Edit the config:
```bash
sudo nano /opt/theframe/config.yml
```

Fill in your TV's IP address, MAC address (for Wake-on-LAN), and input mappings:
```yaml
tv:
  host: "192.168.1.XXX"
  mac: "AA:BB:CC:DD:EE:FF"

inputs:
  appletv: "KEY_HDMI1"   # adjust HDMI port number to match your setup
  hdmi2: "KEY_HDMI2"
```

Start the service:
```bash
sudo systemctl start theframe
sudo systemctl status theframe
```

**First connection:** Your TV will display a pairing prompt — accept it. The token is saved automatically.

The API will be at `http://<pi-ip>:8088`. Test it:
```bash
curl http://<pi-ip>:8088/api/tv/state
curl http://<pi-ip>:8088/api/tv/inputs
```

### 2. Install the Hubitat Driver

1. In Hubitat, go to **Drivers Code → New Driver**
2. Paste the contents of `hubitat/samsung-frame-tv-driver.groovy`
3. Save

### 3. Add the Device

1. Go to **Devices → Add Device → Virtual**
2. Choose **Samsung Frame TV** as the type
3. In preferences, set the **Sidecar Host/IP** (your Pi's IP) and **Port** (8088)
4. Set your preferred **Poll Interval** (5 minutes is a good default)
5. Save — the driver will immediately poll for current state

## Hubitat Automations

### Turn off TV at night (only if not watching)

```
Trigger:   Time 11:30 PM
Condition: Samsung Frame TV → isWatching == false
Action:    Samsung Frame TV → off()
```

### Switch to Apple TV when arriving home

```
Trigger: Presence → Arrived
Action:  Samsung Frame TV → setInput("appletv")
         Samsung Frame TV → on()
```

### Art mode when leaving home

```
Trigger: All present → false (everyone left)
Action:  Samsung Frame TV → artModeOn()
```

## Driver Commands

| Command | Description |
|---|---|
| `on()` | Wake-on-LAN power on |
| `off()` | Power off |
| `artModeOn()` | Switch to art/frame display mode |
| `artModeOff()` | Exit art mode |
| `setInput(name)` | Switch input (`appletv`, `hdmi2`, etc.) |
| `selectArt(contentId)` | Display specific artwork |
| `nextArt()` | Advance to next artwork |
| `listArt()` | Fetch available art IDs (logged + stored in attribute) |
| `slideshowOn(minutes)` | Start art rotation (default 30 min) |
| `slideshowOff()` | Stop art rotation |
| `uploadArtUrl(url)` | Upload image from URL to TV |
| `refresh()` | Poll current TV state |

## Driver Attributes

| Attribute | Values | Description |
|---|---|---|
| `switch` | `on` / `off` | TV power state |
| `artMode` | `on` / `off` / `unknown` | Frame art mode state |
| `isWatching` | `true` / `false` | True when TV is on and NOT in art mode |
| `currentInput` | string | Last input switched to |
| `currentArtId` | string | Content ID of displayed artwork |
| `artList` | JSON | Full list of available art from TV |

## Finding Your TV's MAC Address

On the TV: **Settings → General → Network → Network Status → IP Settings**

Or from your router's DHCP lease table.

## Finding Your HDMI Port for Apple TV

On the TV remote, press **Source** and note which HDMI port your Apple TV appears on.
Then set `KEY_HDMI1`, `KEY_HDMI2`, `KEY_HDMI3`, or `KEY_HDMI4` accordingly in config.yml.

## Logs

```bash
sudo journalctl -u theframe -f          # live logs
sudo journalctl -u theframe --since today  # today's logs
```
