package com.slicercore.payment;

import com.slicercore.order.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Confirmation endpoint of the mock gateway: the shop's own payment page calls it when the
 * tester presses "pay". Only present while {@code payments.provider=mock}; with a real
 * provider nothing can mark an order paid except the signed webhook.
 */
@RestController
@RequestMapping("/api/payments/mock")
@ConditionalOnProperty(name = "payments.provider", havingValue = PaymentProperties.MOCK, matchIfMissing = true)
public class MockPaymentController {

    private final PaymentService paymentService;

    public MockPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{sessionId}/complete")
    public Map<String, String> complete(@PathVariable String sessionId) {
        Order order = paymentService.confirm(sessionId);
        return Map.of("orderNumber", order.getOrderNumber(), "status", order.getStatus().name());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleUnknownSession(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
