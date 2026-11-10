package com.example.transactions;

import java.math.BigDecimal;

public record TransactionRequest(
        String transactionId,
        String accountId,
        Type transactionType,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        String requestId) {

    public enum Type { CREDIT, DEBIT }

    public String businessPayload() {
        return String.join("|", transactionId, accountId, transactionType.name(),
                amount.toPlainString(), currency, Long.toString(sequenceNumber));
    }
}
