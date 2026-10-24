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
  The link is made on read, not on registration: `OrderRepository.findCustomerOrders`
  returns orders linked to the account or placed under its e-mail address
  (case-insensitive), and `AccountService.order` applies the same rule to a single order.
  Registration proves the address by mail, so the match is safe. Nothing writes
  `account_id` on those old rows; that is a known gap, harmless today because every read
  path uses the e-mail rule, and would only matter if a feature needed the column itself.
  It is not built here.
- A customer must now register before paying. Conversion may drop; the owner accepted this.
  The cart stays usable without an account, so the upload and the quote are still free.
- Contract change: the frontend must ship together with this backend (removed endpoint,
  removed `setupFeeAccountChf`, new 401 on `POST /api/orders`).
