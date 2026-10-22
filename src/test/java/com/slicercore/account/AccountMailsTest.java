package com.slicercore.account;

import com.slicercore.notification.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The login mail is what the customer reads; pin the facts it must carry. */
class AccountMailsTest {

    private static final NotificationProperties PROPS =
            new NotificationProperties(true, "info@3dburg.ch", "owner@3dburg.ch", "3DBurg", "https://3dburg.ch");

    @Test
    void loginMailCarriesTheLinkItsLifetimeThePasswordOfferAndTheReassurance() {
        AccountMails.Mail mail = AccountMails.loginLink("anna@example.com", "abc", 15, PROPS);

        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.subject()).isEqualTo("Dein Anmeldelink für 3DBurg");
        assertThat(mail.body())
                .startsWith("Hallo\n")
                .contains("Mit diesem Link meldest du dich bei 3DBurg an:\n\nhttps://3dburg.ch/konto/anmelden?token=abc\n")
                .contains("Der Link ist 15 Minuten gültig und funktioniert nur einmal.")
                .contains("Lieber mit Passwort anmelden? Lege hier eines fest:\n\n"
                        + "https://3dburg.ch/konto/anmelden?token=abc&next=/konto/passwort\n")
                .contains("Falls du keinen Anmeldelink angefordert hast, kannst du diese E-Mail ignorieren")
                .endsWith("Freundliche Grüsse\n3DBurg\n")
                .doesNotContain("null");
    }

    /** One token per mail: the password link is the login link plus a hint for the frontend, not a second secret. */
    @Test
    void bothLinksCarryTheSameTokenAndTheLoginLinkComesFirst() {
        String body = AccountMails.loginLink("anna@example.com", "abc", 15, PROPS).body();

        List<String> links = body.lines().filter(line -> line.startsWith("https://")).toList();
        assertThat(links).containsExactly(
                "https://3dburg.ch/konto/anmelden?token=abc",
                "https://3dburg.ch/konto/anmelden?token=abc&next=/konto/passwort");
        assertThat(body.split("token=", -1)).hasSize(3);
    }
}
