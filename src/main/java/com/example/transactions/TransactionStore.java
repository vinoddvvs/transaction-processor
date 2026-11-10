package com.example.transactions;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionStore {
    public enum Outcome { ACCEPTED, DUPLICATE_REQUEST, DUPLICATE_TRANSACTION, CONFLICT }

    private final JdbcTemplate jdbc;

    public TransactionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome accept(TransactionRequest request) {
        String payload = request.businessPayload();
        var previousDelivery = jdbc.queryForList(
                "SELECT payload, outcome FROM deliveries WHERE request_id = ?", request.requestId());
        if (!previousDelivery.isEmpty()) {
            var original = previousDelivery.getFirst();
            return original.get("PAYLOAD").equals(payload)
                    && !Outcome.CONFLICT.name().equals(original.get("OUTCOME"))
                    ? Outcome.DUPLICATE_REQUEST : Outcome.CONFLICT;
        }
        List<TransactionRequest> previous = jdbc.query(
                "SELECT * FROM transactions WHERE transaction_id = ?",
                (rs, row) -> new TransactionRequest(rs.getString("transaction_id"),
                        rs.getString("account_id"), TransactionRequest.Type.valueOf(rs.getString("transaction_type")),
                        rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getLong("sequence_number"), request.requestId()), request.transactionId());
        Outcome outcome;
        if (!previous.isEmpty()) {
            outcome = previous.getFirst().businessPayload().equals(payload)
                    ? Outcome.DUPLICATE_TRANSACTION : Outcome.CONFLICT;
        } else {
            List<String> currencies = jdbc.queryForList(
                    "SELECT currency FROM accounts WHERE account_id = ?", String.class, request.accountId());
            boolean sequenceUsed = !jdbc.queryForList(
                    "SELECT transaction_id FROM transactions WHERE account_id = ? AND sequence_number = ?",
                    String.class, request.accountId(), request.sequenceNumber()).isEmpty();
            if (sequenceUsed || (!currencies.isEmpty() && !currencies.getFirst().equals(request.currency()))) {
                outcome = Outcome.CONFLICT;
            } else {
                if (currencies.isEmpty()) {
                    jdbc.update("INSERT INTO accounts(account_id, currency) VALUES (?, ?)",
                            request.accountId(), request.currency());
                }
                jdbc.update("""
                        INSERT INTO transactions(transaction_id, account_id, transaction_type, amount,
                            currency, sequence_number, state) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                        """, request.transactionId(), request.accountId(), request.transactionType().name(),
                        request.amount(), request.currency(), request.sequenceNumber());
                outcome = Outcome.ACCEPTED;
            }
        }
        jdbc.update("INSERT INTO deliveries(request_id, payload, transaction_id, outcome) VALUES (?, ?, ?, ?)",
                request.requestId(), payload, request.transactionId(), outcome.name());
        return outcome;
    }

    public void reject(String source, long line, String reason) {
        jdbc.update("INSERT INTO rejected_inputs(source, line_number, reason) VALUES (?, ?, ?)",
                shorten(source, 1000), line, shorten(reason, 500));
    }

    @Transactional
    public int reserveAttempt(String transactionId, int maxAttempts, long now, long retryDelay) {
        Integer previous = jdbc.queryForObject(
                "SELECT attempts FROM transactions WHERE transaction_id = ?", Integer.class, transactionId);
        if (previous == null || previous >= maxAttempts) {
            jdbc.update("""
                    UPDATE transactions SET state = 'FAILED', last_error = 'Attempt budget exhausted on recovery'
                    WHERE transaction_id = ? AND state IN ('PENDING', 'RETRY')
                    """, transactionId);
            return 0;
        }
        int attempt = previous + 1;
        jdbc.update("""
                UPDATE transactions SET attempts = ?, state = 'RETRY', next_attempt_at = ?
                WHERE transaction_id = ?
                """, attempt, now + retryDelay, transactionId);
        return attempt;
    }

    @Transactional
    public void recordFailure(String transactionId, boolean terminal, String error) {
        jdbc.update("""
                UPDATE transactions SET state = ?, last_error = ?
                WHERE transaction_id = ? AND state <> 'PROCESSED'
                """, terminal ? "FAILED" : "RETRY", shorten(error, 500), transactionId);
    }

    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
