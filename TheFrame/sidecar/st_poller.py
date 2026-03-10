"""
SmartThings on-demand poller for Samsung Frame TV state.

Fetches device status from the SmartThings cloud API and caches it for
a configurable TTL. Data is only fetched when requested (no background
polling), and the cache is invalidated when art mode changes so the next
get_state() call returns fresh app/input info.

Requires smartthings.token and smartthings.device_id in config.yml.
If not configured, all functions return None/empty gracefully.
"""
import logging
import time
from typing import Optional

import httpx

from config import st_config

logger = logging.getLogger(__name__)

_ST_API = "https://api.smartthings.com/v1"

_cache: dict = {}          # last fetched ST state
_cache_ts: float = 0.0     # epoch time of last fetch
_DEFAULT_TTL = 60          # seconds


def _is_configured() -> bool:
    cfg = st_config()
    return bool(cfg and cfg.get("token") and cfg.get("device_id"))


def invalidate() -> None:
    """Clear the cache so the next call fetches fresh data."""
    global _cache_ts
    _cache_ts = 0.0


def _fetch() -> dict:
    cfg = st_config()
    token = cfg["token"]
    device_id = cfg["device_id"]
    try:
        resp = httpx.get(
            f"{_ST_API}/devices/{device_id}/status",
            headers={"Authorization": f"Bearer {token}"},
            timeout=5,
        )
        resp.raise_for_status()
        main = resp.json().get("components", {}).get("main", {})
        return _parse(main)
    except Exception as e:
        logger.warning(f"SmartThings fetch failed: {e}")
        return {}


def _parse(main: dict) -> dict:
    """Extract the fields we care about from the ST device status."""
    result = {}

    # Current app / channel name (e.g. "org.tizen.netflix-app", "art")
    ch = main.get("tvChannel", {})
    app = ch.get("tvChannelName", {}).get("value")
    if app:
        result["currentApp"] = app

    # Input source from samsungvd.mediaInputSource (more reliable than mediaInputSource)
    src = (
        main.get("samsungvd.mediaInputSource", {})
            .get("inputSource", {})
            .get("value")
    )
    if src:
        result["inputSource"] = src

    # Volume
    vol = main.get("audioVolume", {}).get("volume", {}).get("value")
    if vol is not None:
        result["volume"] = vol

    # Power (ST switch capability)
    sw = main.get("switch", {}).get("switch", {}).get("value")
    if sw:
        result["stPower"] = sw

    return result


def get() -> dict:
    """
    Return cached ST state, fetching fresh data if the cache is stale.
    Returns an empty dict if SmartThings is not configured.
    """
    if not _is_configured():
        return {}

    global _cache, _cache_ts
    cfg = st_config()
    ttl = cfg.get("cache_ttl", _DEFAULT_TTL)

    if time.time() - _cache_ts > ttl:
        _cache = _fetch()
        _cache_ts = time.time()
        logger.debug(f"SmartThings cache refreshed: {_cache}")

    return _cache
