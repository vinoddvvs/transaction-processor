# Design notes

## Follow the ordinary Java flow

Java 21 target, Spring Boot 3.5.7, Spring JDBC, Jackson, and file-backed H2.
This is one finite batch worker, not a web application or distributed service.

`TransactionApplication.main` starts Spring Boot. Boot creates the objects marked
`@Component` or `@Service` and supplies their constructor arguments. It then calls
`BatchRunner.run`, the normal Spring Boot `CommandLineRunner` entry point.

`BatchRunner` has three main methods:

1. `process(input)` opens a reader in try-with-resources and uses a `while` loop.
   `RequestValidator.parse` returns a `TransactionRequest` or throws a validation
   error. Bad lines are audited and skipped with `continue`; good lines go to
   `TransactionStore.accept` for duplicate/conflict checks and saving.
2. `processPending()` repeatedly queries the next expected transaction per account.
   It saves an attempt, calls `BusinessProcessor.process`, and catches known
   transient failures for bounded retries. It returns when only blocked work remains.
3. `report()` queries saved states, ledger counts, balances, and blocked work and
   prints them. `process` also prints this run's input counts.

The application then closes the database and exits. `processor.run-enabled=false`
only skips automatic execution, so tests can call the same methods explicitly.

There are **9 main Java files and 2 test files**.
`TransactionRequest` is a record: Java supplies a constructor and accessors such
as `amount()` without getter/setter boilerplate. JDBC operations use
`JdbcTemplate.update` for writes, `queryForObject` for one value, and `queryForList`
for rows. SQL `?` placeholders bind values rather than building SQL from input.

Compared with the previous version, the runner and worker are one class,
callback-based input is an ordinary reader loop, reporting/field checks use loops,
and failure simulation needs no extra interface. No new framework was added.
The tradeoff is a longer `BatchRunner`, but the control flow is visible in one
place rather than spread across services. Input finishes before processing starts.

`InputSource` stays as the one interface required for replaceable input:
`description()` names it and `open()` returns a `BufferedReader`. Implementations
can open another file, a string, or another line-based stream; the caller closes
the reader. This intentionally does not model queue acknowledgement or polling.
A replacement can be registered as a primary Spring bean.

## What is saved, and why?

| Table | What is saved |
|---|---|
| `accounts` | Exact balance, fixed currency, and next expected account sequence |
| `transactions` | Original payload, state, attempt count, retry due time, last error |
| `deliveries` | Each validated request ID and its first payload/outcome |
| `ledger` | One signed monetary effect per transaction ID |
| `rejected_inputs` | Source, line number, and rejection reason; no raw payload |

Database primary/unique keys enforce transaction ID, request ID, and
`(account_id, sequence_number)` uniqueness. Startup does not erase existing data.
The setup script is not a schema migration system.

## Duplicate and ordering rules

`transactionId` means "this business operation." `requestId` means "this delivery."
Compare normalized business fields, excluding request ID: trimmed IDs, uppercased
type/currency, exact two-decimal amount, account, and sequence. IDs cannot contain
the delimiter used by the comparison string.

| Delivery | Result |
|---|---|
| Same request ID and same payload | Duplicate request; no business effect |
| Same request ID with changed payload | Conflict |
| New request ID, same transaction ID and payload | Duplicate transaction; no business effect |
| Existing transaction ID with changed business payload | Conflict |
| Different transaction claiming an occupied account sequence or currency mismatch | Conflict |

A previously rejected request remains a conflict on replay. Validated conflicting
deliveries reserve new request IDs; corrected data needs a new request ID.
Malformed/schema-invalid input does not reserve IDs. No duplicate overwrites
PROCESSED/FAILED or resets attempts. Database lookup, rather than a short-lived
cache, also detects very late and post-restart duplicates.

Sequences are **per account**, starting at 1. Future work stays PENDING.
A missing predecessor reports `sequence_gap`; a failed predecessor reports
`terminal_failed_predecessor`. Other accounts can proceed. When only blocked work
remains, the worker exits instead of waiting forever. A later input can fill a
gap, but failed sequences are never automatically skipped or reset.

## Why keep business processing separate?

The attempt must survive a failed business operation. `BatchRunner` calls
two Spring-managed objects, whose `@Transactional` methods commit separately:

1. `TransactionStore.reserveAttempt` saves the incremented attempt and retry time.
2. `BusinessProcessor.process` saves balance, ledger, next sequence, and PROCESSED
   **in one transaction**. Any failure rolls all four changes back.
3. After rollback, the worker calls `TransactionStore.recordFailure` to save
   RETRY or FAILED.

Combining all of this into one transaction would roll back the attempt count too.
`@Transactional` tells Spring to commit all SQL in that method on success or
undo it when a runtime exception escapes. Spring applies this when another
object calls the method; calling it from the same object bypasses that behavior.
This is why the runner, metadata store, and business processor remain separate.
The runner itself is not transactional.

```text
PENDING -> RETRY (attempt saved; also represents in-flight work)
RETRY   -> PROCESSED (all business changes committed)
RETRY   -> RETRY (transient error; retry later)
RETRY   -> FAILED (permanent rejection or attempts exhausted)
```

Default: three total reserved attempts and fixed 100 ms backoff from reservation.
Counts and due timestamps survive restart. A crash can consume an attempt before
business code runs; recovery never resets the budget. A crash on the last
reservation becomes FAILED on recovery if it did not commit success.

| Stop/crash point | Expected restart behavior |
|---|---|
| Before acceptance commits | Replay can accept the record |
| After acceptance | Saved PENDING work is available |
| After reservation or during tentative business updates | Saved attempt remains; uncommitted business effects do not |
| After business commit | Balance, ledger, ordering, and PROCESSED survive together; replay changes none |
| Before failure recording | Saved reservation still bounds future attempts |

The guarantee is at-most-one committed local business effect per accepted
transaction ID under a **single worker**, not end-to-end exactly-once delivery
or guaranteed eventual success. External HTTP/queue effects are not covered.

## Test approach: arrange, act, assert

Tests use real Spring transaction proxies and H2, not mocked balances.
The reduced suite has 13 tests: 8 tagged P0 and 5 tagged P1. P0 covers the core
money-safety and processing checks; P1 covers important conflicts, validation,
and pending-work recovery. Additional variations are manual scenarios in the README,
not claims of automated coverage.
They arrange input and failures, run the worker, then inspect saved results:

| Scenario | Important assertions |
|---|---|
| Duplicate/replay | Balance and ledger unchanged; original status/attempts preserved |
| Invalid input among valid lines | Rejection recorded; valid neighbours still process |
| Future-before-prior or gap | Correct account-local order; blocked work stays pending |
| Transient failure then success | Bounded attempts; exactly one ledger row and correct balance |
| Fault after ledger insert | No partial balance, ledger, or checkpoint changes |
| Exhaustion/permanent business failure | FAILED persists; later same-account work remains blocked |
| Close/reopen file database | Saved attempts/due times survive; replay cannot apply money twice |

`SimulatedFailures.check` throws a transient error at the selected step for the
first N durable attempts, with no hardcoded business IDs. Tests use a small
subclass that overrides `check` to choose a transaction and failure step.
This is ordinary Java method overriding, not a separate plug-in framework.
The automated rollback case fails after the tentative ledger insert, when both
balance and ledger changes must be undone. Before-effect and after-balance failure
variations remain in the manual test list.

## Money, reporting, and errors

Use `BigDecimal` and SQL `DECIMAL(19,2)`, not floating point. USD/EUR/GBP/INR use
two decimals, one currency per account. No FX, fees, or overdrafts. Validation
rejects invalid amounts; processing rejects insufficient funds and balance
overflow permanently. `10`, `10.0`, and `10.000` are the same normalized amount.

Logs show IDs, line outcomes, attempts/reasons, state/ledger counts, balances, and
blocked reasons. Input counts are per run; database counts are cumulative.
Rejected occurrences are audited again on replay. Exit 0 means the batch finished,
not that every record succeeded. I/O, configuration, integrity, and unexpected
errors exit nonzero; only known transient errors are retried.

## Limits and production next steps

Keep one worker; pool size 1 is not a multi-worker locking strategy. Do not enable
H2 AUTO_SERVER. This solution has no queue, REST API, operator repair tool, or
external side effects. Lines are streamed but not size-limited; scans and reports
are assessment-scale, and data is not automatically pruned.

Production would need a production database and migrations, paged/indexed work
queries, concurrency locks/leases, queue acknowledgement/dead-letter policies,
exponential backoff/jitter, and an explicit failed/gap reconciliation process.
External effects need an atomic outbox plus downstream idempotency. Also add
least-privilege credentials, input limits, encryption/access control, retention,
metrics/alerts, backup/restore and load/crash tests, CI/dependency scanning, and
independent human review. Local H2 defaults are not a security setup.

## AI-assisted development

GitHub Copilot assisted with code, documentation, and verification.Evidence comes from the executed H2
integration tests and packaged sample/replay checks, not from assuming generated
code is correct. Test scenarios are in `README.md`; captured sample outputs are
in `samples/`.
