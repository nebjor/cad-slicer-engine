# ADR 0005 — Customer accounts with passwordless e-mail login

**Status:** Accepted
**Date:** 2026-10-21
**Builds on:** ADR 0003 (one server, no infrastructure the shop does not need), ADR 0004 (orders with items).

## Context

The shop sold to guests only: name, e-mail and address per order, the order looked up by
number and e-mail. Returning customers typed their address again and had no list of their
orders; the owner could not tell a first-time buyer from a regular. The roadmap had deferred
accounts until the shop sold, and it does.

Every order already proves an e-mail address: the confirmation goes there, and the status
page is reachable only with number and e-mail. A password would add the one thing the shop
does not have yet — a secret store worth attacking — plus a reset flow, which is itself a
mail link. The shop sends mail already (`notification`, SMTP from the environment).

## Decision

1. **Passwordless.** `POST /api/account/login-links` takes an e-mail and sends a one-time link
   (`/konto/anmelden?token=…`) valid for 15 minutes. `POST /api/account/sessions` exchanges the
   token for a session. There is no password column anywhere, so no hashing policy, no reset,
   no breach of credentials that can be reused elsewhere.
2. **The click creates the account.** An account row appears on the first successful login;
   its shipping details are prefilled from the newest order under that address. Orders belong
   to an account by e-mail address, case-insensitively, so guest orders placed before the
   account existed are listed too; orders placed while logged in additionally carry
   `orders.account_id` (nullable, `ON DELETE SET NULL`). A logged-in customer may order for
   someone else: the checkout e-mail is kept, the link to the account is kept as well.
3. **Secrets are random and stored hashed.** Login tokens and session secrets are 32 random
   bytes (base64url); `login_tokens.token_hash` and `account_sessions.token_hash` hold their
   SHA-256. A database copy yields no usable login. Tokens are single use; a used or expired
   token answers 400 `invalid-token`.
4. **A cookie session, not a bearer token.** The session secret travels in `pod_session`:
   HttpOnly (scripts never see it), Secure (configurable for local HTTP via
   `account.cookie-secure`), SameSite=Lax, Path=/, Max-Age 30 days, extended once a day on
   use. Without a live session the account endpoints answer 401 `not-logged-in`; a stale
   cookie is cleared on the way.
5. **CSRF guard: JSON only plus SameSite=Lax.** Every mutating account endpoint requires
   `Content-Type: application/json` and answers 415 otherwise. An HTML form cannot send JSON,
   and a cross-site `fetch` with a JSON body is a non-simple request that the browser blocks
   without CORS consent — which the API does not grant. SameSite=Lax withholds the cookie
   from cross-site subrequests anyway. No CSRF token needs managing, consistent with the
   stateless admin login in `SecurityConfig`.
6. **No user enumeration.** The link endpoint answers 202 whether or not the address is
   known, a mail went out, or a quota was hit: at most 3 links per address per 15 minutes and
   10 per client address per hour (in memory, as the quote limiter). Over quota, nothing is
   sent and the fact is logged.
7. **Module boundaries hold.** The feature lives in `com.slicercore.account`. The order module
   stays ignorant of sessions: it owns the port `CurrentAccount` (the account module
   implements it from the cookie) and `RetentionSweep` (the account module purges its tables
   in the same nightly run). Mail goes through the `notification` module's new `Mailer`
   port, which also carries the order mails.

## Data retention

- **Login tokens:** purged by the nightly retention run once expired (15 minutes after
  issue, used or not).
- **Sessions:** purged by the same run once expired; logging out deletes the session at once.
- **Accounts:** kept until the customer deletes them (`DELETE /api/account`), which removes
  the account, its sessions and pending tokens. Orders are business records with their own
  retention (ADR 0003, `docs/data-protection.md`) and stay; the database drops their
  `account_id`. Erasing an order's personal data also clears its `account_id`.

## Alternatives considered

- **Passwords with bcrypt and a reset flow.** Rejected: more code, more risk, and the reset
  flow is this design anyway.
- **Third-party login (Google, Apple).** Rejected: a dependency and a privacy page entry for
  a shop with Swiss customers who already gave their e-mail; can be added on top later.
- **Bearer token in `localStorage`.** Rejected: readable by any script on the page; the
  HttpOnly cookie is the safer default and costs only the SameSite/JSON reasoning above.
- **Accounts required for checkout.** Rejected: guest checkout converts; the account is an
  offer, not a gate.

## Consequences

**Benefits:** no credential store; returning customers get their address and order history;
the owner sees `accountEmail` in the admin list. The frontend keeps one contract: the
account's orders are plain `OrderResponse`s.

**Trade-offs:** whoever reads the customer's mailbox can log in — the same trust the order
status link already places in it. Sessions are server rows, so a leaked database backup
contains hashes only, but a customer with many devices has many rows (purged on expiry). The
link base URL is `notifications.shop-url`, so a misconfigured public URL breaks logins
visibly.

## Revisit when

- The owner wants staff accounts: that is a role on top of this login, not a different one.
- Customers ask for passkeys; WebAuthn could replace the mail link while keeping the session.
- A second application instance appears; the in-memory link quota then needs a shared store.
