package com.example.transactions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RequestValidator {
    private static final Set<String> FIELDS = Set.of("transactionId", "accountId",
            "transactionType", "amount", "currency", "sequenceNumber", "requestId");
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "INR");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.99");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public TransactionRequest parse(String line) {
        final JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown field: " + field);
            }
        }
        String transactionId = identifier(root, "transactionId");
        String accountId = identifier(root, "accountId");
        String requestId = identifier(root, "requestId");
        TransactionRequest.Type type;
        try {
            type = TransactionRequest.Type.valueOf(text(root, "transactionType").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("transactionType must be CREDIT or DEBIT");
        }
        String currency = text(root, "currency").toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("currency must be USD, EUR, GBP or INR");
        }
        JsonNode value = root.get("amount");
        if (value == null || !(value.isNumber() || value.isTextual())) {
            throw new IllegalArgumentException("amount must be a decimal number or string");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value.asText()).setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("amount must be an exact decimal with at most two fractional digits");
        }
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount must be positive and at most " + MAX_AMOUNT);
        }
        JsonNode sequence = root.get("sequenceNumber");
        if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()
                || sequence.longValue() <= 0 || sequence.longValue() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("sequenceNumber must be an integer from 1 to 9223372036854775806");
        }
        return new TransactionRequest(transactionId, accountId, type, amount,
                currency, sequence.longValue(), requestId);
    }

    private static String identifier(JsonNode root, String field) {
        String value = text(root, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
            throw new IllegalArgumentException(field + " must be 1-100 identifier characters");
        }
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " is required and must be a nonblank string");
        }
        return value.textValue().trim();
    }
}
