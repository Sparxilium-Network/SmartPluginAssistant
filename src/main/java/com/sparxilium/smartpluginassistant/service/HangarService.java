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
import com.sparxilium.smartpluginassistant.model.UpdateResult;
import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.ServerInstance;

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
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HangarService implements PluginRepository {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HangarService.class);
    private static final String BASE_URL = "https://hangar.papermc.io/api/v1";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant (https://github.com/Sparxilium-Network/SmartPluginAssistant)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HttpDownloadService downloadService;

    public HangarService(HttpDownloadService downloadService) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.downloadService = downloadService;
    }

    @Override
    public String getPlatformKey() {
        return "hangar";
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

    @Override
    public CompletableFuture<Map<InstalledPlugin, UpdateResult>> checkForUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        String platform = toPlatformKey(instance.getLoader());
        Map<InstalledPlugin, UpdateResult> resultMap = new java.util.concurrent.ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (InstalledPlugin plugin : plugins) {
            if (plugin.getHangarNamespace() == null || plugin.getHangarNamespace().isBlank()) continue;
            
            String[] parts = plugin.getHangarNamespace().split("/", 2);
            if (parts.length < 2) continue;
            String author = parts[0];
            String slug = parts[1];

            CompletableFuture<Void> f = getVersions(author, slug, platform, null, 0, 5)
                    .thenAccept(page -> {
                        if (page.versions().isEmpty()) return;

                        HangarVersion latest = null;
                        for (HangarVersion v : page.versions()) {
                            if (!instance.isAllowPrereleases() && v.isUnstable()) continue;
                            latest = v;
                            break;
                        }
                        if (latest == null) latest = page.versions().get(0);

                        String downloadUrl = null;
                        HangarVersion.PlatformDownload pd = null;
                        if ("PAPER".equalsIgnoreCase(platform) || "WATERFALL".equalsIgnoreCase(platform) || "VELOCITY".equalsIgnoreCase(platform)) {
                            pd = latest.getDownloads().get(platform);
                        } else {
                            pd = latest.getDownloads().values().stream().findFirst().orElse(null);
                        }
                        
                        if (pd != null && pd.downloadUrl != null) {
                            downloadUrl = pd.downloadUrl;
                        }
                        
                        if (downloadUrl != null) {
                            String supportedGames = "-";
                            List<String> gameVers = latest.getPlatformDependencies() != null && latest.getPlatformDependencies().containsKey(platform) ? 
                                latest.getPlatformDependencies().get(platform) : new ArrayList<>();
                            
                            if (!gameVers.isEmpty()) {
                                if (gameVers.size() > 2) {
                                    supportedGames = gameVers.get(0) + " ~ " + gameVers.get(gameVers.size() - 1);
                                } else {
                                    supportedGames = String.join(", ", gameVers);
                                }
                            }
                            resultMap.put(plugin, new UpdateResult(latest.getVersionNumber(), downloadUrl, supportedGames, latest.getVersionNumber()));
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
