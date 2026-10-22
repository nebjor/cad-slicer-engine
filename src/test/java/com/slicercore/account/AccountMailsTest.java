package com.slicercore.account;

import com.slicercore.notification.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The registration mail is what the customer reads; pin the facts it must carry. */
class AccountMailsTest {

    private static final NotificationProperties PROPS =
            new NotificationProperties(true, "info@3dburg.ch", "owner@3dburg.ch", "3DBurg", "https://3dburg.ch");

    @Test
    void registrationMailCarriesThePasswordLinkItsLifetimeAndTheReassurance() {
        AccountMails.Mail mail = AccountMails.registration("anna@example.com", "abc", 15, PROPS);

        assertThat(mail.to()).isEqualTo("anna@example.com");
        assertThat(mail.subject()).isEqualTo("Dein Zugang zu 3DBurg");
        assertThat(mail.body())
                .startsWith("Hallo\n")
                .contains("Mit diesem Link legst du dein Passwort fest und bist danach angemeldet:\n\n"
                        + "https://3dburg.ch/konto/anmelden?token=abc&next=/konto/passwort\n")
                .contains("Der Link ist 15 Minuten gültig und funktioniert nur einmal. "
                        + "Falls du ihn nicht angefordert hast, ignoriere diese E-Mail einfach.")
                .endsWith("Freundliche Grüsse\n3DBurg\n")
                .doesNotContain("null");
    }

    /** Exactly one link and one token: the mail no longer offers a plain login next to the password page. */
    @Test
    void theMailCarriesExactlyOneLinkWithOneToken() {
        String body = AccountMails.registration("anna@example.com", "abc", 15, PROPS).body();

        List<String> links = body.lines().filter(line -> line.startsWith("https://")).toList();
        assertThat(links).containsExactly("https://3dburg.ch/konto/anmelden?token=abc&next=/konto/passwort");
        assertThat(body.split("token=", -1)).hasSize(2);
        assertThat(body).doesNotContain("Lieber mit Passwort");
    }
}
