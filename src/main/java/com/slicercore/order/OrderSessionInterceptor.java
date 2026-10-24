package com.slicercore.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Resolves the session of {@code POST /api/orders} before the body is read, so an anonymous
 * request gets 401 whatever it sends (ADR 0011) instead of a validation error for a body that
 * would never have been accepted. The account id travels on as a request attribute.
 */
public class OrderSessionInterceptor implements HandlerInterceptor {

    private static final String ACCOUNT_ATTRIBUTE = OrderSessionInterceptor.class.getName() + ".accountId";

    private final Supplier<CurrentAccount> currentAccount;

    public OrderSessionInterceptor(Supplier<CurrentAccount> currentAccount) {
        this.currentAccount = currentAccount;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ACCOUNT_ATTRIBUTE, currentAccount.get().require(request));
        return true;
    }

    /** The account the interceptor resolved for this request. */
    static UUID accountId(HttpServletRequest request) {
        return (UUID) request.getAttribute(ACCOUNT_ATTRIBUTE);
    }
}
