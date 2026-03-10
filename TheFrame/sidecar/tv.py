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
from samsungtvws import SamsungTVWS

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
    Trigger a WebSocket connection to the remote control channel to save the
    auth token. Accept any on-screen TV prompt, then call again to confirm.
    Art mode is handled by the background watcher (no separate pairing needed).
    """
    try:
        t = _make_tv()
        t.send_key("KEY_LEFT")
        return {"status": "paired", "paired": True}
    except Exception as e:
        return {
            "status": "waiting_for_approval",
            "paired": False,
            "error": str(e),
            "hint": "Accept any prompt shown on the TV, then call /api/tv/pair again.",
        }


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
    import art_watcher
    art_watcher.queue_set_art_mode("on")
    return {"status": "ok", "artMode": "on"}


def art_mode_off() -> dict:
    import art_watcher
    art_watcher.queue_set_art_mode("off")
    return {"status": "ok", "artMode": "off"}


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
    import art_watcher
    import st_poller
    paired = is_paired()
    power = is_reachable()
    art_mode = None
    is_watching = False
    current_source = None
    current_app = None

    if power:
        art_mode = art_watcher.get_art_mode()
        if art_mode is None:
            is_watching = False  # safe default until watcher receives first event
        else:
            is_watching = art_mode == "off"
        current_source = get_current_source()

        st = st_poller.get()
        if st:
            current_app = st.get("currentApp")
            if not current_source:
                current_source = st.get("inputSource")
            # Use ST currentApp as fallback for artMode when watcher hasn't
            # received a response yet (e.g. right after startup).
            if art_mode is None and current_app is not None:
                art_mode = "on" if current_app == "art" else "off"
                is_watching = art_mode == "off"

    return {
        "power": "on" if power else "off",
        "paired": paired,
        "artMode": art_mode if art_mode is not None else "unknown",
        "isWatching": is_watching,
        "currentSource": current_source,
        "currentApp": current_app,
    }


# ---------------------------------------------------------------------------
# Art operations
# ---------------------------------------------------------------------------
# NOTE: list/select/upload/slideshow use the samsungtvws art channel which
# does not respond on this TV's firmware (22_PONTUSM_FTV). These endpoints
# return a clear error rather than hanging.

_ART_OPS_UNSUPPORTED = {
    "error": "Art content operations are not supported on this TV's firmware",
    "detail": "The art channel WebSocket does not respond to commands on 22_PONTUSM_FTV",
}


def list_art() -> dict:
    return _ART_OPS_UNSUPPORTED


def get_current_art() -> dict:
    return _ART_OPS_UNSUPPORTED


def select_art(content_id: str) -> dict:
    return _ART_OPS_UNSUPPORTED


def upload_art(image_bytes: bytes, file_type: str = "JPEG") -> dict:
    return _ART_OPS_UNSUPPORTED


def set_slideshow(enabled: bool, interval_seconds: int = 1800) -> dict:
    return _ART_OPS_UNSUPPORTED
