package com.slicercore.cart;

import com.slicercore.model.UploadedModel;
import com.slicercore.order.CurrentAccount;
import com.slicercore.order.OrderModelExpiredException;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in customer's cart, shared by every device (ADR 0010). All endpoints need the
 * {@code pod_session} cookie; without a live session they answer 401 {@code not-logged-in}
 * like the other account endpoints.
 *
 * GET    /api/account/cart              the cart
 * PUT    /api/account/cart              the whole cart in display order; 200 with the stored cart
 * POST   /api/account/cart/{id}/model   re-registers the line's file in the upload store; 200 with the new model id
 * DELETE /api/account/cart              empties the cart; 204
 */
@RestController
@RequestMapping("/api/account/cart")
@Tag(name = "Account cart", description = "The signed-in customer's cart, stored on the server")
public class AccountCartController {

    private final AccountCartService carts;
    private final CurrentAccount currentAccount;

    public AccountCartController(AccountCartService carts, CurrentAccount currentAccount) {
        this.carts = carts;
        this.currentAccount = currentAccount;
    }

    @GetMapping
    public CartResponse cart(HttpServletRequest request) {
        return carts.cart(accountId(request));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CartResponse replace(@Valid @RequestBody CartRequest body, HttpServletRequest request) {
        return carts.replace(accountId(request), body.items());
    }

    @PostMapping("/{id}/model")
    public UploadedModel registerModel(@PathVariable UUID id, HttpServletRequest request) {
        return carts.registerModel(accountId(request), id);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(HttpServletRequest request) {
        carts.clear(accountId(request));
        return ResponseEntity.noContent().build();
    }

    private UUID accountId(HttpServletRequest request) {
        return currentAccount.idFor(request).orElseThrow(CartNotLoggedInException::new);
    }

    @ExceptionHandler(CartNotLoggedInException.class)
    public ProblemDetail handleNotLoggedIn(CartNotLoggedInException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setProperty("code", "not-logged-in");
        return problem;
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    public ProblemDetail handleItemNotFound(CartItemNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setProperty("code", "cart-item-not-found");
        return problem;
    }

    @ExceptionHandler(OrderModelExpiredException.class)
    public ProblemDetail handleModelExpired(OrderModelExpiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setProperty("code", "model-expired");
        problem.setProperty("itemIndex", e.itemIndex());
        return problem;
    }

    @ExceptionHandler(BindException.class)
    public ProblemDetail handleValidation(BindException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Please check the cart lines.");
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

    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleStorageFailure(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The cart could not be stored. Please try again.");
    }
}
