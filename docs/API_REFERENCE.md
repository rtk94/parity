# API Reference

Parity exposes a RESTful API over HTTPS. All endpoints (except auth/login/register) require a `Bearer` token. Timestamps are UTC ISO 8601. Currency amounts are always integers representing cents.

## Authentication
- `POST /api/v1/auth/register`: Register a new user.
- `POST /api/v1/auth/login`: Authenticate and receive a token.
- `POST /api/v1/auth/logout`: Revoke current token.

## Relationships
- `GET /api/v1/relationships`: List all relationships.
- `POST /api/v1/relationships`: Invite a user to form a relationship.
- `POST /api/v1/relationships/<id>/accept`: Accept a pending relationship invite.
- `POST /api/v1/relationships/<id>/reject`: Reject a pending invite.
- `GET /api/v1/relationships/<id>/balance`: Get confirmed and projected balances.

## Ledger (Expenses & Payments)
- `GET /api/v1/expenses`: List expenses.
- `POST /api/v1/expenses`: Create an expense (supports optional `category`).
- `POST /api/v1/expenses/<id>/confirm`: Confirm a pending expense.
- `POST /api/v1/expenses/<id>/discard`: Discard a pending expense.
- `POST /api/v1/expenses/<id>/reverse`: Reverse a confirmed expense.

- `GET /api/v1/payments`: List payments.
- `POST /api/v1/payments`: Create a payment.
- `POST /api/v1/payments/<id>/confirm`: Confirm a pending payment.
- `POST /api/v1/payments/<id>/discard`: Discard a pending payment.
- `POST /api/v1/payments/<id>/reverse`: Reverse a confirmed payment.

## Comments
- `GET /api/v1/expenses/<id>/comments`: List comments on an expense.
- `POST /api/v1/expenses/<id>/comments`: Post a comment on an expense.
- `GET /api/v1/payments/<id>/comments`: List comments on a payment.
- `POST /api/v1/payments/<id>/comments`: Post a comment on a payment.
