package com.example.orderservice.domain;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SagaControlService {

    private final Map<String, FailureRule> failureRules = new ConcurrentHashMap<>();

    public void configureFailure(String stepName, int remainingFailures, String message) {
        failureRules.put(stepName, new FailureRule(remainingFailures, message));
    }

    public Map<String, Integer> getFailureRules() {
        return failureRules.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> 1));
    }

    public void clearFailures() {
        failureRules.clear();
    }

    public void failIfConfigured(String stepName) {
        FailureRule rule = failureRules.get(stepName);
        if (rule != null && rule.shouldFail()) {
            throw new IllegalStateException(stepName + ": " + rule.message());
        }
    }
}
