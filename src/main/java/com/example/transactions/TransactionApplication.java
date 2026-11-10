package com.example.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TransactionApplication {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(TransactionApplication.class, args)) {
            // A finite batch deliberately closes its datasource before exiting.
        }
    }
}
