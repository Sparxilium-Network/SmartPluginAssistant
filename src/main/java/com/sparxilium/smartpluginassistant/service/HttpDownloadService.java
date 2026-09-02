package com.sparxilium.smartpluginassistant.service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HttpDownloadService {
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant (https://github.com/Sparxilium-Network/SmartPluginAssistant)";
    private final HttpClient httpClient;

    public HttpDownloadService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<Path> downloadFile(String url, Path targetPath, Map<String, String> headers, Consumer<Double> progressCallback) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET();

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }

        HttpRequest request = requestBuilder.build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Download failed from " + url + " with HTTP " + response.statusCode());
                    }
                    try {
                        if (targetPath.getParent() != null) {
                            Files.createDirectories(targetPath.getParent());
                        }
                        try (InputStream is = response.body()) {
                            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (progressCallback != null) {
                            progressCallback.accept(1.0);
                        }
                        return targetPath;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save downloaded file", e);
                    }
                });
    }
}
