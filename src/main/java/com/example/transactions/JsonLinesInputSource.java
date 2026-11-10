package com.example.transactions;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JsonLinesInputSource implements InputSource {
    private final Path path;

    public JsonLinesInputSource(@Value("${processor.input}") String path) {
        this.path = Path.of(path);
    }

    @Override
    public String description() {
        return path.toString();
    }

    @Override
    public BufferedReader open() throws IOException {
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }
}
