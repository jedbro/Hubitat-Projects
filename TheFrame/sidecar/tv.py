"""
Samsung Frame TV controller.
Wraps samsungtvws for both general TV control and art mode operations.
"""
import logging
import os
import socket
from typing import Optional

import httpx
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
# Pairing
# ---------------------------------------------------------------------------

def is_paired() -> bool:
    """Returns True if a valid pairing token has been saved."""
    path = _token_file_path()
    try:
        return os.path.exists(path) and os.path.getsize(path) > 0
    except OSError:
        return False


def pair() -> dict:
    """
    Trigger a WebSocket connection to the TV which causes the pairing prompt
    to appear on screen. Accept it on the TV — the token is then saved to
    token.txt and reused for all future connections.
    """
    try:
        tv = _make_tv()
        # send_key opens the WebSocket connection which triggers the pairing
        # prompt and saves the auth token to token_file on success.
        tv.send_key("KEY_LEFT")
        return {"status": "paired", "paired": True}
    except Exception as e:
        return {"status": "waiting_for_approval", "paired": False, "detail": str(e),
                "hint": "If the TV showed a pairing prompt, accept it and call /api/tv/pair again."}


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


def get_current_source() -> Optional[str]:
    """
    Query the Samsung REST API (port 8001) for the current input source.
    Returns a friendly name if it matches a configured input, otherwise the raw value.
    """
    cfg = tv_config()
    try:
        resp = httpx.get(f"http://{cfg['host']}:8001/api/v2/", timeout=3)
        resp.raise_for_status()
        data = resp.json()
        raw = data.get("device", {}).get("InputSource") or \
              data.get("device", {}).get("inputSource")
        if not raw:
            return None
        # Try to map back to a friendly name from config
        mapping = input_map()
        for friendly, key in mapping.items():
            if raw.lower() in (friendly.lower(), key.lower()):
                return friendly
        return raw
    except Exception as e:
        logger.debug(f"Could not get input source: {e}")
        return None


# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------

def get_state() -> dict:
    paired = is_paired()
    power = is_reachable()
    art_mode = None
    is_watching = False
    current_source = None

    if power:
        art_mode = get_art_mode()
        if art_mode is None:
            # Can't determine art mode (likely not paired yet)
            art_mode = "unknown"
            is_watching = False   # safe default — don't assume watching
        else:
            is_watching = art_mode == "off"
        current_source = get_current_source()

    return {
        "power": "on" if power else "off",
        "paired": paired,
        "artMode": art_mode if art_mode else "unknown",
        "isWatching": is_watching,
        "currentSource": current_source,
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
