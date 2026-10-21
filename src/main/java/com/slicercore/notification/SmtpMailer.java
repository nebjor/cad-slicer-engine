package com.slicercore.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends mails over the SMTP account from the environment. Active only with
 * {@code notifications.enabled=true}; the server then has to provide {@code spring.mail.*}
 * or the context fails to start, which is the right time to notice.
 */
@Component
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "true")
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties props;

    public SmtpMailer(JavaMailSender mailSender, NotificationProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.shopName() + " <" + props.from() + ">");
        message.setReplyTo(props.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("Sent \"{}\" to {}", subject, to);
    }
}
