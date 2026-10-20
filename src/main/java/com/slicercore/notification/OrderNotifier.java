package com.slicercore.notification;

import com.slicercore.order.Order;

/** What the shop tells people about an order. Implemented by mail, or by the log when mail is off. */
public interface OrderNotifier {

    /** Payment confirmed: the customer gets the confirmation, the owner a notification. */
    void orderPaid(Order order);

    /** The parcel is on its way. */
    void orderShipped(Order order);
}
