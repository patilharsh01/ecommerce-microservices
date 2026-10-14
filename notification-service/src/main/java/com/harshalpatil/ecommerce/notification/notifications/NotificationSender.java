package com.harshalpatil.ecommerce.notification.notifications;

/** Port to an email/SMS provider (SES, SendGrid, Twilio, ...). */
public interface NotificationSender {

    void send(Notification notification);
}
