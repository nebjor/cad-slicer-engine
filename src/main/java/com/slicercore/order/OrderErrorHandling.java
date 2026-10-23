package com.slicercore.order;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.quote.RateLimitExceededException;
import com.slicercore.slicer.SlicerBusyException;
import com.slicercore.slicer.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Placing an order recomputes the quote, so the quote failures can surface here too. They are
 * mapped exactly as on /api/quotes so the frontend handles both endpoints alike.
 */
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(OrderErrorHandling.class);

    private final RateLimitProperties rateLimitProperties;

    public OrderErrorHandling(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @ExceptionHandler(SlicingException.class)
    public ProblemDetail handleSlicingFailure(SlicingException e) {
        log.warn("Order rejected, slicing failed: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        return retryLater(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), e.retryAfterSeconds());
    }

    @ExceptionHandler(SlicerBusyException.class)
    public ResponseEntity<ProblemDetail> handleBusy(SlicerBusyException e) {
        return retryLater(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e.retryAfterSeconds());
    }

    private ResponseEntity<ProblemDetail> retryLater(HttpStatus status, String detail, long retryAfterSeconds) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        String supportEmail = rateLimitProperties.supportEmail();
        if (supportEmail != null && !supportEmail.isBlank()) {
            problem.setProperty("supportEmail", supportEmail);
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(problem);
    }
}
