package com.example.notificationservice.domain;

import java.util.concurrent.atomic.AtomicInteger;

public final class FailureRule {

    private final AtomicInteger remainingFailures;
    private final String message;

    public FailureRule(int remainingFailures, String message) {
        this.remainingFailures = new AtomicInteger(Math.max(remainingFailures, 0));
        this.message = (message == null || message.isBlank()) ? "Injected saga failure" : message;
    }

    public boolean shouldFail() {
        while (true) {
            int current = remainingFailures.get();
            if (current <= 0) {
                return false;
            }
            if (remainingFailures.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    public String message() {
        return message;
    }
}
