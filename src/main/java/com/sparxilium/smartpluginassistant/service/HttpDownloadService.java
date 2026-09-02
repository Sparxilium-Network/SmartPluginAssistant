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
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SmartPluginAssistant (https://github.com/Sparxilium-Network/SmartPluginAssistant)";
    private final HttpClient httpClient;

    public HttpDownloadService(HttpClient httpClient) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<Path> downloadFile(String url, Path targetPath, Map<String, String> headers, Consumer<Double> progressCallback) {
        return downloadFileInternal(url, targetPath, headers, progressCallback, 0);
    }

    private CompletableFuture<Path> downloadFileInternal(String url, Path targetPath, Map<String, String> headers, Consumer<Double> progressCallback, int redirectCount) {
        if (redirectCount > 5) {
            return CompletableFuture.failedFuture(new RuntimeException("Too many redirects downloading from: " + url));
        }

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
                .thenCompose(response -> {
                    int status = response.statusCode();
                    // Handle redirects (e.g. 301, 302, 303, 307, 308)
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location != null && !location.isBlank()) {
                            URI baseUri = URI.create(url);
                            URI targetUri = baseUri.resolve(location);
                            return downloadFileInternal(targetUri.toString(), targetPath, headers, progressCallback, redirectCount + 1);
                        }
                    }

                    if (status != 200) {
                        return CompletableFuture.failedFuture(new RuntimeException("Download failed from " + url + " with HTTP " + status));
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
                        return CompletableFuture.completedFuture(targetPath);
                    } catch (Exception e) {
                        return CompletableFuture.failedFuture(new RuntimeException("Failed to save downloaded file", e));
                    }
                });
    }
}
