package com.slicercore.account;

import com.slicercore.order.OrderRepository;
import com.slicercore.order.OrderResponse;
import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The login token, the optional password and the session are the whole security model of an
 * account, so their lifecycle is pinned here: one use, a short life, hashes in the database,
 * sliding expiry, one answer for every failed password login.
 */
class AccountServiceTest {

    private static final String TOKEN = "login-secret-from-the-mail";
    private static final String COOKIE = "session-secret-from-the-cookie";
    private static final String EMAIL = "anna@example.com";
    private static final String PASSWORD = "correct horse battery";
    private static final String CLIENT = "203.0.113.7";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final LoginTokenRepository tokens = mock(LoginTokenRepository.class);
    private final AccountSessionRepository sessions = mock(AccountSessionRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-21T10:00:00Z");
    private final AccountProperties props = new AccountProperties(true, 15, 30, 3, 10, 5, 20);
    /** Real bcrypt at the cheapest strength: the tests prove the hash, not its cost. */
    private final PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    private final AccountService service = new AccountService(accounts, tokens, sessions, orders, TestOrders.SETTINGS,
            props, hasher, new PasswordLoginRateLimiter(props, clock), clock);

    @BeforeEach
    void repositoriesEchoWhatTheySave() {
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstLoginCreatesTheAccountPrefilledFromTheNewestOrderAndSpendsTheToken() {
        LoginToken token = issue(TOKEN);
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(orders.findFirstByCustomerEmailIgnoreCaseAndErasedAtIsNullOrderByCreatedAtDesc(EMAIL))
                .thenReturn(Optional.of(TestOrders.benchyOrder()));
        clock.advance(Duration.ofMinutes(14));

        AccountService.OpenedSession opened = service.openSession(" " + TOKEN + " ");

        AccountResponse account = opened.account();
        assertThat(account.email()).isEqualTo(EMAIL);
        assertThat(account.name()).isEqualTo("Anna Muster");
        assertThat(account.street()).isEqualTo("Bahnhofstrasse 1");
        assertThat(account.postalCode()).isEqualTo("8001");
        assertThat(account.city()).isEqualTo("Zürich");
        assertThat(account.country()).isEqualTo("CH");
        assertThat(account.createdAt()).isEqualTo(clock.instant());
        assertThat(account.lastLoginAt()).isEqualTo(clock.instant());
        assertThat(account.hasPassword()).isFalse();
        assertThat(token.getUsedAt()).isEqualTo(clock.instant());

        ArgumentCaptor<AccountSession> saved = ArgumentCaptor.forClass(AccountSession.class);
        verify(sessions).save(saved.capture());
        assertThat(opened.sessionSecret()).matches("[A-Za-z0-9_-]{43}");
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(Secrets.hash(opened.sessionSecret()))
                .hasSize(64)
                .isNotEqualTo(opened.sessionSecret());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(saved.getValue().getAccount().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void aFirstLoginWithoutEarlierOrdersLeavesTheAddressEmpty() {
        issue(TOKEN);
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(orders.findFirstByCustomerEmailIgnoreCaseAndErasedAtIsNullOrderByCreatedAtDesc(EMAIL))
                .thenReturn(Optional.empty());

        AccountResponse account = service.openSession(TOKEN).account();

        assertThat(account.name()).isNull();
        assertThat(account.street()).isNull();
        assertThat(account.country()).isNull();
    }

    @Test
    void aReturningCustomerKeepsTheAccountAndOnlyTheLoginTimeMoves() {
        issue(TOKEN);
        Account existing = Account.create(EMAIL, Instant.parse("2026-01-01T00:00:00Z"));
        existing.updateDetails(new Account.Details("Anna Muster", "Seeweg 3", "8001", "Zürich", "CH"));
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        clock.advance(Duration.ofMinutes(1));

        AccountResponse account = service.openSession(TOKEN).account();

        assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(account.lastLoginAt()).isEqualTo(clock.instant());
        assertThat(account.street()).isEqualTo("Seeweg 3");
        verify(accounts, never()).save(any());
        verifyNoInteractions(orders);
    }

    @Test
    void aTokenWorksOnlyOnce() {
        issue(TOKEN);
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(Account.create(EMAIL, clock.instant())));
        service.openSession(TOKEN);

        assertThatThrownBy(() -> service.openSession(TOKEN)).isInstanceOf(InvalidLoginTokenException.class);
        verify(sessions).save(any());
    }

    @Test
    void anExpiredTokenIsRefused() {
        issue(TOKEN);
        clock.advance(Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.openSession(TOKEN)).isInstanceOf(InvalidLoginTokenException.class);
        verify(sessions, never()).save(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void anUnknownTokenIsRefused() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openSession("made-up")).isInstanceOf(InvalidLoginTokenException.class);
    }

    @Test
    void aLiveSessionResolvesToItsAccountAndRecordsTheVisit() {
        Account account = Account.create(EMAIL, clock.instant());
        AccountSession session = openSession(account);
        clock.advance(Duration.ofHours(2));

        Optional<AccountService.ResolvedSession> resolved = service.resolve(COOKIE);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().accountId()).isEqualTo(account.getId());
        assertThat(resolved.get().extended()).isFalse();
        assertThat(session.getLastSeenAt()).isEqualTo(clock.instant());
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().minus(Duration.ofHours(2)).plus(Duration.ofDays(30)));
    }

    @Test
    void aSessionIsExtendedAtMostOnceADay() {
        AccountSession session = openSession(Account.create(EMAIL, clock.instant()));

        clock.advance(Duration.ofHours(25));
        assertThat(service.resolve(COOKIE).orElseThrow().extended()).isTrue();
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));

        clock.advance(Duration.ofHours(1));
        assertThat(service.resolve(COOKIE).orElseThrow().extended()).isFalse();
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().minus(Duration.ofHours(1)).plus(Duration.ofDays(30)));
    }

    @Test
    void anExpiredOrUnknownSessionIsNoSession() {
        openSession(Account.create(EMAIL, clock.instant()));
        when(sessions.findByTokenHash(Secrets.hash("other"))).thenReturn(Optional.empty());

        clock.advance(Duration.ofDays(30));
        assertThat(service.resolve(COOKIE)).isEmpty();
        assertThat(service.resolve("other")).isEmpty();
    }

    @Test
    void ordersAreListedUnderTheAccountsEmailAndItsId() {
        Account account = known(Account.create(EMAIL, clock.instant()));
        when(orders.findCustomerOrders(EMAIL, account.getId()))
                .thenReturn(List.of(TestOrders.twoItemOrder(), TestOrders.benchyOrder()));

        List<OrderResponse> listed = service.orders(account.getId());

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).items()).hasSize(2);
        assertThat(listed.get(0).vatNote()).contains("not VAT registered");
    }

    @Test
    void updatingStoresTheDetails() {
        Account account = known(Account.create(EMAIL, clock.instant()));

        AccountResponse updated = service.update(account.getId(),
                new Account.Details("Anna Muster", "Seeweg 3", "8400", "Winterthur", "CH"));

        assertThat(updated.street()).isEqualTo("Seeweg 3");
        assertThat(account.getCity()).isEqualTo("Winterthur");
    }

    @Test
    void settingAPasswordStoresABcryptHashOfItAndShowsInTheResponse() {
        Account account = known(Account.create(EMAIL, clock.instant()));

        service.setPassword(account.getId(), PASSWORD);

        assertThat(account.hasPassword()).isTrue();
        assertThat(account.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(hasher.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(service.account(account.getId()).hasPassword()).isTrue();
    }

    @Test
    void settingAPasswordAgainReplacesTheOldOne() {
        Account account = known(Account.create(EMAIL, clock.instant()));
        service.setPassword(account.getId(), PASSWORD);
        String first = account.getPasswordHash();
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        service.setPassword(account.getId(), "a different passphrase");

        assertThat(account.getPasswordHash()).isNotEqualTo(first);
        assertThatThrownBy(() -> service.openSessionWithPassword(EMAIL, PASSWORD, CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(service.openSessionWithPassword(EMAIL, "a different passphrase", CLIENT).account().hasPassword()).isTrue();
    }

    @Test
    void removingThePasswordMakesTheAccountLinkOnlyAgain() {
        Account account = known(Account.create(EMAIL, clock.instant()));
        service.setPassword(account.getId(), PASSWORD);
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        service.removePassword(account.getId());

        assertThat(account.hasPassword()).isFalse();
        assertThat(account.getPasswordHash()).isNull();
        assertThat(service.account(account.getId()).hasPassword()).isFalse();
        assertThatThrownBy(() -> service.openSessionWithPassword(EMAIL, PASSWORD, CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);
        // Removing it twice is not an error.
        service.removePassword(account.getId());
    }

    @Test
    void aPasswordLoginOpensTheSameKindOfSessionAsTheLink() {
        Account account = Account.create(EMAIL, Instant.parse("2026-01-01T00:00:00Z"));
        account.setPasswordHash(hasher.hash(PASSWORD));
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        clock.advance(Duration.ofMinutes(3));

        AccountService.OpenedSession opened = service.openSessionWithPassword(" Anna@Example.com ", PASSWORD, CLIENT);

        assertThat(opened.account().email()).isEqualTo(EMAIL);
        assertThat(opened.account().hasPassword()).isTrue();
        assertThat(opened.account().lastLoginAt()).isEqualTo(clock.instant());
        assertThat(account.getLastLoginAt()).isEqualTo(clock.instant());
        ArgumentCaptor<AccountSession> saved = ArgumentCaptor.forClass(AccountSession.class);
        verify(sessions).save(saved.capture());
        assertThat(opened.sessionSecret()).matches("[A-Za-z0-9_-]{43}");
        assertThat(saved.getValue().getTokenHash()).isEqualTo(Secrets.hash(opened.sessionSecret()));
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        verifyNoInteractions(tokens);
    }

    @Test
    void aWrongPasswordAMissingPasswordAndAnUnknownAddressFailAlike() {
        Account withPassword = Account.create(EMAIL, clock.instant());
        withPassword.setPasswordHash(hasher.hash(PASSWORD));
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(withPassword));
        when(accounts.findByEmail("link-only@example.com"))
                .thenReturn(Optional.of(Account.create("link-only@example.com", clock.instant())));
        when(accounts.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openSessionWithPassword(EMAIL, "not the password", CLIENT))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("The e-mail address or password is wrong.");
        assertThatThrownBy(() -> service.openSessionWithPassword("link-only@example.com", PASSWORD, CLIENT))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("The e-mail address or password is wrong.");
        assertThatThrownBy(() -> service.openSessionWithPassword("nobody@example.com", PASSWORD, CLIENT))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("The e-mail address or password is wrong.");
        verify(sessions, never()).save(any());
        assertThat(withPassword.getLastLoginAt()).isEqualTo(Instant.parse("2026-10-21T10:00:00Z"));
    }

    @Test
    void afterFiveAttemptsInFifteenMinutesEvenTheRightPasswordIsRefused() {
        Account account = Account.create(EMAIL, clock.instant());
        account.setPasswordHash(hasher.hash(PASSWORD));
        when(accounts.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.openSessionWithPassword(EMAIL, "guess", CLIENT))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThatThrownBy(() -> service.openSessionWithPassword(EMAIL, PASSWORD, CLIENT))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(sessions, never()).save(any());

        clock.advance(Duration.ofMinutes(15));
        assertThat(service.openSessionWithPassword(EMAIL, PASSWORD, CLIENT).account().email()).isEqualTo(EMAIL);
    }

    @Test
    void deletingTheAccountRemovesSessionsAndTokensButTouchesNoOrder() {
        Account account = known(Account.create(EMAIL, clock.instant()));

        service.deleteAccount(account.getId());

        verify(sessions).deleteByAccount(account);
        verify(tokens).deleteByEmail(EMAIL);
        verify(accounts).delete(account);
        verifyNoInteractions(orders);
    }

    @Test
    void loggingOutDeletesTheSessionBehindTheCookie() {
        service.closeSession(COOKIE);

        verify(sessions).deleteByTokenHash(Secrets.hash(COOKIE));
    }

    @Test
    void aSessionWhoseAccountIsGoneCountsAsLoggedOut() {
        UUID gone = UUID.randomUUID();
        when(accounts.findById(gone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.account(gone)).isInstanceOf(NotLoggedInException.class);
        assertThatThrownBy(() -> service.orders(gone)).isInstanceOf(NotLoggedInException.class);
    }

    @Test
    void emailsByIdServesTheAdminListInOneLookup() {
        Account account = Account.create(EMAIL, clock.instant());
        when(accounts.findAllById(Set.of(account.getId()))).thenReturn(List.of(account));

        assertThat(service.emailsById(Set.of(account.getId()))).isEqualTo(Map.of(account.getId(), EMAIL));
        assertThat(service.emailsById(Set.of())).isEmpty();
        verify(accounts, never()).findAllById(Set.of());
    }

    private LoginToken issue(String secret) {
        LoginToken token = LoginToken.issue(EMAIL, Secrets.hash(secret), clock.instant(), props.loginLinkValidity(), "203.0.113.7");
        when(tokens.findByTokenHash(Secrets.hash(secret))).thenReturn(Optional.of(token));
        return token;
    }

    private AccountSession openSession(Account account) {
        AccountSession session = AccountSession.open(account, Secrets.hash(COOKIE), clock.instant(), props.sessionLength());
        when(sessions.findByTokenHash(Secrets.hash(COOKIE))).thenReturn(Optional.of(session));
        return session;
    }

    private Account known(Account account) {
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }
}
