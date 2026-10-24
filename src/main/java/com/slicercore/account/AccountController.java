package com.slicercore.account;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.order.OrderResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer account API, passwordless by default with an optional password (ADR 0005).
 *
 * POST   /api/account/login-links       {"email"}: mails the registration link (login plus password page); 202 whether or not a mail went out
 * POST   /api/account/sessions          {"token"}: opens a session, sets the pod_session cookie; 400 invalid-token
 * POST   /api/account/sessions/password {"email", "password"}: the same session by password; 401 invalid-credentials
 * GET    /api/account                   the account; 401 not-logged-in without a live session
 * PUT    /api/account                   name and shipping address
 * PUT    /api/account/password          {"password"}: sets or replaces the password; 204
 * DELETE /api/account/password          removes the password, the account is link-only again; 204
 * GET    /api/account/orders            the account's orders, newest first
 * GET    /api/account/orders/{number}   one of them; 404 order-not-found when it is not the account's, 429 beyond the lookup quota
 * DELETE /api/account/sessions          logs this browser out and clears the cookie
 * DELETE /api/account                   deletes the account; orders stay under their retention rules
 *
 * Every request with a body must be {@code application/json} (415 otherwise). Together with
 * the cookie's SameSite=Lax that is the CSRF guard: a cross-site form cannot post JSON, and a
 * cross-site script cannot send the cookie.
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "Account", description = "Customer account with passwordless e-mail login and an optional password")
public class AccountController {

    private final LoginLinkService loginLinks;
    private final AccountService accounts;
    private final AccountSessionResolver sessions;
    private final SessionCookies cookies;
    private final RateLimitProperties rateLimitProperties;
    private final OrderLookupRateLimiter orderLookupLimiter;

    public AccountController(LoginLinkService loginLinks, AccountService accounts, AccountSessionResolver sessions,
                             SessionCookies cookies, RateLimitProperties rateLimitProperties,
                             OrderLookupRateLimiter orderLookupLimiter) {
        this.orderLookupLimiter = orderLookupLimiter;
        this.loginLinks = loginLinks;
        this.accounts = accounts;
        this.sessions = sessions;
        this.cookies = cookies;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping(value = "/login-links", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> requestLoginLink(@Valid @RequestBody LoginLinkRequest body, HttpServletRequest request) {
        loginLinks.requestLink(body.email(), rateLimitProperties.clientKey(request));
        return ResponseEntity.accepted().build();
    }

    @PostMapping(value = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> openSession(@Valid @RequestBody SessionRequest body) {
        return opened(accounts.openSession(body.token()));
    }

    @PostMapping(value = "/sessions/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> openSessionWithPassword(@Valid @RequestBody PasswordLoginRequest body,
                                                                   HttpServletRequest request) {
        return opened(accounts.openSessionWithPassword(body.email(), body.password(),
                rateLimitProperties.clientKey(request)));
    }

    private ResponseEntity<AccountResponse> opened(AccountService.OpenedSession opened) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(opened.sessionSecret()).toString())
                .body(opened.account());
    }

    @GetMapping
    public AccountResponse account(HttpServletRequest request, HttpServletResponse response) {
        return accounts.account(sessions.require(request, response));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public AccountResponse update(@Valid @RequestBody AccountUpdateRequest body,
                                  HttpServletRequest request, HttpServletResponse response) {
        return accounts.update(sessions.require(request, response), body.normalized());
    }

    @PutMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> setPassword(@Valid @RequestBody PasswordRequest body,
                                            HttpServletRequest request, HttpServletResponse response) {
        accounts.setPassword(sessions.require(request, response), body.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/password")
    public ResponseEntity<Void> removePassword(HttpServletRequest request, HttpServletResponse response) {
        accounts.removePassword(sessions.require(request, response));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/orders")
    public List<OrderResponse> orders(HttpServletRequest request, HttpServletResponse response) {
        return accounts.orders(sessions.require(request, response));
    }

    @GetMapping("/orders/{orderNumber}")
    public OrderResponse order(@PathVariable String orderNumber, HttpServletRequest request,
                               HttpServletResponse response) {
        UUID accountId = sessions.require(request, response);
        if (!orderLookupLimiter.allow(accountId, rateLimitProperties.clientKey(request))) {
            throw new OrderLookupLimitException();
        }
        return accounts.order(accountId, orderNumber);
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        cookies.read(request).ifPresent(accounts::closeSession);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        UUID accountId = sessions.require(request, response);
        accounts.deleteAccount(accountId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @ExceptionHandler(OrderLookupLimitException.class)
    public ResponseEntity<ProblemDetail> handleOrderLookupLimit(OrderLookupLimitException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("code", "too-many-requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(OrderLookupRateLimiter.WINDOW.toSeconds()))
                .body(problem);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setProperty("code", "order-not-found");
        return problem;
    }

    @ExceptionHandler(InvalidLoginTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidLoginTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("code", "invalid-token");
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setProperty("code", "invalid-credentials");
        return problem;
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
}
