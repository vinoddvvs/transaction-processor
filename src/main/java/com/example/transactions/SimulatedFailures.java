package com.example.transactions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Component;

@Component
public class SimulatedFailures {
    public enum Stage { BEFORE_EFFECT, AFTER_BALANCE, AFTER_LEDGER }

    private final int failures;
    private final Stage failureStage;

    public SimulatedFailures(
            @Value("${processor.simulated-failures}") int failures,
            @Value("${processor.simulated-failure-stage}") Stage failureStage) {
        if (failures < 0) {
            throw new IllegalArgumentException("processor.simulated-failures must be nonnegative");
        }
        this.failures = failures;
        this.failureStage = failureStage;
    }

    public void check(String transactionId, int attempt, Stage stage) {
        if (attempt <= failures && stage == failureStage) {
            throw new TransientDataAccessResourceException("Simulated transient failure at " + stage);
        }
    }
}
