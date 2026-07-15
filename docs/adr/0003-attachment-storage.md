# ADR-0003: Attachment storage

**Status:** Accepted
**Date:** 2026-07-14
**Deciders:** Repository owner (@rtk94)

## Context

Issue #12 adds receipt/supporting-file attachments to expenses. An
attachment is two things: **metadata** (filename, content type, size,
checksum, who uploaded it, which expense) and the **file bytes**. The
metadata is small, relational, and belongs in the DB next to the expense
it documents. This ADR decides where the **bytes** live.

[VISION.md](../VISION.md) §4 already flags datastore scaling (the
SQLite→Postgres path) and a real backup/DR story as first-class concerns
for the hosted product, and §5 prefers standard cloud dependencies for
the mainline (FCM and Postgres are the cited examples). Binary blobs
interact badly with both: they bloat the primary datastore, slow its
backups, and are exactly what object storage exists to hold.

## Decision

Store attachment **bytes in S3-compatible object storage**; keep
**metadata in a DB `attachment` table** that references the object by an
opaque storage key.

The object-storage transport is a small **config-gated seam**, mirroring
the push (ADR-0001) and email (ADR-0002) senders:

- An `ObjectStore` protocol (`put` / `get` / `delete`) in
  `app/services/object_store.py`.
- `S3ObjectStore` (boto3, lazily imported) used when
  `ATTACHMENT_S3_BUCKET` is set. `boto3` speaks the S3 API, so the same
  code targets AWS S3, Cloudflare R2, or **OCI Object Storage** (the
  production box is on OCI) via an `endpoint_url` — the specific provider
  is deployment config, not code.
- `LocalObjectStore` (filesystem under `instance/attachments/`) as the
  default when no bucket is configured, so dev and tests need no cloud
  and no boto3 import. A misconfigured/uninstallable S3 client degrades
  to local with a logged error rather than failing to boot (as the push
  sender degrades to no-op).

Metadata table `attachment`: `expense_id`, `uploaded_by_user_id`,
`filename`, `content_type`, `size_bytes`, `checksum_sha256`,
`storage_key` (opaque UUID), `created_at`. Uploads are validated against
a content-type allowlist (common image types + PDF) and a size cap
(`ATTACHMENT_MAX_BYTES`, default 10 MiB).

Authorization follows the existing rule: only a **party** to the
expense's relationship may upload, list, or download an attachment
(non-parties get 404, not 403). Deletion is restricted to the
**uploader**. Attachments are supplementary evidence, not ledger rows,
so — like comments — they may be added to confirmed entries and deleted
without violating ledger immutability.

## Consequences

- Production gains one object-storage dependency and one bucket to
  provision, back up (or trust the provider's durability for), and set a
  lifecycle/retention policy on. Until `ATTACHMENT_S3_BUCKET` is set,
  attachments transparently use local disk — fine for dev and a
  single-box deployment, and it keeps the feature working before the
  bucket exists.
- The primary datastore stays free of blobs, preserving the
  SQLite→Postgres migration path.
- Two stores now need consistency: an orphaned object (DB insert failed
  after upload) or a dangling row (object delete failed) is possible.
  We keep it simple — best-effort compensation (delete the object if the
  insert fails) and tolerate rare orphans, which a future reconcile
  sweep can garbage-collect. We do **not** add a two-phase commit.
- Attachment bytes are personal data held on the users' behalf: covered
  by the same custodianship stance as the ledger. Metadata is included
  in the account export; the bytes are downloadable by either party.

## Alternatives considered

- **Local filesystem only.** Simplest, no dependency — but ties
  attachments to one box, must be folded into the backup/DR story
  by hand, and blocks a multi-instance backend. Kept as the
  unconfigured-fallback implementation, not the mainline target.
- **Blob column in the database.** One store, one backup surface, but
  bloats the DB, slows queries and backups, and works directly against
  the Postgres-migration path in VISION §4. Rejected.
