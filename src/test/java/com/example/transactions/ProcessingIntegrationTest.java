package com.example.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "processor.run-enabled=false",
        "processor.retry-delay-ms=0",
        "spring.datasource.url=jdbc:h2:mem:processing-tests;DB_CLOSE_DELAY=-1"
})
class ProcessingIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestValidator validator;
    @Autowired TransactionStore store;
    @Autowired BatchRunner runner;
    @Autowired ControlledFailures failures;
    @TempDir Path temp;

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        @Primary
        ControlledFailures controlledFailures() {
            return new ControlledFailures();
        }
    }

    static class ControlledFailures extends SimulatedFailures {
        int failures;
        Stage stage = Stage.AFTER_LEDGER;
        Set<String> selected = Set.of();

        ControlledFailures() {
            super(0, Stage.AFTER_LEDGER);
        }

        @Override
        public void check(String transactionId, int attempt, Stage actualStage) {
            if (selected.contains(transactionId) && attempt <= failures && stage == actualStage) {
                throw new TransientDataAccessResourceException("Test transient failure at " + stage);
            }
        }
    }

    @BeforeEach
    void reset() {
        for (String table : new String[]{"ledger", "transactions", "accounts", "deliveries", "rejected_inputs"}) {
            jdbc.update("DELETE FROM " + table);
        }
        failures.failures = 0;
        failures.selected = Set.of();
        failures.stage = SimulatedFailures.Stage.AFTER_LEDGER;
    }

    @Test
    @Tag("P0")
    void processesCreditAndDebitInAccountOrderDespiteArrivalOrder() throws InterruptedException {
        accept("t2", "a", "DEBIT", "25", 2, "r2");
        accept("t1", "a", "CREDIT", "100", 1, "r1");
        accept("b1", "b", "CREDIT", "7.12", 1, "r3");
        runner.processPending();
        assertBalance("a", "75.00");
        assertBalance("b", "7.12");
        assertThat(count("ledger")).isEqualTo(3);
        assertState("t1", "PROCESSED", 1);
        assertState("t2", "PROCESSED", 1);
        assertThat(jdbc.queryForObject("SELECT next_sequence FROM accounts WHERE account_id='a'", Long.class))
                .isEqualTo(3L);
    }

    @Test
    @Tag("P0")
    void recognizesSameAndNewRequestIdDuplicatesAfterProcessing() throws InterruptedException {
        accept("t1", "a", "CREDIT", "100", 1, "r1");
        runner.processPending();
        assertThat(accept("t1", "a", "CREDIT", "100.00", 1, "r1"))
                .isEqualTo(TransactionStore.Outcome.DUPLICATE_REQUEST);
        assertThat(accept("t1", "a", "CREDIT", "100.0", 1, "late"))
                .isEqualTo(TransactionStore.Outcome.DUPLICATE_TRANSACTION);
        runner.processPending();
        assertBalance("a", "100.00");
        assertThat(count("ledger")).isEqualTo(1);
        assertState("t1", "PROCESSED", 1);
    }

    @Test
    @Tag("P0")
    void rejectsTransactionPayloadConflictWithoutOverwritingOriginal() throws InterruptedException {
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        runner.processPending();
        assertThat(accept("t1", "a", "CREDIT", "20", 1, "r2"))
                .isEqualTo(TransactionStore.Outcome.CONFLICT);
        assertState("t1", "PROCESSED", 1);
        assertBalance("a", "10");
        assertThat(jdbc.queryForObject("SELECT amount FROM transactions WHERE transaction_id='t1'", BigDecimal.class))
                .isEqualByComparingTo("10");
    }

    @Test
    @Tag("P1")
    void rejectsRequestIdReusedForDifferentTransactionOrAmount() {
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        assertThat(accept("t2", "a", "CREDIT", "10", 2, "r1"))
                .isEqualTo(TransactionStore.Outcome.CONFLICT);
        assertThat(accept("t1", "a", "CREDIT", "11", 1, "r1"))
                .isEqualTo(TransactionStore.Outcome.CONFLICT);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("deliveries")).isEqualTo(1);
    }

    @Test
    @Tag("P1")
    void rejectsTwoTransactionsClaimingOneAccountSequenceAndCurrencyMismatch() {
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        assertThat(accept("t2", "a", "CREDIT", "10", 1, "r2"))
                .isEqualTo(TransactionStore.Outcome.CONFLICT);
        var euros = validator.parse(json("t3", "a", "CREDIT", "10", 2, "r3")
                .replace("\"USD\"", "\"EUR\""));
        assertThat(store.accept(euros)).isEqualTo(TransactionStore.Outcome.CONFLICT);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    @Tag("P1")
    void laterBatchFillsGapWithoutReingestingPendingTransaction() throws InterruptedException {
        accept("future", "a", "CREDIT", "10", 2, "r2");
        assertTimeout(Duration.ofSeconds(2), runner::processPending);
        assertState("future", "PENDING", 0);
        assertBalance("a", "0");
        assertThat(count("ledger")).isZero();
        accept("prior", "a", "CREDIT", "20", 1, "r1");
        runner.processPending();
        assertBalance("a", "30");
        assertState("prior", "PROCESSED", 1);
        assertState("future", "PROCESSED", 1);
        assertThat(count("ledger")).isEqualTo(2);
    }

    @Test
    @Tag("P0")
    void transientFailuresSucceedOnBoundedThirdAttempt() throws InterruptedException {
        failures.selected = Set.of("t1");
        failures.failures = 2;
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        runner.processPending();
        assertState("t1", "PROCESSED", 3);
        assertThat(count("ledger")).isEqualTo(1);
        assertBalance("a", "10");
        assertThat(jdbc.queryForObject("SELECT last_error FROM transactions WHERE transaction_id='t1'", String.class))
                .isNull();
    }

    @Test
    @Tag("P0")
    void exhaustedRetriesAreTerminalAndBlockSubsequentAccountSequence() throws InterruptedException {
        failures.selected = Set.of("t1");
        failures.failures = 99;
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        accept("t2", "a", "CREDIT", "10", 2, "r2");
        accept("b1", "b", "CREDIT", "5", 1, "r3");
        runner.processPending();
        assertState("t1", "FAILED", 3);
        assertState("t2", "PENDING", 0);
        assertState("b1", "PROCESSED", 1);
        assertBalance("a", "0");
        failures.failures = 0;
        assertThat(accept("t1", "a", "CREDIT", "10", 1, "redelivery"))
                .isEqualTo(TransactionStore.Outcome.DUPLICATE_TRANSACTION);
        runner.processPending();
        assertState("t1", "FAILED", 3);
    }

    @Test
    @Tag("P0")
    void failureAfterLedgerInsertRollsBackAllBusinessChanges() throws InterruptedException {
        failures.selected = Set.of("t1");
        failures.failures = 3;
        failures.stage = SimulatedFailures.Stage.AFTER_LEDGER;
        accept("t1", "a", "CREDIT", "10", 1, "r1");
        runner.processPending();
        assertState("t1", "FAILED", 3);
        assertBalance("a", "0");
        assertThat(count("ledger")).isZero();
        assertThat(jdbc.queryForObject("SELECT next_sequence FROM accounts WHERE account_id='a'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @Tag("P0")
    void insufficientFundsFailsWithoutRetryOrPartialEffects() throws InterruptedException {
        accept("debit", "a", "DEBIT", "10", 1, "r1");
        runner.processPending();
        assertState("debit", "FAILED", 1);
        assertBalance("a", "0");
        assertThat(count("ledger")).isZero();
    }

    @Test
    @Tag("P1")
    void malformedLinesAreAuditedAndValidNeighboursStillProcess() throws IOException {
        Path input = temp.resolve("input.jsonl");
        Files.writeString(input, String.join("\n",
                "broken json",
                json("t1", "a", "CREDIT", "10", 1, "r1"),
                "{}",
                "",
                json("t2", "a", "DEBIT", "2", 2, "r2"),
                json("t1", "a", "CREDIT", "11", 1, "conflict")) + "\n");
        assertTimeout(Duration.ofSeconds(2), () -> runner.process(new JsonLinesInputSource(input.toString())));
        assertThat(count("rejected_inputs")).isEqualTo(4);
        assertThat(count("ledger")).isEqualTo(2);
        assertBalance("a", "8");
    }

    @Test
    @Tag("P1")
    void enforcesAllMandatoryFieldsAndFieldTypes() throws JsonProcessingException {
        String valid = json("t1", "a", "CREDIT", "10", 1, "r1");
        var mapper = new JsonMapper();
        for (String field : Set.of("transactionId", "accountId", "transactionType", "amount",
                "currency", "sequenceNumber", "requestId")) {
            ObjectNode missing = mapper.readValue(valid, ObjectNode.class);
            missing.remove(field);
            assertThatThrownBy(() -> validator.parse(missing.toString()))
                    .as("Missing mandatory field: %s", field).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{
                valid.replace("\"USD\"", "\"JPY\""),
                valid.replace("\"CREDIT\"", "\"REFUND\""),
                valid.replace("\"transactionId\":\"t1\"", "\"transactionId\":42"),
                valid.replace("\"a\"", "\"contains|delimiter\""),
                valid.replace("\"amount\":\"10\"", "\"amount\":null"),
                valid.replace("\"amount\":\"10\"", "\"amount\":\"0\""),
                valid.replace("\"amount\":\"10\"", "\"amount\":\"-1\""),
                valid.replace("\"amount\":\"10\"", "\"amount\":\"1.001\""),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":0"),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":1.5"),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":\"1\""),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":9223372036854775807"),
                valid.replace("\"sequenceNumber\":1", "\"sequenceNumber\":9223372036854775808"),
                valid.replace("{", "{\"unexpected\":true,")
        }) {
            assertThatThrownBy(() -> validator.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private TransactionStore.Outcome accept(String id, String account, String type, String amount,
            long sequence, String request) {
        return store.accept(validator.parse(json(id, account, type, amount, sequence, request)));
    }

    static String json(String id, String account, String type, String amount, long sequence, String request) {
        return """
                {"transactionId":"%s","accountId":"%s","transactionType":"%s","amount":"%s",
                 "currency":"USD","sequenceNumber":%d,"requestId":"%s"}
                """.formatted(id, account, type, amount, sequence, request).replace("\n", "");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertBalance(String account, String expected) {
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_id=?",
                BigDecimal.class, account)).isEqualByComparingTo(expected);
    }

    private void assertState(String id, String state, int attempts) {
        assertThat(jdbc.queryForObject("SELECT state FROM transactions WHERE transaction_id=?", String.class, id))
                .isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT attempts FROM transactions WHERE transaction_id=?", Integer.class, id))
                .isEqualTo(attempts);
    }
}
