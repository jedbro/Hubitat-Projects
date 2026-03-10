"""
Samsung Frame TV controller.
Wraps samsungtvws for both general TV control and art mode operations.
"""
import logging
import os
import socket
from typing import Optional

import wakeonlan
from samsungtvws import SamsungTVWS, SamsungTVArt

from config import tv_config, input_map

logger = logging.getLogger(__name__)


def _token_file_path() -> str:
    cfg = tv_config()
    token_file = cfg.get("token_file", "token.txt")
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


def _make_art() -> SamsungTVArt:
    cfg = tv_config()
    return SamsungTVArt(
        host=cfg["host"],
        port=cfg.get("port", 8002),
        token_file=_token_file_path(),
    )


# ---------------------------------------------------------------------------
# Power
# ---------------------------------------------------------------------------

def is_reachable() -> bool:
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
        return {"error": f"unknown input '{name}'", "available": list(mapping.keys())}
    tv = _make_tv()
    tv.send_key(key)
    return {"status": "ok", "input": name, "key": key}


# ---------------------------------------------------------------------------
# Art Mode
# ---------------------------------------------------------------------------

def art_mode_on() -> dict:
    art = _make_art()
    art.set_artmode("on")
    return {"status": "ok", "artMode": "on"}


def art_mode_off() -> dict:
    art = _make_art()
    art.set_artmode("off")
    return {"status": "ok", "artMode": "off"}


def get_art_mode() -> Optional[str]:
    try:
        art = _make_art()
        return art.get_artmode()
    except Exception as e:
        logger.warning(f"Could not get art mode: {e}")
        return None


# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------

def get_state() -> dict:
    power = is_reachable()
    art_mode = None
    is_watching = False

    if power:
        art_mode = get_art_mode()
        is_watching = art_mode == "off"

    return {
        "power": "on" if power else "off",
        "artMode": art_mode or "unknown",
        "isWatching": is_watching,
    }


# ---------------------------------------------------------------------------
# Art operations
# ---------------------------------------------------------------------------

def list_art() -> dict:
    art = _make_art()
    items = art.available()
    return {"items": items}


def get_current_art() -> dict:
    art = _make_art()
    return art.get_current()


def select_art(content_id: str) -> dict:
    art = _make_art()
    art.select_image(content_id)
    return {"status": "ok", "contentId": content_id}


def upload_art(image_bytes: bytes, file_type: str = "JPEG") -> dict:
    art = _make_art()
    result = art.upload(image_bytes, file_type=file_type)
    return {"status": "ok", "result": result}


def set_slideshow(enabled: bool, interval_seconds: int = 1800) -> dict:
    art = _make_art()
    if enabled:
        art.set_slideshow_status("on", duration=interval_seconds)
    else:
        art.set_slideshow_status("off")
    return {"status": "ok", "slideshow": "on" if enabled else "off", "intervalSeconds": interval_seconds}
