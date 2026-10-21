package com.slicercore.notification;

/**
 * Sends one plain-text mail from the shop's address. The order module and the account module
 * compose their texts and hand them here; whether a mail actually leaves the server depends on
 * {@code notifications.enabled} ({@link SmtpMailer}) or is written to the log ({@link LoggingMailer}).
 */
public interface Mailer {

    void send(String to, String subject, String body);
}
