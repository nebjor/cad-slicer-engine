package com.slicercore.order;

import com.slicercore.config.RateLimitProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orders API.
 *
 * POST /api/orders                    multipart: model file plus the checkout form; 201 with the order
 * GET  /api/orders/settings           shipping, VAT and validity the checkout page shows up front
 * GET  /api/orders/{number}?email=    the order, only with the matching customer e-mail
 *
 * 400 carries an {@code errors} map of field to message, 409 carries the current quote when the
 * price changed since it was shown. Quote-related failures (422, 429, 503) are the same as for
 * /api/quotes, because the order recomputes the quote.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Guest checkout: place an order and look it up")
public class OrderController {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final OrderService orderService;
    private final RateLimitProperties rateLimitProperties;

    public OrderController(OrderService orderService, RateLimitProperties rateLimitProperties) {
        this.orderService = orderService;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestPart("file") MultipartFile file,
            @Valid OrderRequest request,
            HttpServletRequest httpRequest) {
        OrderResponse order = orderService.placeOrder(file, request, clientKey(httpRequest));
        return ResponseEntity.created(URI.create("/api/orders/" + order.orderNumber())).body(order);
    }

    @GetMapping("/settings")
    public OrderSettingsResponse settings() {
        return orderService.settings();
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse find(@PathVariable String orderNumber, @RequestParam String email) {
        return orderService.find(orderNumber, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found."));
    }

    private String clientKey(HttpServletRequest request) {
        if (rateLimitProperties.trustForwardedHeaders()) {
            String forwardedFor = request.getHeader(FORWARDED_FOR);
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    @ExceptionHandler(BindException.class)
    public ProblemDetail handleValidation(BindException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Please check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PriceChangedException.class)
    public ProblemDetail handlePriceChanged(PriceChangedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("currentQuote", e.currentQuote());
        return problem;
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleStorageFailure(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The order could not be stored. Please try again.");
    }
}
