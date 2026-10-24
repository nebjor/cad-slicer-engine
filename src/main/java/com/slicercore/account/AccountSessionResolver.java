package com.slicercore.account;

import com.slicercore.order.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the session cookie and turns it into the logged-in account. Given a response, it also
 * keeps the cookie in step with the session: re-sent when the session was extended, cleared
 * when the cookie no longer matches a live session. Implements the order module's
 * {@link CurrentAccount} port so a checkout can link the order to the account.
 */
@Component
public class AccountSessionResolver implements CurrentAccount {

    private final AccountService accounts;
    private final SessionCookies cookies;

    public AccountSessionResolver(AccountService accounts, SessionCookies cookies) {
        this.accounts = accounts;
        this.cookies = cookies;
    }

    /** The logged-in account, or empty for guests and stale cookies. */
    public Optional<UUID> resolve(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> secret = cookies.read(request);
        if (secret.isEmpty()) {
            return Optional.empty();
        }
        Optional<AccountService.ResolvedSession> session = accounts.resolve(secret.get());
        if (response != null) {
            if (session.isEmpty()) {
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
            } else if (session.get().extended()) {
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(secret.get()).toString());
            }
        }
        return session.map(AccountService.ResolvedSession::accountId);
    }

    /** @throws NotLoggedInException when no live session came with the request */
    public UUID require(HttpServletRequest request, HttpServletResponse response) {
        return resolve(request, response).orElseThrow(NotLoggedInException::new);
    }

    @Override
    public UUID require(HttpServletRequest request) {
        return require(request, null);
    }

    @Override
    public Optional<UUID> idFor(HttpServletRequest request) {
        return resolve(request, null);
    }
}
