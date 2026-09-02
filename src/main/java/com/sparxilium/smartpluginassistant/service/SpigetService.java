package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparxilium.smartpluginassistant.model.SpigetResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SpigetService {
    private static final Logger logger = LoggerFactory.getLogger(SpigetService.class);
    private static final String BASE_URL = "https://api.spiget.org/v2";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant/1.0 (contact@sparxilium.com)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SpigetService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public CompletableFuture<List<SpigetResource>> searchResources(String query, int page, int size) {
        String url;
        if (query == null || query.isBlank()) {
            url = BASE_URL + "/resources?sort=-downloads&page=" + page + "&size=" + size;
        } else {
            url = BASE_URL + "/search/resources/" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "?page=" + page + "&size=" + size;
        }

        logger.info("SpigetService.searchResources: GET {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        logger.warn("Spiget search returned HTTP status: {}", response.statusCode());
                        return Collections.<SpigetResource>emptyList();
                    }
                    try {
                        String body = response.body();
                        if (body.startsWith("\uFEFF")) {
                            body = body.substring(1);
                        }
                        return objectMapper.readValue(body, new TypeReference<List<SpigetResource>>() {});
                    } catch (Exception e) {
                        logger.error("Failed to parse Spiget search response", e);
                        return Collections.<SpigetResource>emptyList();
                    }
                });
    }

    public CompletableFuture<String> getAuthorName(long authorId) {
        String url = BASE_URL + "/authors/" + authorId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) return "Unknown";
                    try {
                        String body = response.body();
                        if (body.startsWith("\uFEFF")) body = body.substring(1);
                        return objectMapper.readTree(body).path("name").asText("Unknown");
                    } catch (Exception e) {
                        return "Unknown";
                    }
                })
                .exceptionally(ex -> "Unknown");
    }

    public String getDownloadUrl(long resourceId) {
        return BASE_URL + "/resources/" + resourceId + "/download";
    }

    public CompletableFuture<Path> downloadResource(long resourceId, Path targetPath) {
        String url = getDownloadUrl(resourceId);
        logger.info("Downloading resource from Spiget: {} -> {}", url, targetPath);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Download failed with HTTP " + response.statusCode());
                    }
                    try {
                        if (targetPath.getParent() != null) {
                            Files.createDirectories(targetPath.getParent());
                        }
                        try (InputStream in = response.body()) {
                            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return targetPath;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save downloaded Spiget resource: " + e.getMessage(), e);
                    }
                });
    }
}
