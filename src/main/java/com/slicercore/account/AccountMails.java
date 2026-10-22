package com.slicercore.account;

import com.slicercore.notification.NotificationProperties;

/**
 * The text of the registration mail. Plain text, German, in the tone of the order mails: one
 * link, how long it lives, and that nothing happens if the reader did not ask for it. The
 * link signs the customer in with the one-time token and tells the frontend to open the
 * password page, so the same mail serves a new customer, a forgotten password and an account
 * that never had one.
 */
final class AccountMails {

    static final String LOGIN_PATH = "/konto/anmelden?token=";
    static final String PASSWORD_PAGE_NEXT = "&next=/konto/passwort";

    private AccountMails() {
    }

    record Mail(String to, String subject, String body) {
    }

    /** @param token the login secret as it goes into the URL */
    static Mail registration(String email, String token, int validMinutes, NotificationProperties props) {
        String link = props.shopUrl() + LOGIN_PATH + token + PASSWORD_PAGE_NEXT;
        String subject = "Dein Zugang zu " + props.shopName();
        String body = """
                Hallo

                Mit diesem Link legst du dein Passwort fest und bist danach angemeldet:

                %s

                Der Link ist %d Minuten gültig und funktioniert nur einmal. Falls du ihn nicht angefordert \
                hast, ignoriere diese E-Mail einfach.

                Freundliche Grüsse
                %s
                """.formatted(link, validMinutes, props.shopName());
        return new Mail(email, subject, body);
    }
}
