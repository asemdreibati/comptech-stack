# Fake payment service provider

A [WireMock](https://wiremock.org) stand-in for a Stripe-style PSP, used by `docker compose` and
the end-to-end journey. It speaks the two calls the order service makes:

| Call | Answer |
|---|---|
| `POST /v1/payment_intents` | `200` `succeeded`, or `402 card_declined` for the payment method `pm_card_chargeDeclined` (Stripe's own test token) |
| `POST /v1/refunds` | `200` `succeeded` |
| no `Bearer sk_test_…` key | `401` |

Payment and refund IDs are derived from the `Idempotency-Key`, so repeating a request returns the
same ID, as a real PSP does. The order service's tests script slow, failing and declining PSP
answers in-process; this fake only serves the happy and declined paths.
