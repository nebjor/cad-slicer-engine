package com.slicercore.notification;

import com.slicercore.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stands in for the mailer where no SMTP account exists: the full text goes to the log. */
@Component
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingOrderNotifier implements OrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingOrderNotifier.class);

    private final NotificationProperties props;

    public LoggingOrderNotifier(NotificationProperties props) {
        this.props = props;
    }

    @Override
    public void orderPaid(Order order) {
        print(OrderMails.paidForCustomer(order, props));
        if (props.ownerNotified()) {
            print(OrderMails.paidForOwner(order, props));
        }
    }

    @Override
    public void orderShipped(Order order) {
        print(OrderMails.shippedForCustomer(order, props));
    }

    private static void print(OrderMails.Mail mail) {
        log.info("Mail not sent (notifications.enabled=false)\nTo: {}\nSubject: {}\n\n{}", mail.to(), mail.subject(), mail.body());
    }
}
