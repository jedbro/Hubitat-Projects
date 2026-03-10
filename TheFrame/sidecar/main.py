"""
TheFrame Sidecar — FastAPI service for Samsung Frame TV control.
Exposes a simple REST API consumed by the Hubitat driver.
"""
import logging
import platform
import sys
import time
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.responses import RedirectResponse
from pydantic import BaseModel

import tv
from config import load_config, server_config, tv_config, input_map

SIDECAR_VERSION = "1.1.0"
_start_time = time.time()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="TheFrame Sidecar", version="1.0.0")

load_config()


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------

class SelectArtRequest(BaseModel):
    contentId: str

class SlideshowRequest(BaseModel):
    enabled: bool
    intervalSeconds: Optional[int] = 1800

class UploadUrlRequest(BaseModel):
    url: str
    fileType: Optional[str] = "JPEG"


# ---------------------------------------------------------------------------
# Root redirect
# ---------------------------------------------------------------------------

@app.get("/")
def root():
    return RedirectResponse(url="/docs")


# ---------------------------------------------------------------------------
# Pairing
# ---------------------------------------------------------------------------

@app.post("/api/tv/pair")
def pair():
    """
    Initiate TV pairing. The TV will show an on-screen prompt — accept it,
    then call this endpoint again to confirm the token was saved.
    """
    return tv.pair()


# ---------------------------------------------------------------------------
# TV state & power
# ---------------------------------------------------------------------------

@app.get("/api/tv/state")
def get_state():
    """Returns power, artMode, and isWatching status."""
    return tv.get_state()


@app.post("/api/tv/power/on")
def power_on():
    result = tv.power_on()
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@app.post("/api/tv/power/off")
def power_off():
    return tv.power_off()


# ---------------------------------------------------------------------------
# Input switching
# ---------------------------------------------------------------------------

@app.post("/api/tv/input/{name}")
def set_input(name: str):
    result = tv.set_input(name)
    if "error" in result:
        raise HTTPException(status_code=400, detail=result)
    return result


@app.get("/api/tv/inputs")
def list_inputs():
    from config import input_map
    return {"inputs": list(input_map().keys())}


# ---------------------------------------------------------------------------
# Art mode
# ---------------------------------------------------------------------------

@app.post("/api/tv/artmode/on")
def art_mode_on():
    return tv.art_mode_on()


@app.post("/api/tv/artmode/off")
def art_mode_off():
    return tv.art_mode_off()


# ---------------------------------------------------------------------------
# Art management
# ---------------------------------------------------------------------------

@app.get("/api/art/list")
def list_art():
    return tv.list_art()


@app.get("/api/art/current")
def current_art():
    return tv.get_current_art()


@app.post("/api/art/select")
def select_art(body: SelectArtRequest):
    return tv.select_art(body.contentId)


@app.post("/api/art/upload/file")
def upload_art_file(file: UploadFile = File(...)):
    data = file.file.read()
    ext = (file.filename or "image.jpg").rsplit(".", 1)[-1].upper()
    file_type = "JPEG" if ext in ("JPG", "JPEG") else ext
    return tv.upload_art(data, file_type=file_type)


@app.post("/api/art/upload/url")
def upload_art_url(body: UploadUrlRequest):
    resp = httpx.get(body.url, timeout=30)
    resp.raise_for_status()
    return tv.upload_art(resp.content, file_type=body.fileType)


@app.post("/api/art/slideshow")
def set_slideshow(body: SlideshowRequest):
    return tv.set_slideshow(body.enabled, body.intervalSeconds)


# ---------------------------------------------------------------------------
# Health & diagnostics
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    paired = tv.is_paired()
    reachable = tv.is_reachable()
    return {
        "status": "ok",
        "version": SIDECAR_VERSION,
        "paired": paired,
        "tvReachable": reachable,
    }


@app.get("/debug")
def debug():
    """Full diagnostic snapshot — useful for troubleshooting."""
    cfg = tv_config()
    paired = tv.is_paired()
    reachable = tv.is_reachable()
    uptime_seconds = int(time.time() - _start_time)

    # Attempt live state only if TV is reachable
    live_state = None
    if reachable:
        try:
            live_state = tv.get_state()
        except Exception as e:
            live_state = {"error": str(e)}

    return {
        "sidecar": {
            "version": SIDECAR_VERSION,
            "uptimeSeconds": uptime_seconds,
            "pythonVersion": sys.version,
            "platform": platform.platform(),
        },
        "config": {
            "tvHost": cfg.get("host"),
            "tvPort": cfg.get("port", 8002),
            "macConfigured": bool(cfg.get("mac")),
            "inputsConfigured": list(input_map().keys()),
        },
        "connectivity": {
            "tvReachable": reachable,
            "paired": paired,
        },
        "liveState": live_state,
    }


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn
    cfg = server_config()
    uvicorn.run("main:app", host=cfg["host"], port=cfg["port"], reload=False)
