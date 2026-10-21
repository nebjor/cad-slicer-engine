package com.slicercore.account;

import com.slicercore.notification.NotificationProperties;

/**
 * The text of the login mail. Plain text, German, in the tone of the order mails: the link,
 * how long it lives, and that nothing happens if the reader did not ask for it.
 */
final class AccountMails {

    private AccountMails() {
    }

    record Mail(String to, String subject, String body) {
    }

    static Mail loginLink(String email, String link, int validMinutes, NotificationProperties props) {
        String subject = "Dein Anmeldelink für " + props.shopName();
        String body = """
                Hallo

                Mit diesem Link meldest du dich bei %s an:

                %s

                Der Link ist %d Minuten gültig und funktioniert nur einmal.

                Falls du keinen Anmeldelink angefordert hast, kannst du diese E-Mail ignorieren; ohne den \
                Klick passiert nichts.

                Freundliche Grüsse
                %s
                """.formatted(props.shopName(), link, validMinutes, props.shopName());
        return new Mail(email, subject, body);
    }
}
