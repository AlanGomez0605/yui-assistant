import hashlib
import hmac
from typing import Optional

from fastapi import Request, WebSocket

from .config import Settings


SESSION_COOKIE_NAME = "yui_session"


def authentication_configured(settings: Settings) -> bool:
    return bool(settings.API_TOKEN.strip())


def _session_value(settings: Settings) -> str:
    return hmac.new(
        settings.API_TOKEN.encode("utf-8"),
        b"yui-session-v1",
        hashlib.sha256,
    ).hexdigest()


def token_is_valid(candidate: Optional[str], settings: Settings) -> bool:
    expected = settings.API_TOKEN.strip()
    return bool(expected and candidate and hmac.compare_digest(candidate, expected))


def session_is_valid(candidate: Optional[str], settings: Settings) -> bool:
    return bool(
        authentication_configured(settings)
        and candidate
        and hmac.compare_digest(candidate, _session_value(settings))
    )


def request_is_authenticated(request: Request, settings: Settings) -> bool:
    authorization = request.headers.get("authorization", "")
    bearer = authorization[7:].strip() if authorization.lower().startswith("bearer ") else None
    return token_is_valid(bearer, settings) or session_is_valid(
        request.cookies.get(SESSION_COOKIE_NAME), settings
    )


def websocket_is_authenticated(websocket: WebSocket, settings: Settings) -> bool:
    authorization = websocket.headers.get("authorization", "")
    bearer = authorization[7:].strip() if authorization.lower().startswith("bearer ") else None
    return token_is_valid(bearer, settings) or session_is_valid(
        websocket.cookies.get(SESSION_COOKIE_NAME), settings
    )


def create_session_value(settings: Settings) -> str:
    return _session_value(settings)
