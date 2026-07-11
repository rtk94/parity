"""Push sender abstraction.

A transport-agnostic seam between the notification service and the
actual push provider. Today the only real implementation is FCM (see
ADR-0001), but keeping the interface small means a future self-hosted
fork could swap in UnifiedPush, and tests can inject a fake.

Nothing here touches the database or Flask; the notification service
resolves recipients and device tokens and hands a ready ``PushMessage``
to whichever sender is configured.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

logger = logging.getLogger(__name__)


@dataclass
class PushMessage:
    """A single notification bound for one or more device tokens."""

    tokens: list[str]
    title: str
    body: str
    # Data payload for client-side deep-linking; all values are strings
    # (an FCM requirement).
    data: dict[str, str] = field(default_factory=dict)


@runtime_checkable
class PushSender(Protocol):
    def send(self, message: PushMessage) -> None: ...


class NullPushSender:
    """No-op sender used when push is not configured (dev/test)."""

    def send(self, message: PushMessage) -> None:
        logger.debug(
            "push disabled; would send %r to %d token(s)", message.title, len(message.tokens)
        )


class FcmPushSender:
    """Sends via Firebase Cloud Messaging (HTTP v1) using firebase-admin.

    ``firebase-admin`` is imported lazily so the dependency is only
    touched when push is actually configured — dev and test runs never
    import it.
    """

    def __init__(self, credentials_file: str) -> None:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(credentials_file)
        # A named app avoids clashing with any default firebase app and
        # keeps re-initialisation (e.g. a second create_app in one
        # process) from raising.
        try:
            self._app = firebase_admin.get_app("parity-fcm")
        except ValueError:
            self._app = firebase_admin.initialize_app(cred, name="parity-fcm")

    def send(self, message: PushMessage) -> None:
        if not message.tokens:
            return
        from firebase_admin import messaging

        multicast = messaging.MulticastMessage(
            tokens=message.tokens,
            notification=messaging.Notification(title=message.title, body=message.body),
            data=message.data,
        )
        response = messaging.send_each_for_multicast(multicast, app=self._app)
        if response.failure_count:
            # Best-effort: log and move on. Pruning tokens that come back
            # UNREGISTERED is a worthwhile future enhancement.
            logger.warning(
                "FCM multicast: %d/%d failed",
                response.failure_count,
                len(message.tokens),
            )


def build_push_sender(config: Any) -> PushSender:
    """Return the configured sender, or a no-op one when unconfigured."""
    credentials_file = config.get("FCM_CREDENTIALS_FILE")
    if not credentials_file:
        return NullPushSender()
    try:
        return FcmPushSender(credentials_file)
    except Exception:
        # A misconfigured credentials file must not stop the app booting;
        # degrade to no push rather than crash.
        logger.exception("failed to initialise FCM sender; push disabled")
        return NullPushSender()
