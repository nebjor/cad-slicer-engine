# ADR 0011 — Accounts required to order

**Status:** Accepted (owner decision 2026-10-24)
**Date:** 2026-10-24
**Builds on:** ADR 0005 (customer accounts; rejected "accounts required" at the time), ADR 0009 (setup fee), ADR 0010 (account cart).
**Supersedes:** the guest parts of ADR 0005 and ADR 0009.

## Context

The shop launched with guest checkout and added optional accounts later. The owner now wants
to serve customers with an account only: every order has a person behind a verified e-mail
address, the cart follows the customer across devices (ADR 0010), and there is one place for
everything about an order. Guest checkout, the public lookup by order number and e-mail, and
the CHF 2.00 guest surcharge existed only because accounts were optional.

## Decision

1. **`POST /api/orders` needs a session.** Without a valid `pod_session` cookie it answers
   401 with the problem code `not-logged-in`, the same shape as the account endpoints. The
   order is always linked to the account (`orders.account_id`), though the checkout e-mail
   may still differ from the account's.
2. **Orders are read under the account.** `GET /api/account/orders/{orderNumber}` (session
   required) replaces the public `GET /api/orders/{orderNumber}?email=`, which is removed
   together with its tests. An order that is not the account's, does not exist or is erased
   answers 404 `order-not-found`. "The account's" is the rule of the order list: linked to
   the account, or placed under the account's e-mail address.
3. **One setup fee.** `orders.setup-fee-chf` is CHF 3.00; `orders.setup-fee-account-chf` and
   `OrderProperties.setupFeeFor` are gone, `OrderSettingsResponse` publishes only
   `setupFeeChf`, and `OrderTotals.from` no longer takes a signed-in flag.
4. **Mails link into the account.** The status link of the order mails is
   `{shopUrl}/konto/bestellungen/{orderNumber}`; the login page takes over when nobody is
   signed in and returns to the order afterwards.

## Consequences

- Removed: guest checkout, the public order lookup (and its e-mail-guessing surface), the
  guest fee. A guest fee can no longer be charged, so the setting is gone rather than unused.
- Existing orders are untouched: their stored `setup_fee`, totals and customer data stay as
  paid. Guest orders keep `account_id = null`; the owner still sees them in the admin (the
  account e-mail column is empty for them).
- **A guest who ordered before can register with the same e-mail and sees those orders.**
  The link is made on read, not on registration: `OrderRepository.findCustomerOrders` returns
  orders linked to the account or placed under its e-mail address (case-insensitive), and
  `findCustomerOrder` applies the same rule to one order, loading only the account's own row.
  Matching by e-mail is safe because an account exists only after the customer opened the login
  link mailed to that address, and the order confirmation went to the same inbox: whoever can
  register the address could already read those orders. `account_id` is not backfilled on the
  old rows; every read path uses the e-mail rule, so nothing needs the column, and a backfill
  can be added if a feature ever does.
- Order lookups are rate limited per account and per client address (one minute windows, see
  `account.order-lookups-*`), so order numbers cannot be enumerated quickly; a number that is
  blank or longer than 40 characters is answered 404 without a database query.
- `POST /api/orders` resolves the session before the body is read: an anonymous request gets
  401 `not-logged-in` whatever it sends. The `not-logged-in` problem is one handler
  (`SessionProblems`) for the account, cart and order endpoints.
- A customer must now register before paying. Conversion may drop; the owner accepted this.
  The cart stays usable without an account, so the upload and the quote are still free.
- Contract change: the frontend must ship together with this backend (removed endpoint,
  removed `setupFeeAccountChf`, new 401 on `POST /api/orders`).
