package com.slicercore.admin;

import com.slicercore.order.OrderStatus;
import com.slicercore.service.QuoteRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Owner endpoints, all behind the admin login (see SecurityConfig).
 *
 * GET  /api/admin/orders?status=&limit=        newest first
 * GET  /api/admin/orders/{number}
 * POST /api/admin/orders/{number}/status       {"status": "PRINTING"}; 409 when not allowed
 * GET  /api/admin/orders/{number}/model        the uploaded model as a download
 * POST /api/admin/rate-limits/reset?clientKey= clear one client's quote limits, or all
 */
@RestController
@RequestMapping("/api/admin")
public class AdminOrderController {

    private final AdminOrderService orders;
    private final QuoteRateLimiter rateLimiter;

    public AdminOrderController(AdminOrderService orders, QuoteRateLimiter rateLimiter) {
        this.orders = orders;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/orders")
    public List<AdminOrderResponse> list(@RequestParam Optional<OrderStatus> status,
                                         @RequestParam(defaultValue = "100") int limit) {
        return orders.list(status, limit);
    }

    @GetMapping("/orders/{orderNumber}")
    public AdminOrderResponse get(@PathVariable String orderNumber) {
        return orders.get(orderNumber);
    }

    @PostMapping("/orders/{orderNumber}/status")
    public AdminOrderResponse setStatus(@PathVariable String orderNumber, @Valid @RequestBody StatusChange change) {
        return orders.transition(orderNumber, change.status());
    }

    @GetMapping("/orders/{orderNumber}/model")
    public ResponseEntity<InputStreamResource> model(@PathVariable String orderNumber) {
        AdminOrderService.ModelDownload download = orders.model(orderNumber);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.content()));
    }

    @PostMapping("/rate-limits/reset")
    public Map<String, Object> resetRateLimit(@RequestParam(required = false) String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            rateLimiter.resetAll();
            return Map.of("reset", "all");
        }
        return Map.of("reset", clientKey, "hadUsage", rateLimiter.reset(clientKey));
    }

    public record StatusChange(@NotNull OrderStatus status) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalTransition(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleMissingFile(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
