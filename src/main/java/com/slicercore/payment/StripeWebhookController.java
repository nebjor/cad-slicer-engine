package com.slicercore.payment;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe's confirmation channel. The only way an order becomes {@code PAID} with the real
 * provider: the body must carry a valid signature for this endpoint's secret, otherwise it is
 * rejected with 400 and nothing changes. A paid Checkout session marks its order, an expired
 * or failed one cancels and erases it; anything else is acknowledged and ignored, so Stripe
 * does not keep retrying events the shop has no use for.
 */
@RestController
@RequestMapping("/api/payments/stripe")
@Hidden
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.STRIPE)
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private static final String SESSION_COMPLETED = "checkout.session.completed";
    private static final String ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";
    private static final String SESSION_EXPIRED = "checkout.session.expired";
    private static final String ASYNC_PAYMENT_FAILED = "checkout.session.async_payment_failed";

    private final StripeProperties properties;
    private final PaymentService paymentService;

    public StripeWebhookController(StripeProperties properties, PaymentService paymentService) {
        this.properties = properties;
        this.paymentService = paymentService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(@RequestBody String payload,
                                        @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, properties.webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Rejected Stripe webhook with an invalid signature: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        switch (event.getType()) {
            case SESSION_COMPLETED, ASYNC_PAYMENT_SUCCEEDED -> {
                Session session = session(event);
                if ("paid".equals(session.getPaymentStatus())) {
                    paymentService.confirm(session.getId());
                } else {
                    log.info("Stripe session {} is {} after {}; waiting for the payment to settle",
                            session.getId(), session.getPaymentStatus(), event.getType());
                }
            }
            case SESSION_EXPIRED, ASYNC_PAYMENT_FAILED -> paymentService.abandon(session(event).getId());
            default -> log.debug("Ignoring Stripe event {} of type {}", event.getId(), event.getType());
        }
        return ResponseEntity.ok().build();
    }

    /** Falls back to the version-tolerant parser when the event's API version differs from the SDK's. */
    private static Session session(Event event) {
        try {
            return (Session) event.getDataObjectDeserializer().getObject()
                    .orElse(event.getDataObjectDeserializer().deserializeUnsafe());
        } catch (EventDataObjectDeserializationException e) {
            throw new IllegalStateException("Cannot read the Checkout session of event " + event.getId(), e);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleUnknownSession(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
