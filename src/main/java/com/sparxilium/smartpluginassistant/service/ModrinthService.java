package com.sparxilium.smartpluginassistant.service;

import com.sparxilium.smartpluginassistant.model.ModrinthProject;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResponse;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResult;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ModrinthService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ModrinthService.class);
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "Sparxilium/SmartPluginAssistant/1.0 (contact@sparxilium.com)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ModrinthService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetch all release game versions from Modrinth API sorted newest first
     */
    public CompletableFuture<List<String>> fetchGameVersions() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/tag/game_version"))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return List.of("1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2");
                    }
                    try {
                        List<Map<String, Object>> list = objectMapper.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() {});
                        // Filter releases and extract names
                        List<Map<String, Object>> filtered = list.stream()
                                .filter(m -> "release".equals(m.get("version_type")))
                                .toList();
                        
                        // Sort by date descending (newest first)
                        List<String> sortedVersions = new ArrayList<>(filtered.stream()
                                .sorted((a, b) -> {
                                    String dateA = (String) a.get("date");
                                    String dateB = (String) b.get("date");
                                    if (dateA == null) return 1;
                                    if (dateB == null) return -1;
                                    return dateB.compareTo(dateA); // Reverse sorting (descending)
                                })
                                .map(m -> (String) m.get("version"))
                                .toList());

                        if (sortedVersions.isEmpty()) {
                            return List.of("1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2");
                        }
                        return sortedVersions;
                    } catch (Exception e) {
                        return List.of("1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2");
                    }
                });
    }

    public CompletableFuture<ModrinthSearchResponse> searchPlugins(String query, List<String> loaders, String mcVersion, int offset, int limit) {
        StringBuilder facetsBuilder = new StringBuilder("[[\"project_type:plugin\"]");

        if (loaders != null && !loaders.isEmpty()) {
            StringBuilder loadersFacet = new StringBuilder("[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) loadersFacet.append(",");
                loadersFacet.append("\"loaders:").append(loaders.get(i).toLowerCase()).append("\"");
            }
            loadersFacet.append("]");
            facetsBuilder.append(",").append(loadersFacet);
        }

        if (mcVersion != null && !mcVersion.equalsIgnoreCase("all") && !mcVersion.isBlank()) {
            facetsBuilder.append(",[\"versions:").append(mcVersion).append("\"]");
        }
        facetsBuilder.append("]");

        String facets = URLEncoder.encode(facetsBuilder.toString(), StandardCharsets.UTF_8);
        String encodedQuery = URLEncoder.encode(query != null ? query : "", StandardCharsets.UTF_8);

        String url = String.format("%s/search?query=%s&facets=%s&offset=%d&limit=%d&index=relevance",
                BASE_URL, encodedQuery, facets, offset, limit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Modrinth API returned status " + response.statusCode() + ": " + response.body());
                    }
                    try {
                        return objectMapper.readValue(response.body(), ModrinthSearchResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse search response: " + e.getMessage(), e);
                    }
                });
    }

    public CompletableFuture<ModrinthSearchResponse> searchPlugins(String query, String loader, String mcVersion, int offset, int limit) {
        List<String> loaders = (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) ? List.of(loader) : Collections.emptyList();
        return searchPlugins(query, loaders, mcVersion, offset, limit);
    }

    public CompletableFuture<ModrinthProject> getProject(String idOrSlug) {
        String url = BASE_URL + "/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Project not found (" + response.statusCode() + "): " + idOrSlug);
                    }
                    try {
                        return objectMapper.readValue(response.body(), ModrinthProject.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse project: " + e.getMessage(), e);
                    }
                });
    }

    public CompletableFuture<List<ModrinthVersion>> getProjectVersions(String idOrSlug, List<String> loaders, String mcVersion) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8) + "/version");
        List<String> params = new ArrayList<>();

        if (loaders != null && !loaders.isEmpty()) {
            try {
                String loadersJson = objectMapper.writeValueAsString(loaders);
                params.add("loaders=" + URLEncoder.encode(loadersJson, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        if (mcVersion != null && !mcVersion.equalsIgnoreCase("all") && !mcVersion.isBlank()) {
            params.add("game_versions=" + URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8));
        }

        if (!params.isEmpty()) {
            urlBuilder.append("?").append(String.join("&", params));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Failed to fetch versions: " + response.statusCode());
                    }
                    try {
                        return objectMapper.readValue(response.body(), new TypeReference<List<ModrinthVersion>>() {});
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse versions: " + e.getMessage(), e);
                    }
                });
    }

    public CompletableFuture<ModrinthVersion> getVersion(String versionId) {
        String url = BASE_URL + "/version/" + URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Version not found (" + response.statusCode() + "): " + versionId);
                    }
                    try {
                        return objectMapper.readValue(response.body(), ModrinthVersion.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse version: " + e.getMessage(), e);
                    }
                });
    }

    public CompletableFuture<ModrinthVersion> getVersionByHash(String sha1Hash) {
        String url = BASE_URL + "/version_file/" + URLEncoder.encode(sha1Hash, StandardCharsets.UTF_8) + "?algorithm=sha1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    try {
                        return objectMapper.readValue(response.body(), ModrinthVersion.class);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    public CompletableFuture<List<ModrinthVersion>> getProjectVersions(String idOrSlug, String loader, String mcVersion) {
        List<String> loaders = (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) ? List.of(loader) : Collections.emptyList();
        return getProjectVersions(idOrSlug, loaders, mcVersion);
    }

    public CompletableFuture<Map<String, ModrinthVersion>> checkUpdates(List<String> sha1Hashes, List<String> loaders, String mcVersion) {
        if (sha1Hashes == null || sha1Hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("hashes", sha1Hashes);
        payload.put("algorithm", "sha1");
        if (loaders != null && !loaders.isEmpty()) {
            payload.put("loaders", loaders.stream().map(String::toLowerCase).toList());
        }
        if (mcVersion != null && !mcVersion.equalsIgnoreCase("all") && !mcVersion.isBlank()) {
            payload.put("game_versions", List.of(mcVersion));
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/version_files/update"))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new RuntimeException("Update check failed with code " + response.statusCode() + ": " + response.body());
                        }
                        try {
                            return objectMapper.readValue(response.body(), new TypeReference<Map<String, ModrinthVersion>>() {});
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse update results: " + e.getMessage(), e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Map<String, ModrinthVersion>> checkUpdates(List<String> sha1Hashes, String loader, String mcVersion) {
        List<String> loaders = (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) ? List.of(loader) : Collections.emptyList();
        return checkUpdates(sha1Hashes, loaders, mcVersion);
    }

    public CompletableFuture<Path> downloadFile(String fileUrl, Path destination, Consumer<Double> progressCallback) {
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
                        if (progressCallback != null) {
                            progressCallback.accept(1.0);
                        }
                        return destination;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save downloaded file: " + e.getMessage(), e);
                    }
                });
    }

    public static String extractSlugOrId(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            String[] parts = trimmed.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].equals("plugin") || parts[i].equals("mod") || parts[i].equals("project")) {
                    return parts[i + 1].split("\\?")[0].split("#")[0];
                }
            }
            return parts[parts.length - 1].split("\\?")[0].split("#")[0];
        }
        return trimmed;
    }
}
