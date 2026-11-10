package com.example.transactions;

import java.io.BufferedReader;
import java.io.IOException;

public interface InputSource {
    String description();

    BufferedReader open() throws IOException;
}
