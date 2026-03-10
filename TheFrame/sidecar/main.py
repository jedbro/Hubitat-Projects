"""
TheFrame Sidecar — FastAPI service for Samsung Frame TV control.
Exposes a simple REST API consumed by the Hubitat driver.
"""
import io
import logging
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from pydantic import BaseModel

import tv
from config import load_config, server_config

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
    intervalSeconds: Optional[int] = 1800  # 30 minutes default

class UploadUrlRequest(BaseModel):
    url: str
    fileType: Optional[str] = "JPEG"


# ---------------------------------------------------------------------------
# TV state & power
# ---------------------------------------------------------------------------

@app.get("/api/tv/state")
async def get_state():
    """Returns power, artMode, and isWatching status."""
    return await tv.get_state()


@app.post("/api/tv/power/on")
def power_on():
    """Wake-on-LAN to turn the TV on."""
    result = tv.power_on()
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@app.post("/api/tv/power/off")
def power_off():
    """Send power key to turn TV off."""
    return tv.power_off()


# ---------------------------------------------------------------------------
# Input switching
# ---------------------------------------------------------------------------

@app.post("/api/tv/input/{name}")
def set_input(name: str):
    """Switch to a named input (e.g. appletv, hdmi2)."""
    result = tv.set_input(name)
    if "error" in result:
        raise HTTPException(status_code=400, detail=result)
    return result


@app.get("/api/tv/inputs")
def list_inputs():
    """List configured input names."""
    from config import input_map
    return {"inputs": list(input_map().keys())}


# ---------------------------------------------------------------------------
# Art mode
# ---------------------------------------------------------------------------

@app.post("/api/tv/artmode/on")
async def art_mode_on():
    return await tv.art_mode_on()


@app.post("/api/tv/artmode/off")
async def art_mode_off():
    return await tv.art_mode_off()


# ---------------------------------------------------------------------------
# Art management
# ---------------------------------------------------------------------------

@app.get("/api/art/list")
async def list_art():
    """List all artwork available on the TV."""
    return await tv.list_art()


@app.get("/api/art/current")
async def current_art():
    """Get currently displayed artwork info."""
    return await tv.get_current_art()


@app.post("/api/art/select")
async def select_art(body: SelectArtRequest):
    """Display a specific artwork by content ID."""
    return await tv.select_art(body.contentId)


@app.post("/api/art/upload/file")
async def upload_art_file(file: UploadFile = File(...)):
    """Upload an image file to the TV's art collection."""
    data = await file.read()
    ext = (file.filename or "image.jpg").rsplit(".", 1)[-1].upper()
    file_type = "JPEG" if ext in ("JPG", "JPEG") else ext
    return await tv.upload_art(data, file_type=file_type)


@app.post("/api/art/upload/url")
async def upload_art_url(body: UploadUrlRequest):
    """Fetch an image from a URL and upload it to the TV's art collection."""
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(body.url)
        resp.raise_for_status()
    return await tv.upload_art(resp.content, file_type=body.fileType)


@app.post("/api/art/slideshow")
async def set_slideshow(body: SlideshowRequest):
    """Start or stop the art slideshow."""
    return await tv.set_slideshow(body.enabled, body.intervalSeconds)


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------

@app.get("/health")
def health():
    return {"status": "ok"}


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn
    cfg = server_config()
    uvicorn.run("main:app", host=cfg["host"], port=cfg["port"], reload=False)
