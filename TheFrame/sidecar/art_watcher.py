"""
Persistent WebSocket listener for Samsung Frame TV art mode state.

Maintains a long-lived connection to the com.samsung.art-app channel
(wss://:8002) and caches the latest artMode value. This mirrors the
approach used by DaveGut's Hubitat driver, which proved the channel
works on 2022 Frame TV firmware where samsungtvws request/response
polling hangs indefinitely.

The watcher:
  - Connects on startup (retries until the TV is reachable)
  - Sends get_artmode_status once per connection to prime the cache
  - Listens passively for pushed artmode_status / art_mode_changed events
  - Exposes a send queue so callers can fire set_artmode_status without
    blocking the event loop
  - Reconnects automatically on any disconnect or error
"""
import asyncio
import base64
import json
import logging
import ssl
from typing import Optional

logger = logging.getLogger(__name__)

# Cached art mode state — updated whenever the TV pushes an event.
_state: dict = {"art_mode": None}  # "on", "off", or None (unknown)

# Queue for outbound art commands (set_artmode_status etc.)
_send_queue: Optional[asyncio.Queue] = None

_APP_NAME_B64 = base64.b64encode(b"TheFrame Sidecar").decode()


# ---------------------------------------------------------------------------
# Public API (synchronous — safe to call from FastAPI sync routes)
# ---------------------------------------------------------------------------

def get_art_mode() -> Optional[str]:
    """Return the last known art mode ("on"/"off"), or None if not yet received."""
    return _state["art_mode"]


def queue_set_art_mode(value: str) -> bool:
    """
    Enqueue a set_artmode_status command to be sent via the live connection.
    Returns True if queued, False if the watcher is not yet connected.
    """
    if _send_queue is None:
        return False
    try:
        from config import tv_config
        uuid = _fetch_tv_uuid(tv_config().get("host", ""))
        data: dict = {"request": "set_artmode_status", "value": value}
        if uuid:
            data["id"] = uuid
        _send_queue.put_nowait(data)
        return True
    except asyncio.QueueFull:
        return False


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _art_channel_msg(data: dict) -> str:
    return json.dumps({
        "method": "ms.channel.emit",
        "params": {
            "data": json.dumps(data),
            "to": "host",
            "event": "art_app_request",
        },
    })


def _fetch_tv_uuid(host: str) -> Optional[str]:
    """Fetch the TV's device UUID from the REST API (port 8001)."""
    try:
        import httpx
        resp = httpx.get(f"http://{host}:8001/api/v2/", timeout=3)
        resp.raise_for_status()
        uid = resp.json().get("device", {}).get("duid") or resp.json().get("id")
        return uid
    except Exception:
        return None


async def _reader(ws) -> None:
    """Read frames from the WebSocket and update the cache."""
    async for raw in ws:
        try:
            msg = json.loads(raw)
            event = msg.get("event")
            if event == "d2d_service_message":
                inner = json.loads(msg.get("data", "{}"))
                if inner.get("event") in ("artmode_status", "art_mode_changed"):
                    status = inner.get("value") or inner.get("status")
                    if status in ("on", "off"):
                        _state["art_mode"] = status
                        logger.info(f"Art watcher: artMode → {status}")
        except Exception as e:
            logger.debug(f"Art watcher: parse error: {e}")


async def _writer(ws, queue: asyncio.Queue) -> None:
    """Forward queued commands to the WebSocket."""
    while True:
        data = await queue.get()
        try:
            await ws.send(_art_channel_msg(data))
        except Exception as e:
            logger.warning(f"Art watcher: send error: {e}")
            raise


async def _connect_and_watch(host: str, token: str, queue: asyncio.Queue) -> None:
    import websockets  # bundled with samsungtvws[async]

    ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE

    url = (
        f"wss://{host}:8002/api/v2/channels/com.samsung.art-app"
        f"?name={_APP_NAME_B64}&token={token}"
    )
    logger.info(f"Art watcher: connecting to {host}")

    # Fetch UUID for art requests (DaveGut's driver includes this as "id" —
    # the TV ignores get_artmode_status without it on this firmware).
    uuid = _fetch_tv_uuid(host)
    logger.info(f"Art watcher: TV UUID = {uuid}")

    async with websockets.connect(
        url, ssl=ssl_ctx, ping_interval=None, open_timeout=10
    ) as ws:
        logger.info("Art watcher: connected")

        status_req = {"request": "get_artmode_status"}
        if uuid:
            status_req["id"] = uuid

        async def _poll_until_known():
            """Re-send get_artmode_status every 30s until the TV responds."""
            await asyncio.sleep(1)  # let connection settle
            while _state["art_mode"] is None:
                logger.debug("Art watcher: requesting artmode status")
                try:
                    await ws.send(_art_channel_msg(status_req))
                except Exception:
                    return
                await asyncio.sleep(30)

        reader_task = asyncio.create_task(_reader(ws))
        writer_task = asyncio.create_task(_writer(ws, queue))
        poll_task = asyncio.create_task(_poll_until_known())
        try:
            done, pending = await asyncio.wait(
                {reader_task, writer_task, poll_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass
            # Re-raise any exception from a finished task
            for task in done:
                task.result()
        finally:
            reader_task.cancel()
            writer_task.cancel()


# ---------------------------------------------------------------------------
# Entry point — run as an asyncio background task
# ---------------------------------------------------------------------------

async def watch_forever() -> None:
    """Reconnecting loop. Start this with asyncio.create_task()."""
    global _send_queue
    _send_queue = asyncio.Queue(maxsize=20)

    from config import tv_config
    from tv import _token_file_path

    while True:
        try:
            cfg = tv_config()
            host = cfg.get("host")
            if not host:
                logger.warning("Art watcher: no host configured, retrying in 60s")
                await asyncio.sleep(60)
                continue

            try:
                with open(_token_file_path()) as f:
                    token = f.read().strip()
            except OSError:
                logger.warning("Art watcher: no token file yet, retrying in 30s")
                await asyncio.sleep(30)
                continue

            await _connect_and_watch(host, token, _send_queue)
            logger.info("Art watcher: connection closed, reconnecting in 15s")
            _state["art_mode"] = None
            await asyncio.sleep(15)

        except OSError:
            # TV unreachable (off or network issue) — back off longer
            logger.debug("Art watcher: TV unreachable, retrying in 60s")
            _state["art_mode"] = None
            await asyncio.sleep(60)
        except Exception as e:
            logger.warning(f"Art watcher: unexpected error ({e}), retrying in 30s")
            _state["art_mode"] = None
            await asyncio.sleep(30)
