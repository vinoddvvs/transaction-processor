package com.example.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class PersistenceRestartTest {
    @TempDir Path temp;

    @Test
    @Tag("P0")
    void fileBackedReplayAfterContextRestartPreservesLedgerBalancesAndRetryState() throws InterruptedException {
        String url = "jdbc:h2:file:" + temp.resolve("durable").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        long due;
        try (var first = start(url)) {
            var store = first.getBean(TransactionStore.class);
            var validator = first.getBean(RequestValidator.class);
            store.accept(validator.parse(ProcessingIntegrationTest.json("t1", "a", "CREDIT", "10", 1, "r1")));
            first.getBean(BatchRunner.class).processPending();
            store.accept(validator.parse(ProcessingIntegrationTest.json("t2", "a", "CREDIT", "5", 2, "r2")));
            due = System.currentTimeMillis() + 200;
            store.reserveAttempt("t2", 3, due, 0);
            assertThat(first.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT next_attempt_at FROM transactions WHERE transaction_id='t2'", Long.class)).isEqualTo(due);
        }
        try (var second = start(url)) {
            var jdbc = second.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT attempts FROM transactions WHERE transaction_id='t2'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM transactions WHERE transaction_id='t2'", Long.class))
                    .isEqualTo(due);
            var store = second.getBean(TransactionStore.class);
            var validator = second.getBean(RequestValidator.class);
            assertThat(store.accept(validator.parse(
                    ProcessingIntegrationTest.json("t1", "a", "CREDIT", "10", 1, "r1"))))
                    .isEqualTo(TransactionStore.Outcome.DUPLICATE_REQUEST);
            assertThat(store.accept(validator.parse(
                    ProcessingIntegrationTest.json("t1", "a", "CREDIT", "10", 1, "new-request"))))
                    .isEqualTo(TransactionStore.Outcome.DUPLICATE_TRANSACTION);
            second.getBean(BatchRunner.class).processPending();
            assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(due);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_id='a'", BigDecimal.class))
                    .isEqualByComparingTo("15.00");
            assertThat(jdbc.queryForObject("SELECT attempts FROM transactions WHERE transaction_id='t2'", Integer.class))
                    .isEqualTo(2);
        }
        try (var third = start(url)) {
            third.getBean(BatchRunner.class).processPending();
            var jdbc = third.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_id='a'", BigDecimal.class))
                    .isEqualByComparingTo("15.00");
        }
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(TransactionApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--processor.run-enabled=false",
                        "--processor.retry-delay-ms=0");
    }
}
