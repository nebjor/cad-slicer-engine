# Data protection

What personal data the shop holds, why, for how long, and how it leaves again. Written
against the Swiss Federal Act on Data Protection (FADP, in force since 1 September 2023).
The customer-facing summary is the privacy page in the frontend; this document is the
engineering view and must stay consistent with it.

Controller: Joris Neber, Unterwegli 1, 8404 Winterthur, info@3dburg.ch.

## Data held per order

| Field(s) | Why | Lawful basis | Kept until | Removed by |
|---|---|---|---|---|
| Name, e-mail | Confirmation and shipping mails, order lookup | Contract (FADP art. 31 para. 2 lit. a) | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request |
| Street, postal code, city, country | Delivery | Contract | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request |
| Uploaded model file (STL/3MF), one per order item | Quote and production; the customer's design | Contract | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request; every item's file is deleted before the personal data |
| File hash, size, original name, per item | Integrity check and the admin download name | Contract | with the order record | Not erased (no personal data) |
| Order number, items with configuration and line prices, order totals, timestamps, status | Business record, accounting | Legal obligation (CO art. 958f: 10 years) | 10 years | Manual, after the statutory period |
| Payment provider, session id | Match the provider's confirmation to the order | Contract | with the order record | Not erased (opaque id) |
| Client IP address | Rate limiting of the quote endpoint | Legitimate interest (abuse prevention) | In memory only, 1 hour window | Expires, never written to disk |

No customer accounts, no tracking, no analytics, no marketing mails. The browser keeps the
last order number and e-mail in `localStorage` so the success page can show the order;
that stays on the customer's device. Before an order exists, an uploaded model is kept for
one hour under its content hash so it can be quoted again and ordered by id
(`quote.model-store`); it is not linked to a person and expires on its own.

## Processors

| Who | What they see | Where |
|---|---|---|
| Hetzner (server) | Everything above, encrypted at rest by the volume | Germany / Finland |
| Infomaniak (mail) | Name, e-mail, order mails | Switzerland |
| Stripe (payment, once live) | Amount, order number, what Stripe collects on its own page | EU / USA with standard contractual clauses |
| Swiss Post | Name and address on the parcel | Switzerland |

The backend never sees card data; the payment page is the provider's.

## Retention and deletion

- Shipping an order sets `delete_after = shipped_at + 90 days` (`orders.retention-days`).
- A nightly job (`OrderRetentionJob`, cron `orders.retention-cron`) calls
  `OrderRetentionService.eraseExpired()`: every item's model file is deleted from the
  order-files volume and name, e-mail and address are overwritten; `erased_at` records when.
  If one file cannot be deleted, the personal data waits for the next run so that an order is
  never half erased. The order row and its items with their numbers stay as the business
  record. `OrderRetentionServiceTest` proves it
  with a controllable clock.
- A deletion request is one operation: `POST /api/admin/orders/{number}/erase` (owner login),
  same effect, idempotent. Document the request and the date in the mailbox; the order's
  `erased_at` is the technical proof.
- Orders that never get paid are abandoned: when Stripe reports the Checkout session as
  expired or its payment as failed, the order is cancelled and erased at once
  (`PaymentService.abandon`). As a backstop, the nightly job also cancels and erases every
  order still unpaid, and erases every cancelled order, once its 14-day quote validity has
  passed (`OrderRetentionService.eraseAbandoned`). `OrderRetentionServiceTest` covers both.
- Backups of the database and the volume follow the server's backup retention; a restore
  after an erasure must be followed by a re-run of the sweep.

## Rights of the person

Access, correction and deletion go through info@3dburg.ch. Access is answered from the admin
order view; deletion with the endpoint above. Answer within 30 days (FADP art. 25).
