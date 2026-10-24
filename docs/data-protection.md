# Data protection

What personal data the shop holds, why, for how long, and how it leaves again. Written
against the Swiss Federal Act on Data Protection (FADP, in force since 1 September 2023).
The customer-facing summary is the privacy page in the frontend; this document is the
engineering view and must stay consistent with it.

Controller: Joris Neber, Unterwegli 1, 8404 Winterthur, info@3dburg.ch.

## Data held per order

| Field(s) | Why | Lawful basis | Kept until | Removed by |
|---|---|---|---|---|
| Name, e-mail | Confirmation and shipping mails, the order page under the customer's account | Contract (FADP art. 31 para. 2 lit. a) | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request |
| Street, postal code, city, country | Delivery | Contract | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request |
| Uploaded model file (STL/3MF), one per order item | Quote and production; the customer's design | Contract | 90 days after shipping, or when the order is abandoned | Retention sweep or deletion request; every item's file is deleted before the personal data |
| File hash, size, original name, per item | Integrity check and the admin download name | Contract | with the order record | Not erased (no personal data) |
| Order number, items with configuration and line prices, order totals, timestamps, status | Business record, accounting | Legal obligation (CO art. 958f: 10 years) | 10 years | Manual, after the statutory period |
| Payment provider, session id | Match the provider's confirmation to the order | Contract | with the order record | Not erased (opaque id) |
| Client IP address | Rate limiting of the quote, login-link and password-login endpoints | Legitimate interest (abuse prevention) | In memory only, 1 hour window | Expires, never written to disk |
| Account id on the order (`account_id`) | Lists the order under the customer account it was placed with | Contract | With the personal data of the order, or until the account is deleted | Erasure clears it; account deletion sets it null |
| Slice calibration rows and queue (`slice_calibrations`, `calibration_queue`): the model's upload name, hash, size, bounding box, triangle count and slice figures of every quote slice, plus the order number for an order item the owner queued by hand | Checking the pricing constants against real models (ADR 0008); gathered passively from the quote path, never by a scheduled job, the full matrix only on the owner's request | Legitimate interest (correct prices); no personal data, a quote row is linked to no one and the order number is the only link of a queued order item to its order | With the order record; the queue entry references the order's file by its storage key and that file is deleted by the retention sweep as before | Not erased (no personal data); a queued entry whose order was erased is skipped and marked, never sliced |

## Data held per customer account (optional, ADR 0005)

| Field(s) | Why | Lawful basis | Kept until | Removed by |
|---|---|---|---|---|
| E-mail (account identity), created and last login time | Login; the address is the account and receives the registration mail | Contract (the customer opens the account) | Account deletion | `DELETE /api/account`, or on request by mail |
| Name, street, postal code, city, country | Prefill the checkout; saved by the customer or taken from the newest order on first login | Contract | Account deletion | `DELETE /api/account` |
| Password hash (`password_hash`, bcrypt over a SHA-256 pre-hash; null until set) | The primary login, set from the registration mail's link; the mail link stays as the recovery path | Contract (the customer opts in) | Until the customer removes it or deletes the account | `DELETE /api/account/password`, `DELETE /api/account` |
| Login token: e-mail, SHA-256 of the token, client address, timestamps | One-time link in the registration mail (sign-in plus password page); the client address for abuse investigations | Contract / legitimate interest | 15 minutes (expiry), purged by the nightly sweep | Nightly sweep (`AccountRetentionSweep`); account deletion |
| Session: SHA-256 of the cookie secret, timestamps | Keep the customer logged in for 30 days | Contract | Expiry (30 days after last extension), purged nightly | Logout, nightly sweep, account deletion |
| Cart lines (`account_cart_items`, ADR 0010): the uploaded model file (STL/3MF) in a private copy on the order-files volume, its name, size and SHA-256, filament, colour, quality, quantity, the quote JSON the browser held, created and last-saved time | Show the same cart on every device the customer signs in on; only for signed-in customers, guests keep the cart in their browser | Contract (the customer's own cart) | 30 days after the cart was last saved (`account.cart-retention-days`); each save restarts the clock | Nightly sweep (`CartRetentionSweep`), `DELETE /api/account/cart`, removing the line in the cart, account deletion (files first, rows cascade) |

Tokens and session secrets exist in clear only in the mail and in the customer's browser;
the database holds hashes, and a password only ever as a bcrypt hash. An account is optional,
and so is its password: ordering needs an account (ADR 0011), but nothing on the quote path is
linked to a person.

No tracking, no analytics, no marketing mails. The browser keeps the
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
- The same nightly run deletes cart lines and their model files that were not saved for 30 days
  (`CartRetentionSweep`, `account.cart-retention-days`); `DELETE /api/account` deletes the cart
  and its files too.
- The same nightly run deletes expired login tokens and expired sessions
  (`AccountRetentionSweep` through the `RetentionSweep` port). A customer deletes the account
  with `DELETE /api/account`: account, sessions and pending tokens go at once; orders stay
  under the rules above and lose their link to the account (`ON DELETE SET NULL`).
- Backups of the database and the volume follow the server's backup retention; a restore
  after an erasure must be followed by a re-run of the sweep.

## Rights of the person

Access, correction and deletion go through info@3dburg.ch. Access is answered from the admin
order view; deletion with the endpoint above. A customer with an account sees and corrects
their own data at `/konto` and deletes the account there; the owner can delete an account on
request directly in the database (`DELETE FROM accounts WHERE email = …`, sessions cascade).
Answer within 30 days (FADP art. 25).
