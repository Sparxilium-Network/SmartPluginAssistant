package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparxilium.smartpluginassistant.model.HangarProject;
import com.sparxilium.smartpluginassistant.model.HangarVersion;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HangarService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HangarService.class);
    private static final String BASE_URL = "https://hangar.papermc.io/api/v1";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant/1.0 (contact@sparxilium.com)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HangarService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ===== Search =====

    public CompletableFuture<SearchPage> searchProjects(String query, String platform, String mcVersion, int offset, int limit) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/projects?");
        if (query != null && !query.isBlank()) {
            urlBuilder.append("query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8)).append("&");
        }
        if (platform != null && !platform.isBlank()) {
            urlBuilder.append("platform=").append(URLEncoder.encode(platform.toUpperCase(), StandardCharsets.UTF_8)).append("&");
        }
        if (mcVersion != null && !mcVersion.isBlank() && !mcVersion.equalsIgnoreCase("all")) {
            urlBuilder.append("version=").append(URLEncoder.encode(mcVersion, StandardCharsets.UTF_8)).append("&");
        }
        urlBuilder.append("offset=").append(offset).append("&limit=").append(limit);

        String url = urlBuilder.toString();
        logger.info("HangarService.searchProjects: GET {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    logger.info("HangarService.searchProjects: HTTP {} body_len={}", response.statusCode(), response.body().length());
                    if (response.statusCode() != 200) {
                        logger.warn("Hangar search returned HTTP {}: {}", response.statusCode(), response.body());
                        return new SearchPage(Collections.emptyList(), 0);
                    }
                    try {
                        Map<String, Object> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
                        Object paginationObj = raw.get("pagination");
                        int count = 0;
                        if (paginationObj instanceof Map<?,?> pagination) {
                            Object countObj = pagination.get("count");
                            if (countObj instanceof Number n) count = n.intValue();
                        }
                        Object resultObj = raw.get("result");
                        List<HangarProject> projects = new ArrayList<>();
                        if (resultObj != null) {
                            String resultJson = objectMapper.writeValueAsString(resultObj);
                            projects = objectMapper.readValue(resultJson, new TypeReference<>() {});
                        }
                        return new SearchPage(projects, count);
                    } catch (Exception e) {
                        logger.error("Failed to parse Hangar search response: {}", e.getMessage(), e);
                        return new SearchPage(Collections.emptyList(), 0);
                    }
                });
    }

    // ===== Get Project =====

    public CompletableFuture<HangarProject> getProject(String author, String slug) {
        String url = BASE_URL + "/projects/" + URLEncoder.encode(author, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
        logger.info("HangarService.getProject: GET {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Hangar project not found (HTTP " + response.statusCode() + "): " + author + "/" + slug);
                    }
                    try {
                        return objectMapper.readValue(response.body(), HangarProject.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse Hangar project: " + e.getMessage(), e);
                    }
                });
    }

    // ===== Get Versions =====

    public CompletableFuture<VersionPage> getVersions(String author, String slug, String platform, String mcVersion, int offset, int limit) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/projects/"
                + URLEncoder.encode(author, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(slug, StandardCharsets.UTF_8)
                + "/versions?");
        if (platform != null && !platform.isBlank()) {
            urlBuilder.append("platform=").append(URLEncoder.encode(platform.toUpperCase(), StandardCharsets.UTF_8)).append("&");
        }
        if (mcVersion != null && !mcVersion.isBlank() && !mcVersion.equalsIgnoreCase("all")) {
            urlBuilder.append("platformVersion=").append(URLEncoder.encode(mcVersion, StandardCharsets.UTF_8)).append("&");
        }
        urlBuilder.append("offset=").append(offset).append("&limit=").append(limit);

        String url = urlBuilder.toString();
        logger.info("HangarService.getVersions: GET {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    logger.info("HangarService.getVersions: HTTP {} body_len={}", response.statusCode(), response.body().length());
                    if (response.statusCode() != 200) {
                        logger.warn("Hangar versions returned HTTP {}: {}", response.statusCode(), response.body());
                        return new VersionPage(Collections.emptyList(), 0);
                    }
                    try {
                        Map<String, Object> raw = objectMapper.readValue(response.body(), new TypeReference<>() {});
                        Object paginationObj = raw.get("pagination");
                        int count = 0;
                        if (paginationObj instanceof Map<?,?> pagination) {
                            Object countObj = pagination.get("count");
                            if (countObj instanceof Number n) count = n.intValue();
                        }
                        Object resultObj = raw.get("result");
                        List<HangarVersion> versions = new ArrayList<>();
                        if (resultObj != null) {
                            String resultJson = objectMapper.writeValueAsString(resultObj);
                            versions = objectMapper.readValue(resultJson, new TypeReference<>() {});
                        }
                        return new VersionPage(versions, count);
                    } catch (Exception e) {
                        logger.error("Failed to parse Hangar versions response: {}", e.getMessage(), e);
                        return new VersionPage(Collections.emptyList(), 0);
                    }
                });
    }

    // ===== Download =====

    public CompletableFuture<Path> downloadFile(String fileUrl, Path destination, Consumer<Double> progressCallback) {
        logger.info("HangarService.downloadFile: downloading {} -> {}", fileUrl, destination);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Download failed with HTTP " + response.statusCode());
                    }
                    try (InputStream is = response.body()) {
                        if (destination.getParent() != null) {
                            Files.createDirectories(destination.getParent());
                        }
                        Files.copy(is, destination, StandardCopyOption.REPLACE_EXISTING);
                        if (progressCallback != null) progressCallback.accept(1.0);
                        return destination;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save downloaded file: " + e.getMessage(), e);
                    }
                });
    }

    // ===== URL Parsing =====

    /**
     * Parse a Hangar URL or "author/slug" string.
     * Supported formats:
     *  - https://hangar.papermc.io/William278/HuskHomes
     *  - https://hangar.papermc.io/William278/HuskHomes/versions/4.11-7a2d09a
     *  - William278/HuskHomes
     */
    public static ResolvedUrlInfo parseHangarUrl(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            // Must contain hangar.papermc.io to be treated as Hangar URL
            if (!trimmed.contains("hangar.papermc.io")) return null;

            String[] parts = trimmed.split("/");
            // URL pattern: https://hangar.papermc.io/{author}/{slug}[/versions/{versionName}]
            String author = null;
            String slug = null;
            String versionName = null;

            // Find the part after "hangar.papermc.io"
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("hangar.papermc.io") && i + 2 < parts.length) {
                    author = parts[i + 1].split("\\?")[0].split("#")[0];
                    slug = parts[i + 2].split("\\?")[0].split("#")[0];
                    // Check for /versions/{versionName}
                    if (i + 3 < parts.length && "versions".equals(parts[i + 3]) && i + 4 < parts.length) {
                        versionName = parts[i + 4].split("\\?")[0].split("#")[0];
                    }
                    break;
                }
            }

            if (author != null && slug != null && !author.isBlank() && !slug.isBlank()) {
                return new ResolvedUrlInfo(author, slug, versionName);
            }
            return null;
        }

        // "author/slug" format
        if (trimmed.contains("/")) {
            String[] parts = trimmed.split("/", 3);
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                String versionName = parts.length >= 3 && !parts[2].isBlank() ? parts[2] : null;
                return new ResolvedUrlInfo(parts[0], parts[1], versionName);
            }
        }

        return null;
    }

    public static boolean isHangarUrl(String input) {
        if (input == null || input.isBlank()) return false;
        return input.contains("hangar.papermc.io") || (input.contains("/") && !input.startsWith("http"));
    }

    // ===== Helper: map loader name to Hangar platform key =====

    public static String toPlatformKey(String loader) {
        if (loader == null) return "PAPER";
        return switch (loader.toLowerCase()) {
            case "waterfall", "bungeecord" -> "WATERFALL";
            case "velocity" -> "VELOCITY";
            default -> "PAPER";
        };
    }

    // ===== Inner types =====

    public record SearchPage(List<HangarProject> projects, int totalCount) {}
    public record VersionPage(List<HangarVersion> versions, int totalCount) {}

    public static class ResolvedUrlInfo {
        public final String author;
        public final String slug;
        public final String versionName; // null if pointing at project root

        public ResolvedUrlInfo(String author, String slug, String versionName) {
            this.author = author;
            this.slug = slug;
            this.versionName = versionName;
        }
    }
}
