package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sparxilium.smartpluginassistant.model.spiget.SpigetResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sparxilium.smartpluginassistant.model.UpdateResult;
import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.ServerInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class SpigetService implements PluginRepository {
    private static final Logger logger = LoggerFactory.getLogger(SpigetService.class);
    private static final String BASE_URL = "https://api.spiget.org/v2";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant (https://github.com/Sparxilium-Network/SmartPluginAssistant)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HttpDownloadService downloadService;

    public SpigetService(HttpDownloadService downloadService) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.downloadService = downloadService;
    }

    @Override
    public String getPlatformKey() {
        return "spiget";
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

    @Override
    public CompletableFuture<Map<InstalledPlugin, UpdateResult>> checkForUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        Map<InstalledPlugin, UpdateResult> resultMap = new java.util.concurrent.ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (InstalledPlugin plugin : plugins) {
            if (plugin.getProjectId() == null || plugin.getProjectId().isBlank()) continue;

            long resourceId;
            try {
                resourceId = Long.parseLong(plugin.getProjectId());
            } catch (NumberFormatException e) {
                continue;
            }

            // Spiget doesn't have a good batch endpoint, so we query the latest version for each resource
            String url = BASE_URL + "/resources/" + resourceId + "/versions/latest";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            CompletableFuture<Void> f = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonNode root = objectMapper.readTree(response.body());
                                String versionNum = root.path("name").asText(null);
                                String versionId = root.path("id").asText(null);
                                if (versionNum != null && !versionNum.equals(plugin.getCurrentVersionNumber())) {
                                    String downloadUrl = getDownloadUrl(resourceId);
                                    resultMap.put(plugin, new UpdateResult(versionNum, downloadUrl, "-", versionId));
                                }
                            } catch (Exception e) {
                                logger.error("Failed to parse Spiget latest version", e);
                            }
                        }
                    });
            futures.add(f);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> resultMap);
    }

    @Override
    public CompletableFuture<Path> downloadUpdate(ServerInstance instance, String downloadUrl, Path targetPath, Consumer<Double> progressCallback) {
        return downloadService.downloadFile(downloadUrl, targetPath, null, progressCallback);
    }
}
