package com.example.userservice.domain;

import com.example.contracts.TopicNames;
import com.example.contracts.UserRegisteredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class UserService {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate;

    public UserService(JdbcTemplate jdbcTemplate,
                       KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    public UserResponse register(UserRegistrationRequest request) {
        validate(request);
        String userId = UUID.randomUUID().toString();
        UserResponse response = new UserResponse(userId, request.fullName().trim(), request.email().trim());
        jdbcTemplate.update(
                "INSERT INTO users (user_id, full_name, email, created_at) VALUES (?, ?, ?, ?)",
                userId,
                response.fullName(),
                response.email(),
                OffsetDateTime.now()
        );

        kafkaTemplate.send(
                TopicNames.USER_REGISTERED,
                userId,
                new UserRegisteredEvent(userId, response.fullName(), response.email(), OffsetDateTime.now())
        );
        return response;
    }

    public UserResponse get(String userId) {
        return jdbcTemplate.query(
                "SELECT user_id, full_name, email FROM users WHERE user_id = ?",
                (rs, rowNum) -> new UserResponse(
                        rs.getString("user_id"),
                        rs.getString("full_name"),
                        rs.getString("email")
                ),
                userId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown userId: " + userId));
    }

    private void validate(UserRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.fullName())) {
            throw new IllegalArgumentException("fullName is required");
        }
        if (isBlank(request.email())) {
            throw new IllegalArgumentException("email is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
