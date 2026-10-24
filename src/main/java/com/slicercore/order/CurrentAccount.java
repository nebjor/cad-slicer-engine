package com.slicercore.order;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is placing the order, if anyone is logged in. A port like {@link PaymentGateway}: the
 * order module asks, the account module answers from the session cookie, and the order module
 * never learns how sessions work.
 */
public interface CurrentAccount {

    /** The account id behind the request's session cookie; empty for guests and stale cookies. */
    Optional<UUID> idFor(HttpServletRequest request);

    /** The account id behind the request's session cookie; a missing or stale session answers 401 not-logged-in. */
    UUID require(HttpServletRequest request);
}
