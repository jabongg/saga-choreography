package com.example.notificationservice.template;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationTemplateProperties {

    private final String paymentSubject;
    private final String paymentBody;

    public NotificationTemplateProperties(
            @Value("${notification.payment.subject}") String paymentSubject,
            @Value("${notification.payment.body}") String paymentBody
    ) {
        this.paymentSubject = paymentSubject;
        this.paymentBody = paymentBody;
    }

    public String paymentSubject() {
        return paymentSubject;
    }

    public String paymentBody() {
        return paymentBody;
    }
}
