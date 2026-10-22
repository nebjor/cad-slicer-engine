# ADR 0009 — Setup fee once per order, cheaper for signed-in customers

**Status:** Accepted
**Date:** 2026-10-22
**Builds on:** ADR 0004 (multi-item orders; this replaces its "setup fee per item").

## Context

The setup fee ("Einrichtung") covered the work of preparing a print: opening the model,
checking the slice, loading the printer. ADR 0004 charged it per item, so an order of three
parts paid it three times, and every quote carried it inside `totalPriceChf`. In practice the
owner prepares one order in one sitting, and a customer who orders several parts at once was
penalised for it. The owner also wants to reward repeat customers who sign in.

## Decision

1. **The setup fee is charged once per order.** CHF 5.00 for a guest, CHF 3.00 when the order
   is placed by a signed-in customer (`accountId != null` in `OrderService.placeOrder`). Both
   amounts are settings, `orders.setup-fee-chf` and `orders.setup-fee-account-chf`, and are
   published by `GET /api/orders/settings` as `setupFeeChf` and `setupFeeAccountChf` so the
   cart page can show the fee before checkout.
2. **Quotes no longer contain it.** `QuoteResponse.totalPriceChf` is `unitPriceChf * quantity`
   and `setupFeeChf` is gone from the quote and from `quote.*` in the configuration. A quote
   describes one model; the fee depends on the order around it.
3. **Order totals carry it as their own line.** `OrderTotals` gains `setupFee` between
   `itemsTotal` and `shipping`; `total = itemsTotal + setupFee + shipping`, VAT is carved out
   of that total as before. An item's `lineTotal` is `unitPrice * quantity`.
   `orders.setup_fee` stores the fee actually charged, so later changes to the settings do not
   touch placed orders.
4. **`expectedTotalChf` is unchanged in meaning:** the sum of the line totals, setup fee and
   shipping excluded. The fee is a function of the order settings and of whether the customer
   is signed in, not of prices that can drift, so the price-changed guard does not need it.
   A customer who signs in or out between cart and checkout is simply charged the fee of the
   state at placement, which the response shows.
5. **Stripe** gets one extra line item "Einrichtung" (quantity 1) after the item lines and
   before the shipping line, so the Checkout total equals `OrderTotals.total`. The order
   confirmation mail lists the fee between the subtotal and shipping.

## Consequences

- API change for the frontend: `QuoteResponse.setupFeeChf` removed and `totalPriceChf` lower
  by the fee; `OrderSettingsResponse` gains two fields; `OrderResponse.totals` and the admin
  order's `totals` gain `setupFee`; `OrderItemResponse` and `AdminOrderItemResponse` lose
  `setupFeeChf`.
- **Migration of existing orders (`V10__setup_fee_per_order.sql`).** `orders.setup_fee` is
  added and filled with the sum of the order's former per-item fees; `orders.items_total`
  is reduced by that sum, and each `order_items.line_total_chf` becomes unit price times
  quantity; `order_items.setup_fee_chf` is dropped. Every old order keeps exactly the same
  `total` and VAT, but its fee now shows once, as the sum of what was charged (an old
  three-item order shows CHF 15.00 setup fee, as it was paid; it is not recalculated).
- A guest pays CHF 2.00 more than a signed-in customer for the same order, which is the
  intended incentive; the checkout should say so.
- The calibration of ADR 0008 compares print times, not prices, and is unaffected.
