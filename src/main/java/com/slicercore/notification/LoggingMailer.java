package com.slicercore.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stands in for the mailer where no SMTP account exists: the full text goes to the log. */
@Component
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("Mail not sent (notifications.enabled=false)\nTo: {}\nSubject: {}\n\n{}", to, subject, body);
    }
}
