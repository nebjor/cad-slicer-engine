package com.slicercore.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

/** The one Stripe call the gateway makes, behind an interface so tests need no network. */
@FunctionalInterface
interface CheckoutSessions {

    Session create(SessionCreateParams params) throws StripeException;
}
