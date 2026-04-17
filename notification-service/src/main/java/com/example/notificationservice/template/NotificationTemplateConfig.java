package com.example.notificationservice.template;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("classpath:notification-templates.properties")
public class NotificationTemplateConfig {
}
