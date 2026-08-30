package com.chiliasmstudio.smartpluginassistant.service;

import com.chiliasmstudio.smartpluginassistant.model.ModrinthProject;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthSearchResponse;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthSearchResult;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthVersion;
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
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "ChiliasmStudio/SmartPluginAssistant/1.0 (contact@chiliasmstudio.com)";

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
     * Search plugins on Modrinth with loader, game version and query filters
     */
    public CompletableFuture<ModrinthSearchResponse> searchPlugins(String query, String loader, String mcVersion, int offset, int limit) {
        StringBuilder facetsBuilder = new StringBuilder("[[\"project_type:plugin\"]");

        if (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) {
            facetsBuilder.append(",[\"loaders:").append(loader.toLowerCase()).append("\"]");
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

    /**
     * Get Project details by id or slug
     */
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

    /**
     * Get versions for a project, optionally filtered by loader and mc version
     */
    public CompletableFuture<List<ModrinthVersion>> getProjectVersions(String idOrSlug, String loader, String mcVersion) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL + "/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8) + "/version");
        List<String> params = new ArrayList<>();

        if (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) {
            params.add("loaders=" + URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]", StandardCharsets.UTF_8));
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

    /**
     * Check updates for multiple hashes using /v2/version_files/update
     */
    public CompletableFuture<Map<String, ModrinthVersion>> checkUpdates(List<String> sha1Hashes, String loader, String mcVersion) {
        if (sha1Hashes == null || sha1Hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("hashes", sha1Hashes);
        payload.put("algorithm", "sha1");
        if (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) {
            payload.put("loaders", List.of(loader.toLowerCase()));
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

    /**
     * Download a file from URL to destination path
     */
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

    /**
     * Helper to extract project slug from URL or string
     */
    public static String extractSlugOrId(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            // e.g. https://modrinth.com/plugin/viaversion or https://modrinth.com/mod/sodium
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
