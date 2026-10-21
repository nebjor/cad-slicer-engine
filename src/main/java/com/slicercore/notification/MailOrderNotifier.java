package com.slicercore.notification;

import com.slicercore.order.Order;
import org.springframework.stereotype.Component;

/** Composes the order mails and hands them to the {@link Mailer} (SMTP, or the log when mail is off). */
@Component
public class MailOrderNotifier implements OrderNotifier {

    private final Mailer mailer;
    private final NotificationProperties props;

    public MailOrderNotifier(Mailer mailer, NotificationProperties props) {
        this.mailer = mailer;
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
        mailer.send(mail.to(), mail.subject(), mail.body());
    }
}
