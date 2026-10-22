package com.slicercore.account;

import com.slicercore.notification.NotificationProperties;

/**
 * The text of the login mail. Plain text, German, in the tone of the order mails: the link,
 * how long it lives, the offer to set a password instead, and that nothing happens if the
 * reader did not ask for it. Both links carry the same one-time token; the second one only
 * tells the frontend to open the password page after the login.
 */
final class AccountMails {

    static final String LOGIN_PATH = "/konto/anmelden?token=";
    static final String PASSWORD_PAGE_NEXT = "&next=/konto/passwort";

    private AccountMails() {
    }

    record Mail(String to, String subject, String body) {
    }

    /** @param token the login secret as it goes into the URL, issued once for both links */
    static Mail loginLink(String email, String token, int validMinutes, NotificationProperties props) {
        String link = props.shopUrl() + LOGIN_PATH + token;
        String subject = "Dein Anmeldelink für " + props.shopName();
        String body = """
                Hallo

                Mit diesem Link meldest du dich bei %s an:

                %s

                Der Link ist %d Minuten gültig und funktioniert nur einmal.

                Lieber mit Passwort anmelden? Lege hier eines fest:

                %s

                Falls du keinen Anmeldelink angefordert hast, kannst du diese E-Mail ignorieren; ohne den \
                Klick passiert nichts.

                Freundliche Grüsse
                %s
                """.formatted(props.shopName(), link, validMinutes, link + PASSWORD_PAGE_NEXT, props.shopName());
        return new Mail(email, subject, body);
    }
}
