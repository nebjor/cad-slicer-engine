package com.slicercore.notification;

import com.slicercore.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the order mails over the SMTP account from the environment. Active only with
 * {@code notifications.enabled=true}; the server then has to provide {@code spring.mail.*}
 * or the context fails to start, which is the right time to notice.
 */
@Component
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "true")
public class MailOrderNotifier implements OrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailOrderNotifier.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties props;

    public MailOrderNotifier(JavaMailSender mailSender, NotificationProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public void orderPaid(Order order) {
        send(OrderMails.paidForCustomer(order, props));
        if (props.ownerNotified()) {
            send(OrderMails.paidForOwner(order, props));
        }
    }

    @Override
    public void orderShipped(Order order) {
        send(OrderMails.shippedForCustomer(order, props));
    }

    private void send(OrderMails.Mail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.shopName() + " <" + props.from() + ">");
        message.setReplyTo(props.from());
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        mailSender.send(message);
        log.info("Sent \"{}\" to {}", mail.subject(), mail.to());
    }
}
