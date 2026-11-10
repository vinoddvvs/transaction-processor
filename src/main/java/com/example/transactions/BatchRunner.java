package com.example.transactions;

import java.io.IOException;
import java.util.EnumMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BatchRunner implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(BatchRunner.class);
    private final InputSource source;
    private final RequestValidator validator;
    private final TransactionStore store;
    private final BusinessProcessor processor;
    private final JdbcTemplate jdbc;
    private final int maxAttempts;
    private final long retryDelay;
    private final boolean runEnabled;

    public BatchRunner(InputSource source, RequestValidator validator, TransactionStore store,
            BusinessProcessor processor, JdbcTemplate jdbc,
            @Value("${processor.max-attempts}") int maxAttempts,
            @Value("${processor.retry-delay-ms}") long retryDelay,
            @Value("${processor.run-enabled}") boolean runEnabled) {
        if (maxAttempts < 1 || maxAttempts > 100 || retryDelay < 0 || retryDelay > 60000) {
            throw new IllegalArgumentException("max-attempts must be 1-100 and retry-delay-ms must be 0-60000");
        }
        this.source = source;
        this.validator = validator;
        this.store = store;
        this.processor = processor;
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.runEnabled = runEnabled;
    }

    @Override
    public void run(String... args) throws Exception {
        if (runEnabled) {
            process(source);
        }
    }

    public void process(InputSource input) throws IOException, InterruptedException {
        var counts = new EnumMap<TransactionStore.Outcome, Integer>(TransactionStore.Outcome.class);
        int invalid = 0;
        try (var reader = input.open()) {
            long lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                final TransactionRequest request;
                try {
                    request = validator.parse(line);
                } catch (IllegalArgumentException e) {
                    store.reject(input.description(), lineNumber, e.getMessage());
                    invalid++;
                    LOG.warn("REJECTED line={} reason={}", lineNumber, e.getMessage());
                    continue;
                }
                var outcome = store.accept(request);
                counts.merge(outcome, 1, Integer::sum);
                if (outcome == TransactionStore.Outcome.CONFLICT) {
                    store.reject(input.description(), lineNumber,
                            "Conflicting requestId, transaction payload, account currency, or account sequence");
                }
                LOG.info("INGEST line={} requestId={} transactionId={} outcome={}",
                        lineNumber, request.requestId(), request.transactionId(), outcome);
            }
        }
        processPending();
        LOG.info("INPUT_SUMMARY accepted={} duplicateRequests={} duplicateTransactions={} conflicts={} invalid={}",
                counts.getOrDefault(TransactionStore.Outcome.ACCEPTED, 0),
                counts.getOrDefault(TransactionStore.Outcome.DUPLICATE_REQUEST, 0),
                counts.getOrDefault(TransactionStore.Outcome.DUPLICATE_TRANSACTION, 0),
                counts.getOrDefault(TransactionStore.Outcome.CONFLICT, 0), invalid);
        report();
    }

    public void processPending() throws InterruptedException {
        while (true) {
            var ready = jdbc.queryForList("""
                    SELECT t.transaction_id, t.next_attempt_at FROM transactions t
                    JOIN accounts a ON a.account_id = t.account_id
                    WHERE t.state IN ('PENDING', 'RETRY') AND t.sequence_number = a.next_sequence
                    ORDER BY t.account_id
                    """);
            if (ready.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            long earliest = Long.MAX_VALUE;
            boolean attempted = false;
            for (var row : ready) {
                long due = ((Number) row.get("NEXT_ATTEMPT_AT")).longValue();
                if (due > now) {
                    earliest = Math.min(earliest, due);
                    continue;
                }
                attempted = true;
                String id = (String) row.get("TRANSACTION_ID");
                int attempt = store.reserveAttempt(id, maxAttempts, now, retryDelay);
                if (attempt == 0) {
                    LOG.warn("FAILED transactionId={} reason=attempt_budget_exhausted_on_recovery", id);
                    continue;
                }
                try {
                    processor.process(id, attempt);
                    LOG.info("PROCESSED transactionId={} attempt={}", id, attempt);
                } catch (TransientDataAccessException e) {
                    boolean terminal = attempt >= maxAttempts;
                    store.recordFailure(id, terminal, e.getMessage());
                    LOG.warn("{} transactionId={} attempt={} reason={}",
                            terminal ? "FAILED" : "RETRY", id, attempt, e.getMessage());
                } catch (BusinessProcessor.BusinessRejection e) {
                    store.recordFailure(id, true, e.getMessage());
                    LOG.warn("FAILED transactionId={} attempt={} reason={}", id, attempt, e.getMessage());
                }
            }
            if (!attempted) {
                Thread.sleep(Math.min(Math.max(earliest - System.currentTimeMillis(), 1), 1000));
            }
        }
    }

    private void report() {
        for (String state : new String[]{"PENDING", "RETRY", "PROCESSED", "FAILED"}) {
            LOG.info("STATE_SUMMARY state={} count={}", state,
                    jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE state = ?", Long.class, state));
        }
        LOG.info("LEDGER_SUMMARY entries={} rejectedInputs={}",
                jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Long.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM rejected_inputs", Long.class));
        var accounts = jdbc.queryForList("SELECT * FROM accounts ORDER BY account_id");
        for (var account : accounts) {
            LOG.info("ACCOUNT accountId={} currency={} balance={} nextSequence={}",
                    account.get("ACCOUNT_ID"), account.get("CURRENCY"),
                    account.get("BALANCE"), account.get("NEXT_SEQUENCE"));
        }
        var blocked = jdbc.queryForList("""
                SELECT t.transaction_id, t.account_id, t.sequence_number, a.next_sequence,
                    blocker.state AS blocker_state
                FROM transactions t JOIN accounts a ON a.account_id = t.account_id
                LEFT JOIN transactions blocker
                    ON blocker.account_id = a.account_id AND blocker.sequence_number = a.next_sequence
                WHERE t.state = 'PENDING' ORDER BY t.account_id, t.sequence_number
                """);
        for (var row : blocked) {
            LOG.warn("BLOCKED transactionId={} accountId={} sequence={} expected={} reason={}",
                    row.get("TRANSACTION_ID"), row.get("ACCOUNT_ID"), row.get("SEQUENCE_NUMBER"),
                    row.get("NEXT_SEQUENCE"), "FAILED".equals(row.get("BLOCKER_STATE"))
                            ? "terminal_failed_predecessor" : "sequence_gap");
        }
    }
}
