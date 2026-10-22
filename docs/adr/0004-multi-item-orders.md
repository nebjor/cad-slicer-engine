# ADR 0004 — Orders with several items, placed by model id

**Status:** Accepted
**Date:** 2026-10-21
**Builds on:** ADR 0003 (synchronous quotes, local order files).

## Context

An order held exactly one model. A customer who wanted two different parts placed two
orders, paid shipping twice and received two parcels, and the owner handled two tickets. The
checkout also re-sent the model file as multipart, although since the upload-once change
(`POST /api/models`) the backend already keeps every quoted model for an hour under its
content hash and quotes it by that id.

Pricing is per quoted model: a setup fee of CHF 5.00 once per model, a unit price from the
slice of the whole quantity, and flat shipping of CHF 9.00. Nothing in that formula depends
on other models in the same order; the only shared cost is shipping.

## Decision

1. **An order has one to ten items.** `orders` keeps the customer, the state and the
   order-level money; a new `order_items` table holds, per item, the file, the print
   parameters, the slice figures and the line price. Existing single-model orders become one
   item at position 1 (`V5__order_items.sql`), and the per-model columns leave `orders`.
2. **Orders are placed by model id, as JSON.** `POST /api/orders` takes the customer, a list
   of `{modelId, fileName, filament, color, quality, quantity}` and the expected total. Each
   item is quoted again server-side through the same path as `POST /api/quotes` with a
   `modelId`, which is a cache hit when the customer already saw that quote. The file is then
   copied from the short-lived upload store into the order-files storage under an opaque key,
   as before.
3. **One slice per item, setup fee and shipping per order** (the setup fee moved from item to
   order in [ADR 0009](0009-setup-fee-per-order.md)). Items are not arranged on
   shared plates: every item is its own model and its own slice, so a line total is
   `unitPrice * quantity` (originally `setupFee + unitPrice * quantity`), the items total is
   the sum of the lines, and the order total adds the setup fee and shipping once. Nothing in the quote path changes.
4. **Failures leave no trace.** Every item is quoted and the items total compared with the
   customer's `expectedTotalChf` (the sum of the quotes they saw, shipping excluded) before
   any file is copied. An unknown or expired model answers 404 with `code: "model-expired"`
   and the 0-based `itemIndex`, so the client re-uploads that one model and retries; a
   changed price answers 409 with `code: "price-changed"`, `currentQuotes` (one per item, in
   request order) and the recomputed `totals`.
5. **Payment, mails, admin and erasure follow the items.** Stripe Checkout shows one line
   item per order item plus "Versand (Pauschale)"; the mails list every item; the admin
   downloads a model per item (`/items/{position}/model`); erasure deletes every item's file
   before it removes the personal data.

## Alternatives considered

- **Keep one model per order and let the frontend place several orders.** Rejected: shipping
  would be charged per model or need a refund step, and the customer would pay several times.
- **Arrange all items of an order on shared plates for one slice.** Rejected for now: the
  quote path would need to know about orders, the per-item quotes the customer saw would no
  longer be what is charged, and the saving is machine time the owner can realise manually in
  Bambu Studio anyway.
- **Keep the multipart order endpoint and send every file again.** Rejected: the models are
  already on the server, and a multipart list of files with per-file fields is awkward to
  validate and to document in OpenAPI.

## Consequences

**Benefits:** one parcel and one payment per customer visit; a JSON contract the frontend
types are generated from; the quote path, the model store and pricing are untouched.

**Trade-offs:** an order needs all its models to still be in the upload store (one hour since
the last upload); the frontend must handle `model-expired` per item. Each cache-missed item
counts against the client's quote rate limit, so a ten-item order of never-quoted models can
hit `429`; in practice every item was quoted on the upload page first. Orders placed before
this change carry no colour swatch and no material price on their item (`color.hex` and
`color.pricePerKgChf` are null).

## Revisit when

- Customers regularly order the same part in several colours; a per-colour quantity on one
  item would then be cheaper than separate slices.
- Shipping stops being a flat rate; it would then depend on the items' weight and size.
