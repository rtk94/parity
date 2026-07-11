# Parity Documentation

Welcome to the official documentation for **Parity**, a self-hosted, two-party expense and payment tracking ledger designed around an immutable ledger and explicit two-party confirmation.

## Overview

Parity is designed for fair and transparent financial tracking between two parties (e.g., roommates, partners). It features a robust backend built with Python (Flask) and a modern mobile client built with Kotlin (Jetpack Compose). 

## Core Principles

1. **Immutability:** Confirmed entries are never edited or deleted; corrections are made via reversing entries.
2. **Two-Party Confirmation:** Entries remain pending and do not affect the balance until confirmed by the counterparty.
3. **Currency:** Each relationship has a fixed, immutable currency code set at invite time.
4. **Financial Integrity:** Money is stored and handled as integer cents to avoid floating-point errors.

## Documentation Index

- [Getting Started](GETTING_STARTED.md): Setup instructions for the backend and Android client.
- [Architecture](ARCHITECTURE.md): Detailed overview of the system architecture, data models, and Android app structure.
- [API Reference](API_REFERENCE.md): Documentation of the RESTful API endpoints.
- [Deployment](DEPLOYMENT.md): How prod and staging run on the VPS (native gunicorn + nginx + certbot), and how to deploy.
- [Architecture Decision Records](adr/): Records of significant technical decisions and their trade-offs.
  - [ADR-0001: Push notification transport](adr/0001-push-notification-transport.md) (Proposed)
