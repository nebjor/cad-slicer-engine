package com.slicercore.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MailOrderNotifierTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);

    @Test
    void paidOrderMailsTheCustomerAndTheOwnerFromTheShopAddress() {
        NotificationProperties props =
                new NotificationProperties(true, "info@3dburg.ch", "owner@3dburg.ch", "3DBurg", "https://3dburg.ch");

        new MailOrderNotifier(sender, props).orderPaid(OrderMailsTest.order(false));

        List<SimpleMailMessage> sent = sentMessages(2);
        assertThat(sent.get(0).getTo()).containsExactly("anna@example.com");
        assertThat(sent.get(0).getFrom()).isEqualTo("3DBurg <info@3dburg.ch>");
        assertThat(sent.get(0).getReplyTo()).isEqualTo("info@3dburg.ch");
        assertThat(sent.get(0).getSubject()).contains("POD-1000");
        assertThat(sent.get(1).getTo()).containsExactly("owner@3dburg.ch");
    }

    @Test
    void withoutOwnerAddressOnlyTheCustomerIsMailed() {
        NotificationProperties props = new NotificationProperties(true, "info@3dburg.ch", "", "3DBurg", "https://3dburg.ch");

        new MailOrderNotifier(sender, props).orderPaid(OrderMailsTest.order(false));

        assertThat(sentMessages(1).get(0).getTo()).containsExactly("anna@example.com");
    }

    private List<SimpleMailMessage> sentMessages(int expected) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }
}
