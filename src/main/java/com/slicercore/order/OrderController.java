package com.slicercore.order;

import com.slicercore.config.RateLimitProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orders API.
 *
 * POST /api/orders                    JSON: customer, items by modelId, expected total; 201 with the order.
 *                                     A logged-in customer's session cookie links the order to the account.
 * GET  /api/orders/settings           shipping, VAT and validity the checkout page shows up front
 * GET  /api/orders/{number}?email=    the order, only with the matching customer e-mail
 *
 * 400 carries an {@code errors} map of field path (such as {@code items[0].quantity}) to message.
 * 404 with {@code code: "model-expired"} and {@code itemIndex} names the item whose model must be
 * uploaded again. 409 with {@code code: "price-changed"} carries {@code currentQuotes} (one per
 * item, in request order) and the recomputed {@code totals} when the items total differs from
 * what was shown. Quote-related failures (422, 429, 503) are the same as for /api/quotes,
 * because the order recomputes every quote.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Checkout, as guest or logged in: place an order and look it up")
public class OrderController {


    private final OrderService orderService;
    private final RateLimitProperties rateLimitProperties;
    private final CurrentAccount currentAccount;

    public OrderController(OrderService orderService, RateLimitProperties rateLimitProperties,
                           CurrentAccount currentAccount) {
        this.orderService = orderService;
        this.rateLimitProperties = rateLimitProperties;
        this.currentAccount = currentAccount;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody OrderRequest request,
                                                    HttpServletRequest httpRequest) {
        OrderResponse order = orderService.placeOrder(request, rateLimitProperties.clientKey(httpRequest),
                currentAccount.idFor(httpRequest).orElse(null));
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body could not be read.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OrderModelExpiredException.class)
    public ProblemDetail handleModelExpired(OrderModelExpiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setProperty("code", "model-expired");
        problem.setProperty("itemIndex", e.itemIndex());
        return problem;
    }

    @ExceptionHandler(PriceChangedException.class)
    public ProblemDetail handlePriceChanged(PriceChangedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", "price-changed");
        problem.setProperty("currentQuotes", e.currentQuotes());
        problem.setProperty("totals", e.currentTotals());
        return problem;
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleStorageFailure(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The order could not be stored. Please try again.");
    }
}
