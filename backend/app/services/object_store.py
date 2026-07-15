"""Object-store abstraction for attachment bytes.

A transport-agnostic seam mirroring ``push_sender`` and ``email_sender``
(ADR-0003). ``S3ObjectStore`` (boto3) is used when a bucket is
configured and speaks the S3 API, so the same code targets AWS S3,
Cloudflare R2, or OCI Object Storage via an ``endpoint_url``. Without a
bucket, a filesystem-backed ``LocalObjectStore`` is used — dev and tests
need no cloud and never import boto3.

Nothing here touches the database or Flask; callers pass raw bytes and an
opaque key.
"""

from __future__ import annotations

import contextlib
import logging
import os
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

logger = logging.getLogger(__name__)


class ObjectNotFound(Exception):
    """Raised by ``get``/``delete`` when the key is absent."""


@runtime_checkable
class ObjectStore(Protocol):
    def put(self, key: str, data: bytes, content_type: str) -> None: ...
    def get(self, key: str) -> bytes: ...
    def delete(self, key: str) -> None: ...


class LocalObjectStore:
    """Filesystem-backed store used when no bucket is configured.

    Keys may contain ``/`` (they become subdirectories). Paths are
    resolved under ``base_dir`` and validated to stay inside it.
    """

    def __init__(self, base_dir: str) -> None:
        self._base = Path(base_dir).resolve()
        self._base.mkdir(parents=True, exist_ok=True)

    def _path_for(self, key: str) -> Path:
        path = (self._base / key).resolve()
        if self._base not in path.parents and path != self._base:
            raise ValueError("object key escapes the storage root")
        return path

    def put(self, key: str, data: bytes, content_type: str) -> None:  # noqa: ARG002
        path = self._path_for(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def get(self, key: str) -> bytes:
        try:
            return self._path_for(key).read_bytes()
        except FileNotFoundError as exc:
            raise ObjectNotFound(key) from exc

    def delete(self, key: str) -> None:
        with contextlib.suppress(FileNotFoundError):
            os.remove(self._path_for(key))


class S3ObjectStore:
    """S3-compatible object store (boto3), lazily importing the client."""

    def __init__(
        self,
        *,
        bucket: str,
        endpoint_url: str | None,
        region: str | None,
        access_key: str | None,
        secret_key: str | None,
    ) -> None:
        import boto3

        self._bucket = bucket
        self._client = boto3.client(
            "s3",
            endpoint_url=endpoint_url,
            region_name=region,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key,
        )

    def put(self, key: str, data: bytes, content_type: str) -> None:
        self._client.put_object(Bucket=self._bucket, Key=key, Body=data, ContentType=content_type)

    def get(self, key: str) -> bytes:
        from botocore.exceptions import ClientError

        try:
            resp = self._client.get_object(Bucket=self._bucket, Key=key)
            return resp["Body"].read()
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code")
            if code in {"NoSuchKey", "404"}:
                raise ObjectNotFound(key) from exc
            raise

    def delete(self, key: str) -> None:
        self._client.delete_object(Bucket=self._bucket, Key=key)


def build_object_store(config: Any, *, default_local_dir: str) -> ObjectStore:
    """Return the configured store, or a filesystem one when unconfigured.

    A configured-but-broken S3 client degrades to local with a logged
    error rather than stopping the app from booting.
    """
    bucket = config.get("ATTACHMENT_S3_BUCKET")
    local_dir = config.get("ATTACHMENT_LOCAL_DIR") or default_local_dir
    if not bucket:
        return LocalObjectStore(local_dir)
    try:
        return S3ObjectStore(
            bucket=bucket,
            endpoint_url=config.get("ATTACHMENT_S3_ENDPOINT_URL"),
            region=config.get("ATTACHMENT_S3_REGION"),
            access_key=config.get("ATTACHMENT_S3_ACCESS_KEY"),
            secret_key=config.get("ATTACHMENT_S3_SECRET_KEY"),
        )
    except Exception:
        logger.exception("failed to initialise S3 object store; falling back to local disk")
        return LocalObjectStore(local_dir)
