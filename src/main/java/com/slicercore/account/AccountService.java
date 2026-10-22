package com.slicercore.account;

import com.slicercore.order.OrderProperties;
import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Accounts and their sessions. An account comes into being when a login link is used for the
 * first time; the click proves the address, nothing else is asked. A customer may then set a
 * password as a second way in; the link keeps working and is the recovery path. Orders belong
 * to an account by e-mail address, so orders placed as a guest before the account existed are
 * listed too.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    /** A session is extended at most once a day, see {@link AccountSession#extend}. */
    static final Duration SESSION_REFRESH_AFTER = Duration.ofDays(1);

    private final AccountRepository accounts;
    private final LoginTokenRepository tokens;
    private final AccountSessionRepository sessions;
    private final OrderRepository orders;
    private final OrderProperties orderSettings;
    private final AccountProperties props;
    private final PasswordHasher passwords;
    private final PasswordLoginRateLimiter passwordLimiter;
    private final Clock clock;

    public AccountService(AccountRepository accounts, LoginTokenRepository tokens, AccountSessionRepository sessions,
                          OrderRepository orders, OrderProperties orderSettings, AccountProperties props,
                          PasswordHasher passwords, PasswordLoginRateLimiter passwordLimiter, Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.sessions = sessions;
        this.orders = orders;
        this.orderSettings = orderSettings;
        this.props = props;
        this.passwords = passwords;
        this.passwordLimiter = passwordLimiter;
        this.clock = clock;
    }

    /** A freshly opened session: the account and the secret that goes into the cookie. */
    public record OpenedSession(AccountResponse account, String sessionSecret) {
    }

    /** The account behind a valid session cookie and whether its expiry was just pushed out. */
    public record ResolvedSession(UUID accountId, boolean extended) {
    }

    /**
     * Turns a login token into a session. The token is spent even if something later fails,
     * which is what single use means. The first login creates the account and prefills its
     * shipping details from the newest order placed under that address, if there is one.
     *
     * @throws InvalidLoginTokenException for unknown, used or expired tokens
     */
    @Transactional
    public OpenedSession openSession(String token) {
        Instant now = clock.instant();
        LoginToken login = tokens.findByTokenHash(Secrets.hash(token.trim()))
                .filter(found -> found.isUsable(now))
                .orElseThrow(InvalidLoginTokenException::new);
        login.markUsed(now);

        Account account = accounts.findByEmail(login.getEmail()).orElseGet(() -> register(login.getEmail(), now));
        return open(account, now);
    }

    /**
     * Opens a session for an e-mail address and its password. Every attempt counts against
     * the password-login quota, and an unknown address, an account without a password, a
     * wrong password and an exhausted quota all fail the same way, so the answer tells an
     * outsider nothing about the address.
     *
     * @throws InvalidCredentialsException when the login is refused, for whatever reason
     */
    @Transactional
    public OpenedSession openSessionWithPassword(String email, String password, String clientKey) {
        String address = LoginLinkService.normalize(email);
        if (!passwordLimiter.allow(address, clientKey)) {
            log.info("Password login for {} by {} refused: over the limit", address, clientKey);
            throw new InvalidCredentialsException();
        }
        Account account = accounts.findByEmail(address)
                .filter(found -> passwords.matches(password, found.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        return open(account, clock.instant());
    }

    private OpenedSession open(Account account, Instant now) {
        account.recordLogin(now);
        String secret = Secrets.random();
        sessions.save(AccountSession.open(account, Secrets.hash(secret), now, props.sessionLength()));
        log.info("Session opened for account {}", account.getId());
        return new OpenedSession(AccountResponse.from(account), secret);
    }

    private Account register(String email, Instant now) {
        Account account = Account.create(email, now);
        orders.findFirstByCustomerEmailIgnoreCaseAndErasedAtIsNullOrderByCreatedAtDesc(email)
                .ifPresent(order -> account.updateDetails(new Account.Details(order.getCustomerName(),
                        order.getStreet(), order.getPostalCode(), order.getCity(), order.getCountry())));
        log.info("Account {} created for a first login", account.getId());
        return accounts.save(account);
    }

    /**
     * The session behind a cookie secret, if it exists and has not expired. Seeing it touches
     * {@code last_seen_at} and extends the expiry when the last extension is a day or older.
     */
    @Transactional
    public Optional<ResolvedSession> resolve(String sessionSecret) {
        Instant now = clock.instant();
        return sessions.findByTokenHash(Secrets.hash(sessionSecret))
                .filter(session -> !session.isExpired(now))
                .map(session -> {
                    session.touch(now);
                    boolean extended = session.extend(now, props.sessionLength(), SESSION_REFRESH_AFTER);
                    return new ResolvedSession(session.getAccount().getId(), extended);
                });
    }

    @Transactional(readOnly = true)
    public AccountResponse account(UUID accountId) {
        return AccountResponse.from(require(accountId));
    }

    @Transactional
    public AccountResponse update(UUID accountId, Account.Details details) {
        Account account = require(accountId);
        account.updateDetails(details);
        return AccountResponse.from(account);
    }

    /** Sets or replaces the password. Other sessions stay open; the link login keeps working. */
    @Transactional
    public void setPassword(UUID accountId, String password) {
        Account account = require(accountId);
        account.setPasswordHash(passwords.hash(password));
        log.info("Password set for account {}", accountId);
    }

    /** Removes the password: the account is link-only again. Nothing happens if there was none. */
    @Transactional
    public void removePassword(UUID accountId) {
        Account account = require(accountId);
        account.clearPassword();
        log.info("Password removed from account {}", accountId);
    }

    /** Every order under the account's e-mail or linked to the account, newest first, erased ones excluded. */
    @Transactional(readOnly = true)
    public List<OrderResponse> orders(UUID accountId) {
        Account account = require(accountId);
        return orders.findCustomerOrders(account.getEmail(), account.getId()).stream()
                .map(order -> OrderResponse.from(order, orderSettings.vatEnabled()))
                .toList();
    }

    /** Logs one browser out. Unknown secrets are ignored: the result is the same. */
    @Transactional
    public void closeSession(String sessionSecret) {
        sessions.deleteByTokenHash(Secrets.hash(sessionSecret));
    }

    /**
     * Deletes the account with its sessions and pending login links. Orders are business
     * records with their own retention and stay; the database drops their link to the account.
     */
    @Transactional
    public void deleteAccount(UUID accountId) {
        Account account = require(accountId);
        sessions.deleteByAccount(account);
        tokens.deleteByEmail(account.getEmail());
        accounts.delete(account);
        log.info("Account {} deleted on request", accountId);
    }

    /** E-mail per account id, for the owner's order list; unknown ids are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, String> emailsById(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getEmail));
    }

    private Account require(UUID accountId) {
        // The session outlived the account only in a race with a deletion; treat it as logged out.
        return accounts.findById(accountId).orElseThrow(NotLoggedInException::new);
    }
}
