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
git clone https://github.com/jedbro/Hubitat-Projects.git
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

### 2. Pair with the TV (required first time)

The TV must be paired before art operations will work. **Watch your TV screen**, then run:

```bash
curl -X POST http://localhost:8088/api/tv/pair
```

A prompt will appear on the TV — **accept it**. Then run the command again to confirm pairing succeeded. You should see `"status": "paired"`. The token is saved to `/opt/theframe/token.txt` and reused automatically forever.

### 3. Install the Hubitat Driver

1. In Hubitat, go to **Drivers Code → New Driver**
2. Paste the contents of `hubitat/samsung-frame-tv-driver.groovy`
3. Save

### 4. Add the Device

1. Go to **Devices → Add Device → Virtual**
2. Choose **Samsung Frame TV** as the type
3. In preferences, set the **Sidecar Host/IP** (your Pi's IP) and **Port** (8088)
4. Set your preferred **Poll Interval** (5 minutes is a good default)
5. Save — the driver will immediately poll for current state

---

## Sidecar REST API

The sidecar runs on port 8088. All endpoints are callable directly with `curl` for testing.

### Pairing

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/tv/pair` | Trigger TV pairing prompt. Accept on TV, then call again to confirm. |

```bash
curl -X POST http://<pi-ip>:8088/api/tv/pair
```

---

### TV State & Power

| Method | Endpoint | Description |
|---|---|---|
| `GET`  | `/api/tv/state` | Returns power, artMode, isWatching, currentSource |
| `POST` | `/api/tv/power/on` | Wake-on-LAN power on (requires MAC in config) |
| `POST` | `/api/tv/power/off` | Send power off key |

```bash
curl http://<pi-ip>:8088/api/tv/state
# {"power":"on","artMode":"off","isWatching":true,"currentSource":"appletv"}

curl -X POST http://<pi-ip>:8088/api/tv/power/on
curl -X POST http://<pi-ip>:8088/api/tv/power/off
```

---

### Input Switching

| Method | Endpoint | Description |
|---|---|---|
| `GET`  | `/api/tv/inputs` | List configured input names from config.yml |
| `POST` | `/api/tv/input/{name}` | Switch to a named input |

```bash
curl http://<pi-ip>:8088/api/tv/inputs
# {"inputs":["appletv","hdmi2"]}

curl -X POST http://<pi-ip>:8088/api/tv/input/appletv
```

---

### Art Mode

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/tv/artmode/on` | Enable art/frame display mode |
| `POST` | `/api/tv/artmode/off` | Disable art mode (return to normal TV) |

```bash
curl -X POST http://<pi-ip>:8088/api/tv/artmode/on
curl -X POST http://<pi-ip>:8088/api/tv/artmode/off
```

---

### Art Management

| Method | Endpoint | Body | Description |
|---|---|---|---|
| `GET`  | `/api/art/list` | — | List all artwork in My Collection on the TV |
| `GET`  | `/api/art/current` | — | Get currently displayed artwork info |
| `POST` | `/api/art/select` | `{"contentId":"MY_F0001"}` | Display specific artwork by content ID |
| `POST` | `/api/art/upload/url` | `{"url":"https://...","fileType":"JPEG"}` | Fetch image from URL and upload to TV |
| `POST` | `/api/art/upload/file` | multipart file | Upload a local image file to TV |
| `POST` | `/api/art/slideshow` | `{"enabled":true,"intervalSeconds":1800}` | Start or stop art slideshow |

```bash
# List available art (IDs shown in output)
curl http://<pi-ip>:8088/api/art/list

# See what's currently displayed
curl http://<pi-ip>:8088/api/art/current

# Select a specific piece by content ID
curl -X POST http://<pi-ip>:8088/api/art/select \
  -H "Content-Type: application/json" \
  -d '{"contentId":"MY_F0001"}'

# Upload art from a URL
curl -X POST http://<pi-ip>:8088/api/art/upload/url \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/painting.jpg","fileType":"JPEG"}'

# Start slideshow (change every 30 minutes)
curl -X POST http://<pi-ip>:8088/api/art/slideshow \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"intervalSeconds":1800}'

# Stop slideshow
curl -X POST http://<pi-ip>:8088/api/art/slideshow \
  -H "Content-Type: application/json" \
  -d '{"enabled":false}'
```

---

### Health Check

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/health` | Confirm sidecar is running |

```bash
curl http://<pi-ip>:8088/health
# {"status":"ok"}
```

---

### Interactive API Docs

FastAPI generates interactive docs automatically. Open in a browser:
```
http://<pi-ip>:8088/docs
```

---

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

---

## Driver Commands

| Command | Description |
|---|---|
| `on()` | Wake-on-LAN power on |
| `off()` | Power off |
| `artModeOn()` | Switch to art/frame display mode |
| `artModeOff()` | Exit art mode |
| `setInput(name)` | Switch input (`appletv`, `hdmi2`, etc.) |
| `selectArt(contentId)` | Display specific artwork by content ID |
| `nextArt()` | Advance to next artwork in collection |
| `listArt()` | Fetch available art IDs (logged + stored in attribute) |
| `slideshowOn(minutes)` | Start art rotation (default 30 min) |
| `slideshowOff()` | Stop art rotation |
| `uploadArtUrl(url)` | Upload image from URL to TV collection |
| `refresh()` | Poll current TV state |

## Driver Attributes

| Attribute | Values | Description |
|---|---|---|
| `switch` | `on` / `off` | TV power state |
| `artMode` | `on` / `off` | Frame art mode state |
| `isWatching` | `true` / `false` | True when TV is on and NOT in art mode |
| `currentInput` | string | Current detected input source |
| `currentArtId` | string | Content ID of displayed artwork |
| `artList` | JSON | Full list of available art from TV |
| `lastUpdated` | string | Timestamp of last successful poll |

---

## Tips & Reference

### Finding Your TV's MAC Address

On the TV: **Settings → General → Network → Network Status → IP Settings**

Or from your router's DHCP lease table.

### Finding Your HDMI Port for Apple TV

On the TV remote, press **Source** and note which HDMI port your Apple TV appears on.
Then set `KEY_HDMI1`, `KEY_HDMI2`, `KEY_HDMI3`, or `KEY_HDMI4` accordingly in config.yml.

### Art Content IDs

Content IDs look like `MY_F0001`, `MY_F0002`, etc. Run `listArt` in Hubitat or:
```bash
curl http://<pi-ip>:8088/api/art/list
```
to see all IDs available on your TV.

### Slideshow Intervals

Common `intervalSeconds` values:
- `300` = 5 minutes
- `600` = 10 minutes
- `1800` = 30 minutes (default)
- `3600` = 1 hour
- `86400` = 24 hours

### Logs

```bash
sudo journalctl -u theframe -f             # live logs
sudo journalctl -u theframe --since today  # today's logs
```
