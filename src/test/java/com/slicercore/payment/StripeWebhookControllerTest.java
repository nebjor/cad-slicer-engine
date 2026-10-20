package com.slicercore.payment;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.stripe.Stripe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StripeWebhookController.class, properties = "payments.provider=stripe")
@Import({StripeWebhookControllerTest.Config.class, SecurityConfig.class})
class StripeWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }

        @Bean
        StripeProperties stripeProperties() {
            return new StripeProperties("sk_test_unused", SECRET);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PaymentService paymentService;

    @Test
    void aSignedPaidSessionMarksItsOrder() throws Exception {
        String payload = event("checkout.session.completed", "cs_test_1", "paid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());

        verify(paymentService).confirm("cs_test_1");
    }

    @Test
    void anAsyncPaymentThatSettlesLaterMarksItsOrderToo() throws Exception {
        String payload = event("checkout.session.async_payment_succeeded", "cs_test_2", "paid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());

        verify(paymentService).confirm("cs_test_2");
    }

    @Test
    void aTamperedBodyIsRejectedWithoutTouchingAnyOrder() throws Exception {
        String signed = event("checkout.session.completed", "cs_test_1", "paid");
        String tampered = signed.replace("cs_test_1", "cs_test_9");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(signed, SECRET))
                        .content(tampered))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).confirm(anyString());
    }

    @Test
    void aSignatureForAnotherEndpointIsRejected() throws Exception {
        String payload = event("checkout.session.completed", "cs_test_1", "paid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, "whsec_someone_elses"))
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).confirm(anyString());
    }

    @Test
    void aCompletedButUnpaidSessionIsAcknowledgedAndLeftAlone() throws Exception {
        String payload = event("checkout.session.completed", "cs_test_3", "unpaid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());

        verify(paymentService, never()).confirm(anyString());
    }

    @Test
    void unrelatedEventsAreAcknowledgedSoStripeStopsRetrying() throws Exception {
        String payload = event("payment_intent.created", "cs_test_1", "paid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());

        verify(paymentService, never()).confirm(anyString());
    }

    @Test
    void aSessionTheShopNeverStartedIs404() throws Exception {
        when(paymentService.confirm("cs_test_foreign")).thenThrow(new IllegalArgumentException("Unknown payment session."));
        String payload = event("checkout.session.completed", "cs_test_foreign", "paid");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload, SECRET))
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown payment session."));
    }

    /** A minimal event body as Stripe sends it: the Checkout session is the event's data object. */
    private static String event(String type, String sessionId, String paymentStatus) {
        return """
                {"id":"evt_1","object":"event","api_version":"%s","type":"%s",
                 "data":{"object":{"id":"%s","object":"checkout.session","payment_status":"%s"}}}
                """.formatted(Stripe.API_VERSION, type, sessionId, paymentStatus);
    }

    /** Stripe's scheme: HMAC-SHA256 over "timestamp.payload", sent as t=...,v1=... */
    private static String sign(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
    }
}
