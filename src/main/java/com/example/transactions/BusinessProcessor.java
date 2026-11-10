package com.example.transactions;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessProcessor {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private final JdbcTemplate jdbc;
    private final SimulatedFailures failures;

    public BusinessProcessor(JdbcTemplate jdbc, SimulatedFailures failures) {
        this.jdbc = jdbc;
        this.failures = failures;
    }

    public static class BusinessRejection extends RuntimeException {
        public BusinessRejection(String message) {
            super(message);
        }
    }

    @Transactional
    public void process(String transactionId, int attempt) {
        var rows = jdbc.queryForList("""
                SELECT t.*, a.balance, a.next_sequence FROM transactions t
                JOIN accounts a ON a.account_id = t.account_id WHERE t.transaction_id = ?
                """, transactionId);
        var row = rows.getFirst();
        String state = (String) row.get("STATE");
        if ("PROCESSED".equals(state) || "FAILED".equals(state)) {
            return;
        }
        long sequence = ((Number) row.get("SEQUENCE_NUMBER")).longValue();
        if (sequence != ((Number) row.get("NEXT_SEQUENCE")).longValue()) {
            throw new IllegalStateException("Attempted processing out of account order");
        }
        failures.check(transactionId, attempt, SimulatedFailures.Stage.BEFORE_EFFECT);
        BigDecimal amount = (BigDecimal) row.get("AMOUNT");
        BigDecimal delta = "CREDIT".equals(row.get("TRANSACTION_TYPE")) ? amount : amount.negate();
        BigDecimal balance = ((BigDecimal) row.get("BALANCE")).add(delta);
        if (balance.signum() < 0) {
            throw new BusinessRejection("Insufficient funds; overdrafts are not allowed");
        }
        if (balance.compareTo(MAX_BALANCE) > 0) {
            throw new BusinessRejection("Account balance would exceed DECIMAL(19,2)");
        }
        String accountId = (String) row.get("ACCOUNT_ID");
        jdbc.update("UPDATE accounts SET balance = ?, next_sequence = ? WHERE account_id = ?",
                balance, sequence + 1, accountId);
        failures.check(transactionId, attempt, SimulatedFailures.Stage.AFTER_BALANCE);
        jdbc.update("""
                INSERT INTO ledger(transaction_id, account_id, delta, currency, sequence_number)
                VALUES (?, ?, ?, ?, ?)
                """, transactionId, accountId, delta, row.get("CURRENCY"), sequence);
        failures.check(transactionId, attempt, SimulatedFailures.Stage.AFTER_LEDGER);
        jdbc.update("""
                UPDATE transactions SET state = 'PROCESSED', next_attempt_at = 0, last_error = NULL
                WHERE transaction_id = ?
                """, transactionId);
    }
}
