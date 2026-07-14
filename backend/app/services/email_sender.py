"""Email sender abstraction.

A transport-agnostic seam between the application and outbound email,
mirroring ``push_sender`` (ADR-0001). The only real implementation is a
stdlib SMTP sender, which works against any SMTP relay — SES, Postmark,
a self-managed server — so the specific provider is a deployment-config
concern, not a code one (ADR-0002). With ``MAIL_SERVER`` unset the
sender is a no-op, so dev, tests, and any unconfigured instance behave
safely.

Nothing here touches the database or Flask; the caller builds a ready
``EmailMessage`` and hands it to whichever sender is configured.
"""

from __future__ import annotations

import logging
import smtplib
from dataclasses import dataclass
from email.message import EmailMessage as MimeEmailMessage
from typing import Any, Protocol, runtime_checkable

logger = logging.getLogger(__name__)


@dataclass
class EmailMessage:
    """A single plain-text email bound for one recipient."""

    to: str
    subject: str
    body: str


@runtime_checkable
class EmailSender(Protocol):
    def send(self, message: EmailMessage) -> None: ...


class NullEmailSender:
    """No-op sender used when email is not configured (dev/test)."""

    def send(self, message: EmailMessage) -> None:
        logger.debug("email disabled; would send %r to %s", message.subject, message.to)


class SmtpEmailSender:
    """Sends plain-text email over SMTP using the standard library."""

    def __init__(
        self,
        *,
        host: str,
        port: int,
        username: str | None,
        password: str | None,
        use_tls: bool,
        from_addr: str,
    ) -> None:
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._use_tls = use_tls
        self._from_addr = from_addr

    def send(self, message: EmailMessage) -> None:
        mime = MimeEmailMessage()
        mime["From"] = self._from_addr
        mime["To"] = message.to
        mime["Subject"] = message.subject
        mime.set_content(message.body)
        try:
            with smtplib.SMTP(self._host, self._port, timeout=10) as smtp:
                if self._use_tls:
                    smtp.starttls()
                if self._username:
                    smtp.login(self._username, self._password or "")
                smtp.send_message(mime)
        except Exception:
            # Best-effort, like the push sender: a delivery failure must
            # not surface to the caller (and must not leak whether the
            # address exists). Log and move on.
            logger.exception("failed to send email to %s", message.to)


def build_email_sender(config: Any) -> EmailSender:
    """Return the configured sender, or a no-op one when unconfigured."""
    host = config.get("MAIL_SERVER")
    if not host:
        return NullEmailSender()
    try:
        return SmtpEmailSender(
            host=host,
            port=int(config.get("MAIL_PORT", 587)),
            username=config.get("MAIL_USERNAME"),
            password=config.get("MAIL_PASSWORD"),
            use_tls=bool(config.get("MAIL_USE_TLS", True)),
            from_addr=config.get("MAIL_FROM", "no-reply@parity.local"),
        )
    except Exception:
        logger.exception("failed to initialise SMTP sender; email disabled")
        return NullEmailSender()
