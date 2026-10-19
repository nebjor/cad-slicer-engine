package com.slicercore.payment;

/**
 * A started payment: the provider's session id and the page the customer is sent to.
 *
 * @param provider  which gateway created it, stored on the order
 * @param sessionId what the provider's confirmation (webhook) will refer to
 * @param url       hosted payment page; absolute for a real provider, shop-relative for the mock
 */
public record PaymentSession(String provider, String sessionId, String url) {
}
