# Background transaction processor

## About the service

A Java service that reads credits and debits from a file and updates account
balances. It prevents duplicate updates, handles transactions received in the wrong
order, and retries temporary failures. Data is saved in a local H2 database.

Each account starts at zero, uses its own sequence starting at 1, and cannot overdraw.

## Development and testing approach

This assessment includes both building the service and checking its behavior.
The solution is kept small so its business rules and failure cases are easy to explain.

- **Development:** Java and Spring Boot, file input, and a local database.
  Balance, ledger, and transaction state changes are saved together to prevent
  partial updates. No API, queue, or cloud setup is required.
- **Testing:** P0/P1 integration tests check the main service behavior.
  Manual cases cover business rules and edge cases. Performance scenarios describe
  how to check larger batches, duplicates, and retry overhead.

## Start the application

With JDK 21 or newer installed, open the project folder and run:

```bash
./mvnw package -DskipTests
java -jar target/transaction-processor-1.0.0.jar
```

The first build needs internet access. The default input is
`samples/transactions.jsonl`; the database is `data/transactions.mv.db`.
The application prints a report and exits.

To select another input and database:

```bash
java -jar target/transaction-processor-1.0.0.jar \
  --processor.input=samples/transactions.jsonl \
  --spring.datasource.url=jdbc:h2:file:./target/test01
```

## Testing scope

| In scope | Out of scope |
|---|---|
| Credit/debit balances and amount boundaries | Currency conversion, fees, and reversals |
| Duplicate requests, replay, and conflicting data | External payment systems |
| Account sequence, missing records, and blocked work | Real queue delivery and acknowledgement |
| Invalid input and independent account processing | API and UI testing; neither is provided |
| Simulated failures, retries, and rollback | Full security and penetration testing |
| Saved data after normal restart | Abrupt process-kill, machine failure, and disaster recovery testing |
| Local batch timing, duplicate load, and retry overhead | Multiple workers, distributed load, and production capacity certification |

## Manual test cases

### Test data

T1 is a credit of 100. T2 is a debit of 40 for the same account:

```json
{"transactionId":"T1","accountId":"A1","transactionType":"CREDIT","amount":"100","currency":"USD","sequenceNumber":1,"requestId":"R1"}
{"transactionId":"T2","accountId":"A1","transactionType":"DEBIT","amount":"40","currency":"USD","sequenceNumber":2,"requestId":"R2"}
```

Save one JSON object per line, without an enclosing array. Use the input path in
the start command to select your file.

Use a new database name for each case and each input variation. Reuse a database
only for replay or recovery steps. Check the console balance, ledger count, next
sequence, and transaction outcome. A zero exit code alone does not mean all
transactions succeeded.

| ID | Test case | Steps | Expected outcome |
|---|---|---|---|
| M01 | Valid credit | Run with only T1. | T1 processes on attempt 1. Balance 100.00; ledger 1; next sequence 2. |
| M02 | Replay the same request | Complete M01. Run the same file and database again. | Duplicate request reported. Balance 100.00, ledger 1, and next sequence 2 stay unchanged. |
| M03 | Credit followed by debit | Run with T1 and T2. | Both process. Balance 60.00; ledger 2; next sequence 3. |
| M04 | Duplicate inside one file | Put T1 twice in the file and run. | One accepted transaction and one duplicate request. Balance 100.00; ledger 1. |
| M05 | Same transaction, new request ID | Put T1 twice in the file. Change only the second request ID to R3. | Duplicate transaction reported. Balance 100.00; ledger 1. |
| M06 | Same amount written differently | Repeat M05 using 100.0 or 100.00 for the second amount. | Still a duplicate, not a conflict. Money is added only once. |
| M07 | Changed business data | Put T1 twice in the file. Change the second request ID to R3 and amount to 200. Repeat separately with a changed account, type, currency, or sequence. | Each changed record is a conflict. Original balance stays 100.00; ledger 1. |
| M08 | Reused request ID | Run with T1 and T2, but change T2's request ID to R1. | T2 is a conflict. Balance 100.00; ledger 1. |
| M09 | Wrong arrival order | Put T2 before T1 and run. | T1 processes before T2. Balance 60.00; ledger 2; no pending records. |
| M10 | Missing earlier transaction | Run with only T2. | T2 is pending with 0 attempts. Balance 0.00; ledger 0; missing sequence 1 reported. |
| M11 | Fill a missing sequence | Complete M10. Run only T1 against the same database. | T1 and saved T2 process. Balance 60.00; ledger 2. |
| M12 | Same sequence used twice | Run with T1 and T2, but change T2's sequence to 1. | T2 is a conflict. Balance 100.00; ledger 1. |
| M13 | Different account currency | Run with T1 and T2, but change T2's currency to EUR. | T2 is a conflict. Account stays in USD with balance 100.00. |
| M14 | Invalid line among valid records | Put T1, a line containing `broken json`, and T2 in the file. Repeat separately with a blank line and an empty object. | Each run reports 1 invalid record. Valid records process. Balance 60.00; ledger 2. |
| M15 | Missing or invalid fields | Start with T1. Remove each required field separately. Also try null, empty identifiers, wrong field types, and an extra field. | Invalid records are rejected without financial effects. |
| M16 | Invalid amount | Start with T1. Try 0, -1, 1.001, and 1000000000000000 in separate runs. | Each record is rejected. No balance update or ledger entry. |
| M17 | Amount boundaries | Start with T1. Try 0.01 and 999999999999999.99 in separate runs. | Each processes once. Balance exactly matches the amount. |
| M18 | Invalid sequence | Start with T1. Try 0, -1, 1.5, and 9223372036854775807 in separate runs. | Each record is rejected without financial effects. |
| M19 | Unsupported type or currency | Start with T1. Try type REFUND, then currency JPY in a separate run. | Each record is rejected. |
| M20 | Accepted formatting | Use T1 with type `credit`, currency ` usd `, and amount 100.000. | T1 processes. Currency USD; balance 100.00. |
| M21 | Debit below balance | Run T1 and T2 with debit amount 99.99. | Both process. Balance 0.01; ledger 2. |
| M22 | Debit equal to balance | Run T1 and T2 with debit amount 100. | Both process. Balance 0.00; ledger 2. |
| M23 | Debit above balance | Run T1 and T2 with debit amount 100.01. | T2 fails on attempt 1. Balance 100.00; ledger 1. |
| M24 | Temporary failure | Run only T1 with `--processor.simulated-failures=1 --processor.max-attempts=3 --processor.retry-delay-ms=0`. | Attempt 1 fails; attempt 2 succeeds. Balance 100.00; ledger 1. |
| M25 | All attempts fail | Run only T1 with simulated failures 3, maximum attempts 3, and retry delay 0. | T1 fails after 3 attempts. Balance 0.00; ledger 0; next sequence 1. |
| M26 | No partial update | Repeat M25 with `--processor.simulated-failure-stage=BEFORE_EFFECT`, then AFTER_BALANCE, then AFTER_LEDGER, using new databases. | Every variation leaves balance 0.00, ledger 0, and next sequence 1. |
| M27 | Replay a failed transaction | Complete M25. Set simulated failures to 0 and replay T1 against the same database. | T1 stays failed. Attempts do not reset; no money is added. |
| M28 | Failed transaction blocks later work | Change T1 to a DEBIT and keep T2 at sequence 2. Run. | T1 fails for insufficient funds. T2 stays pending. Balance 0.00; ledger 0. |
| M29 | Other accounts still work | Use M28's input. Add an A2 CREDIT of 25, sequence 1, with new transaction and request IDs. | A1 stays blocked. A2 processes with balance 25.00. Ledger 1. |
| M30 | Empty file | Run with an empty file and a new database. | No records processed, no financial effects, and no invalid-line errors. |
| M31 | Missing file | Select an input path that does not exist and run. | File error shown; nonzero exit code. |
| M32 | Restart and replay | Complete M03. After the application exits, run the same input and database again. | Duplicate requests reported. Saved balance 60.00 and ledger count 2 stay unchanged. |

## Performance test cases

Use credits of 0.01 for one account, unique transaction/request IDs, and consecutive
sequences starting at 1. Use a fresh database for every independent run.

Measure elapsed time with the same machine and Java settings. Redirect logs to a
file. Do one warm-up, then three measured runs with fresh databases; compare the
middle time. No speed target is specified, so these checks establish a local baseline.

| ID | Test case | Steps | Expected outcome |
|---|---|---|---|
| P01 | 1,000 transactions | Prepare 1,000 unique credits, sequences 1-1000. Run and measure elapsed time. | Processed 1,000; ledger 1,000; balance 10.00; next sequence 1001; no failed or pending records. |
| P02 | 10,000 transactions | Repeat P01 with 10,000 credits. Compare time with P01. | Processed 10,000; ledger 10,000; balance 100.00; next sequence 10001; no failed or pending records. |
| P03 | Duplicate-heavy input | Repeat P01's 1,000 records once in the same file. Run all 2,000 lines. | Accepted 1,000; duplicate requests 1,000; ledger 1,000; balance 10.00; next sequence 1001. |
| P04 | Reverse arrival order | Reverse P01's input to sequences 1000 down to 1. Run and compare time with P01. | All process in sequence. Balance 10.00; ledger 1,000; next sequence 1001; no pending records. |
| P05 | Retry under load | Run P01's input with one simulated failure per transaction, maximum attempts 3, and retry delay 0. | All 1,000 succeed on attempt 2. Retry events 1,000; ledger 1,000; balance 10.00. |
| P06 | Replay a large batch | Complete P01. Run the same input and database again and measure time. | Duplicate requests 1,000; no new financial effects; balance stays 10.00; ledger stays 1,000. |

Elapsed time includes Java startup, file reading, database work, and shutdown.
A faster run with incorrect results is not acceptable.

## Integration tests

The automated suite has 13 tests that check the service and a test database together.

| Priority | Coverage | Expected outcome |
|---|---|---|
| P0 - 8 cases | Credit/debit order, duplicates, changed transaction payload, retry success, retry exhaustion, rollback, insufficient funds, restart/replay | Correct balances, no duplicate or partial updates, bounded retries, and saved data after restart. |
| P1 - 5 cases | Reused request ID, sequence/currency conflict, gap filling, invalid lines, required fields and values | Bad records are rejected; valid and pending work process correctly. |

Run all integration tests with `./mvnw test`. Test files:
`ProcessingIntegrationTest.java` and `PersistenceRestartTest.java`.
