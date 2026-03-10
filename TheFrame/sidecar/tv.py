"""
Samsung Frame TV controller.
Wraps samsungtvws for both general TV control and art mode operations.
"""
import asyncio
import logging
import os
from contextlib import asynccontextmanager
from typing import Optional

import wakeonlan
from samsungtvws import SamsungTVWS
from samsungtvws.async_art import SamsungTVAsyncArt

from config import tv_config, input_map

logger = logging.getLogger(__name__)


def _token_file_path() -> str:
    cfg = tv_config()
    token_file = cfg.get("token_file", "token.txt")
    # Allow relative paths resolved from the script directory
    if not os.path.isabs(token_file):
        token_file = os.path.join(os.path.dirname(__file__), token_file)
    return token_file


def _make_tv() -> SamsungTVWS:
    cfg = tv_config()
    return SamsungTVWS(
        host=cfg["host"],
        port=cfg.get("port", 8002),
        token_file=_token_file_path(),
    )


@asynccontextmanager
async def _art_api():
    cfg = tv_config()
    api = SamsungTVAsyncArt(
        host=cfg["host"],
        port=cfg.get("port", 8002),
        token_file=_token_file_path(),
    )
    await api.start_listening()
    try:
        yield api
    finally:
        await api.close()


# ---------------------------------------------------------------------------
# Power
# ---------------------------------------------------------------------------

def is_reachable() -> bool:
    """Try to open a connection to determine if the TV is on."""
    import socket
    cfg = tv_config()
    try:
        s = socket.create_connection((cfg["host"], cfg.get("port", 8002)), timeout=2)
        s.close()
        return True
    except OSError:
        return False


def power_on() -> dict:
    cfg = tv_config()
    mac = cfg.get("mac")
    if not mac:
        return {"error": "mac address not configured for Wake-on-LAN"}
    wakeonlan.send_magic_packet(mac)
    return {"status": "wake-on-lan sent", "mac": mac}


def power_off() -> dict:
    tv = _make_tv()
    tv.send_key("KEY_POWER")
    return {"status": "ok"}


# ---------------------------------------------------------------------------
# Input
# ---------------------------------------------------------------------------

def set_input(name: str) -> dict:
    mapping = input_map()
    key = mapping.get(name.lower())
    if not key:
        available = list(mapping.keys())
        return {"error": f"unknown input '{name}'", "available": available}
    tv = _make_tv()
    tv.send_key(key)
    return {"status": "ok", "input": name, "key": key}


# ---------------------------------------------------------------------------
# Art Mode
# ---------------------------------------------------------------------------

async def art_mode_on() -> dict:
    async with _art_api() as api:
        await api.set_artmode("on")
    return {"status": "ok", "artMode": "on"}


async def art_mode_off() -> dict:
    async with _art_api() as api:
        await api.set_artmode("off")
    return {"status": "ok", "artMode": "off"}


async def get_art_mode() -> Optional[str]:
    """Returns 'on', 'off', or None if unreachable."""
    try:
        async with _art_api() as api:
            result = await api.get_artmode()
            return result
    except Exception as e:
        logger.warning(f"Could not get art mode: {e}")
        return None


# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------

async def get_state() -> dict:
    power = is_reachable()
    art_mode = None
    is_watching = False

    if power:
        art_mode = await get_art_mode()
        is_watching = art_mode == "off"

    return {
        "power": "on" if power else "off",
        "artMode": art_mode or "unknown",
        "isWatching": is_watching,
    }


# ---------------------------------------------------------------------------
# Art operations
# ---------------------------------------------------------------------------

async def list_art() -> dict:
    async with _art_api() as api:
        items = await api.available()
    return {"items": items}


async def get_current_art() -> dict:
    async with _art_api() as api:
        current = await api.get_current()
    return current


async def select_art(content_id: str) -> dict:
    async with _art_api() as api:
        await api.select_image(content_id)
    return {"status": "ok", "contentId": content_id}


async def upload_art(image_bytes: bytes, file_type: str = "JPEG") -> dict:
    async with _art_api() as api:
        result = await api.upload(image_bytes, file_type=file_type)
    return {"status": "ok", "result": result}


async def set_slideshow(enabled: bool, interval_seconds: int = 1800) -> dict:
    """
    interval_seconds maps to Samsung's slideshow categories:
      OFF=0, 5s, 1m, 5m, 10m, 30m, 1h, 2h, 4h, 8h, 12h, 24h
    We pick the closest category.
    """
    async with _art_api() as api:
        if enabled:
            await api.set_slideshow_status("on", duration=interval_seconds)
        else:
            await api.set_slideshow_status("off")
    return {"status": "ok", "slideshow": "on" if enabled else "off", "intervalSeconds": interval_seconds}
