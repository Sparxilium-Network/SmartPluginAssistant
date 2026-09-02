package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparxilium.smartpluginassistant.model.VoxelProduct;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VoxelService {
    private static final Logger logger = LoggerFactory.getLogger(VoxelService.class);
    private static final String BASE_URL = "https://api.voxel.shop/v1";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant/1.0 (contact@sparxilium.com)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public record SearchResultPage(List<VoxelProduct> products, int totalCount, boolean hasMore) {}
    public record DownloadInfo(String downloadUrl, String version) {}

    public VoxelService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
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
        String url = BASE_URL + "/getDownloadURL";
        String formBody = "resource_id=" + resourceId;

        logger.info("VoxelService.getDownloadInfo: POST {} (resource_id={})", url, resourceId);

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
                        return null;
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
                            String msg = respNode.path("message").asText("Unknown error");
                            logger.warn("Voxel getDownloadURL unsuccessful: {}", msg);
                            return null;
                        }
                        JsonNode resultNode = respNode.path("result");
                        String dlUrl = resultNode.path("url").asText(null);
                        String ver = resultNode.path("version").asText(null);
                        if (dlUrl != null && !dlUrl.isBlank()) {
                            return new DownloadInfo(dlUrl, ver);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse Voxel getDownloadURL response", e);
                    }
                    return null;
                });
    }

    public CompletableFuture<Path> downloadFile(String downloadUrl, Path targetPath) {
        logger.info("Downloading file from Voxel: {} -> {}", downloadUrl, targetPath);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
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
                        throw new RuntimeException("Failed to save downloaded file: " + e.getMessage(), e);
                    }
                });
    }
}
