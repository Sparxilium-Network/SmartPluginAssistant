package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparxilium.smartpluginassistant.model.voxel.VoxelProduct;
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

public class VoxelService implements PluginRepository {
    private static final Logger logger = LoggerFactory.getLogger(VoxelService.class);
    private static final String BASE_URL = "https://api.voxel.shop/v1";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant (https://github.com/Sparxilium-Network/SmartPluginAssistant)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HttpDownloadService downloadService;

    public record SearchResultPage(List<VoxelProduct> products, int totalCount, boolean hasMore) {}
    public record DownloadInfo(String downloadUrl, String version, String errorCode, String errorMessage) {}

    public VoxelService(HttpDownloadService downloadService) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.downloadService = downloadService;
    }

    @Override
    public String getPlatformKey() {
        return "voxel";
    }

    public CompletableFuture<SearchResultPage> searchResources(String query, int offset, int limit) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/search?");
        if (query != null && !query.isBlank()) {
            urlBuilder.append("query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8)).append("&");
        }
        urlBuilder.append("offset=").append(offset).append("&limit=").append(limit);

        String url = urlBuilder.toString();
        logger.info("VoxelService.searchResources: GET {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        logger.error("Voxel search failed with HTTP status: {}", response.statusCode());
                        return new SearchResultPage(Collections.emptyList(), 0, false);
                    }
                    try {
                        String body = response.body();
                        // Strip UTF-8 BOM if present
                        if (body.startsWith("\uFEFF")) {
                            body = body.substring(1);
                        }
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode responseNode = root.path("response");
                        int total = responseNode.path("total").asInt(0);
                        boolean more = responseNode.path("more").asBoolean(false);

                        JsonNode resultArray = responseNode.path("result");
                        List<VoxelProduct> list = new ArrayList<>();
                        if (resultArray.isArray()) {
                            for (JsonNode node : resultArray) {
                                VoxelProduct product = objectMapper.treeToValue(node, VoxelProduct.class);
                                list.add(product);
                            }
                        }
                        return new SearchResultPage(list, total, more);
                    } catch (Exception e) {
                        logger.error("Failed to parse Voxel search response", e);
                        return new SearchResultPage(Collections.emptyList(), 0, false);
                    }
                });
    }

    public CompletableFuture<DownloadInfo> getDownloadInfo(long resourceId) {
        return getDownloadInfo(resourceId, null);
    }

    public CompletableFuture<DownloadInfo> getDownloadInfo(long resourceId, String token) {
        String url = BASE_URL + "/getDownloadURL";
        StringBuilder formBuilder = new StringBuilder("resource_id=").append(resourceId);
        if (token != null && !token.isBlank()) {
            formBuilder.append("&token=").append(URLEncoder.encode(token.trim(), StandardCharsets.UTF_8));
        }
        String formBody = formBuilder.toString();

        logger.info("VoxelService.getDownloadInfo: POST {} (resource_id={}, hasToken={})", url, resourceId, (token != null && !token.isBlank()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        logger.error("Voxel getDownloadURL failed with status: {}", response.statusCode());
                        return new DownloadInfo(null, null, "HTTP_" + response.statusCode(), "HTTP " + response.statusCode());
                    }
                    try {
                        String body = response.body();
                        if (body.startsWith("\uFEFF")) {
                            body = body.substring(1);
                        }
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode respNode = root.path("response");
                        boolean success = respNode.path("success").asBoolean(false);
                        if (!success) {
                            String err = respNode.path("error").asText(null);
                            if (err == null || err.isBlank()) {
                                err = respNode.path("message").asText("UNKNOWN_ERROR");
                            }
                            String msg = respNode.path("message").asText(err);
                            logger.warn("Voxel getDownloadURL unsuccessful: err={}, msg={}", err, msg);
                            return new DownloadInfo(null, null, err, msg);
                        }
                        JsonNode resultNode = respNode.path("result");
                        String dlUrl = resultNode.path("url").asText(null);
                        String ver = resultNode.path("version").asText(null);
                        if (dlUrl != null && !dlUrl.isBlank()) {
                            return new DownloadInfo(dlUrl, ver, null, null);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse Voxel getDownloadURL response", e);
                        return new DownloadInfo(null, null, "PARSE_ERROR", e.getMessage());
                    }
                    return new DownloadInfo(null, null, "NO_URL", "No download URL returned");
                });
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

            String voxelToken = instance != null ? instance.getApiToken("voxel") : null;
            CompletableFuture<Void> f = getDownloadInfo(resourceId, voxelToken)
                    .thenAccept(dlInfo -> {
                        if (dlInfo != null && dlInfo.downloadUrl() != null && dlInfo.version() != null) {
                            // Check if version is different from currently installed
                            if (!dlInfo.version().equals(plugin.getCurrentVersionNumber())) {
                                resultMap.put(plugin, new UpdateResult(dlInfo.version(), dlInfo.downloadUrl(), "-", dlInfo.version()));
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
        // Voxel might need api token if we ever implement premium downloads, we can grab it from instance
        Map<String, String> headers = new java.util.HashMap<>();
        if (instance != null && instance.getApiToken("voxel") != null && !instance.getApiToken("voxel").isBlank()) {
            // Not strictly specified by Polymart docs for direct URL, but good placeholder
            // headers.put("Authorization", "Bearer " + instance.getApiToken("voxel"));
        }
        return downloadService.downloadFile(downloadUrl, targetPath, headers, progressCallback);
    }
}
