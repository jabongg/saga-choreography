package com.example.orderservice.config;

import com.example.contracts.CartCheckedOutEvent;
import com.example.contracts.InventoryFailedEvent;
import com.example.contracts.InventoryReleasedEvent;
import com.example.contracts.InventoryReservedEvent;
import com.example.contracts.PaymentCompletedEvent;
import com.example.contracts.PaymentFailedEvent;
import com.example.contracts.PaymentRefundedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, CartCheckedOutEvent> cartCheckedOutConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<CartCheckedOutEvent> valueDeserializer = new JsonDeserializer<>(CartCheckedOutEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "cartCheckedOutKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, CartCheckedOutEvent> cartCheckedOutKafkaListenerContainerFactory(
            ConsumerFactory<String, CartCheckedOutEvent> cartCheckedOutConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, CartCheckedOutEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cartCheckedOutConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, PaymentRefundedEvent> paymentRefundedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<PaymentRefundedEvent> valueDeserializer = new JsonDeserializer<>(PaymentRefundedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "paymentRefundedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, PaymentRefundedEvent> paymentRefundedKafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentRefundedEvent> paymentRefundedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentRefundedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentRefundedConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, PaymentCompletedEvent> paymentCompletedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<PaymentCompletedEvent> valueDeserializer = new JsonDeserializer<>(PaymentCompletedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "paymentCompletedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, PaymentCompletedEvent> paymentCompletedKafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentCompletedEvent> paymentCompletedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentCompletedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentCompletedConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, PaymentFailedEvent> paymentFailedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<PaymentFailedEvent> valueDeserializer = new JsonDeserializer<>(PaymentFailedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "paymentFailedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, PaymentFailedEvent> paymentFailedKafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentFailedEvent> paymentFailedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentFailedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentFailedConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, InventoryReservedEvent> inventoryReservedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<InventoryReservedEvent> valueDeserializer = new JsonDeserializer<>(InventoryReservedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "inventoryReservedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, InventoryReservedEvent> inventoryReservedKafkaListenerContainerFactory(
            ConsumerFactory<String, InventoryReservedEvent> inventoryReservedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, InventoryReservedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventoryReservedConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, InventoryFailedEvent> inventoryFailedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<InventoryFailedEvent> valueDeserializer = new JsonDeserializer<>(InventoryFailedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "inventoryFailedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, InventoryFailedEvent> inventoryFailedKafkaListenerContainerFactory(
            ConsumerFactory<String, InventoryFailedEvent> inventoryFailedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, InventoryFailedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventoryFailedConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, InventoryReleasedEvent> inventoryReleasedConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        JsonDeserializer<InventoryReleasedEvent> valueDeserializer = new JsonDeserializer<>(InventoryReleasedEvent.class);
        valueDeserializer.addTrustedPackages("com.example.contracts");
        valueDeserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), valueDeserializer);
    }

    @Bean(name = "inventoryReleasedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, InventoryReleasedEvent> inventoryReleasedKafkaListenerContainerFactory(
            ConsumerFactory<String, InventoryReleasedEvent> inventoryReleasedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, InventoryReleasedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventoryReleasedConsumerFactory);
        return factory;
    }
}
