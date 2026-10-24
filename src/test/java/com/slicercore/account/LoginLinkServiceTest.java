package com.slicercore.account;

import com.slicercore.notification.Mailer;
import com.slicercore.notification.NotificationProperties;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** A login link is a secret in a mail; the database must hold only its hash, and quotas must stay silent. */
class LoginLinkServiceTest {

    private static final NotificationProperties MAIL =
            new NotificationProperties(true, "info@3dburg.ch", "", "3DBurg", "https://3dburg.ch");

    private final LoginTokenRepository tokens = mock(LoginTokenRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-21T10:00:00Z");
    private final AccountProperties props = new AccountProperties(true, 15, 30, 3, 10, 5, 20, 30, 60);
    private final List<String[]> sent = new ArrayList<>();
    private final Mailer mailer = (to, subject, body) -> sent.add(new String[] {to, subject, body});
    private final LoginLinkService service = new LoginLinkService(tokens, new LoginLinkRateLimiter(props, clock),
            mailer, MAIL, props, clock);

    @Test
    void mailsAOneTimeLinkAndStoresOnlyTheHashOfItsToken() {
        service.requestLink(" Anna@Example.com ", "203.0.113.7");

        ArgumentCaptor<LoginToken> saved = ArgumentCaptor.forClass(LoginToken.class);
        verify(tokens).save(saved.capture());
        LoginToken token = saved.getValue();
        assertThat(token.getEmail()).isEqualTo("anna@example.com");
        assertThat(token.getClientKey()).isEqualTo("203.0.113.7");
        assertThat(token.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(token.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(token.isUsable(clock.instant())).isTrue();

        assertThat(sent).hasSize(1);
        String[] mail = sent.getFirst();
        assertThat(mail[0]).isEqualTo("anna@example.com");
        assertThat(mail[1]).isEqualTo("Dein Zugang zu 3DBurg");
        String link = mail[2].lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow();
        assertThat(link).startsWith("https://3dburg.ch/konto/anmelden?token=").endsWith("&next=/konto/passwort");
        String secret = link.substring(link.indexOf("token=") + 6, link.indexOf("&next="));
        assertThat(secret).matches("[A-Za-z0-9_-]{43}");
        assertThat(token.getTokenHash()).isEqualTo(Secrets.hash(secret)).isNotEqualTo(secret);
        assertThat(mail[2]).doesNotContain(token.getTokenHash());
        // One link, one secret: the mail carries nothing but the password link.
        assertThat(mail[2].split("token=", -1)).hasSize(2);
    }

    @Test
    void beyondTheQuotaNoMailGoesOutAndNothingIsRevealed() {
        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> service.requestLink("anna@example.com", "203.0.113.7")).doesNotThrowAnyException();
        }

        assertThat(sent).hasSize(3);
        verify(tokens, times(3)).save(any());
    }

    @Test
    void aFailingMailServerDoesNotSurfaceToTheCaller() {
        Mailer broken = (to, subject, body) -> {
            throw new IllegalStateException("SMTP down");
        };
        LoginLinkService failing = new LoginLinkService(tokens, new LoginLinkRateLimiter(props, clock), broken, MAIL, props, clock);

        assertThatCode(() -> failing.requestLink("anna@example.com", "c")).doesNotThrowAnyException();
    }
}
