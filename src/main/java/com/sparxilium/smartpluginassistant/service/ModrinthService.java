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

    public CompletableFuture<ModrinthVersion> resolveVersionByProjectAndVersion(String projectIdOrSlug, String versionIdOrNumber, List<String> preferredLoaders) {
        if (versionIdOrNumber == null || versionIdOrNumber.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        logger.info("resolveVersionByProjectAndVersion: querying direct /version/{} or matching from project '{}', preferredLoaders={}",
                versionIdOrNumber, projectIdOrSlug, preferredLoaders);
        // First try direct /version/{id}
        return getVersion(versionIdOrNumber)
                .handle((ver, ex) -> {
                    if (ver != null) {
                        logger.info("resolveVersionByProjectAndVersion: direct /version/{} hit!", versionIdOrNumber);
                        return CompletableFuture.completedFuture(ver);
                    }
                    logger.info("resolveVersionByProjectAndVersion: direct getVersion failed, fetching all versions for project '{}' to match '{}'...",
                            projectIdOrSlug, versionIdOrNumber);
                    // If not found by ID (e.g. it's a version_number like 4.11-7a2d09a), fetch project all versions and match
                    return getProjectVersions(projectIdOrSlug, Collections.emptyList(), null)
                            .thenApply(list -> {
                                logger.info("resolveVersionByProjectAndVersion: project has {} total versions on Modrinth", list.size());
                                List<ModrinthVersion> matchingVersions = new ArrayList<>();
                                for (ModrinthVersion v : list) {
                                    if (versionIdOrNumber.equalsIgnoreCase(v.getVersionNumber()) ||
                                        versionIdOrNumber.equalsIgnoreCase(v.getId())) {
                                        matchingVersions.add(v);
                                    }
                                }

                                if (!matchingVersions.isEmpty()) {
                                    // If multiple versions have same version_number (e.g. Spigot build vs Fabric build), prioritize preferred loader
                                    if (preferredLoaders != null && !preferredLoaders.isEmpty()) {
                                        for (ModrinthVersion mv : matchingVersions) {
                                            if (mv.getLoaders() != null && mv.getLoaders().stream().anyMatch(l -> preferredLoaders.stream().anyMatch(pl -> pl.equalsIgnoreCase(l)))) {
                                                logger.info("resolveVersionByProjectAndVersion: matched version with preferred loader: id='{}', loaders={}, files={}",
                                                        mv.getId(), mv.getLoaders(), mv.getFiles() != null ? mv.getFiles().stream().map(ModrinthVersion.ModrinthFile::getFilename).toList() : "[]");
                                                return mv;
                                            }
                                        }
                                    }
                                    ModrinthVersion first = matchingVersions.get(0);
                                    logger.info("resolveVersionByProjectAndVersion: matched version (first candidate): id='{}', loaders={}", first.getId(), first.getLoaders());
                                    return first;
                                }

                                logger.warn("resolveVersionByProjectAndVersion: no exact match found for '{}', returning first available version: {}",
                                        versionIdOrNumber, list.isEmpty() ? "none" : list.get(0).getVersionNumber());
                                return list.isEmpty() ? null : list.get(0);
                            });
                })
                .thenCompose(f -> f);
    }

    public CompletableFuture<Map<String, ModrinthVersion>> getVersionsByHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("hashes", hashes);
        payload.put("algorithm", "sha512");

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/version_files"))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            logger.warn("getVersionsByHashes returned HTTP {}", response.statusCode());
                            return Collections.<String, ModrinthVersion>emptyMap();
                        }
                        try {
                            return objectMapper.readValue(response.body(), new TypeReference<Map<String, ModrinthVersion>>() {});
                        } catch (Exception e) {
                            logger.error("Failed to parse getVersionsByHashes response: {}", e.getMessage(), e);
                            return Collections.<String, ModrinthVersion>emptyMap();
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<ModrinthVersion> getVersionByHash(String hash) {
        String url = BASE_URL + "/version_file/" + URLEncoder.encode(hash, StandardCharsets.UTF_8) + "?algorithm=sha512";
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

    public CompletableFuture<Map<String, ModrinthVersion>> checkUpdates(List<String> hashes, List<String> loaders, String mcVersion) {
        if (hashes == null || hashes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("hashes", hashes);
        payload.put("algorithm", "sha512");
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

    public CompletableFuture<Map<String, ModrinthVersion>> checkUpdates(List<String> hashes, String loader, String mcVersion) {
        List<String> loaders = (loader != null && !loader.equalsIgnoreCase("all") && !loader.isBlank()) ? List.of(loader) : Collections.emptyList();
        return checkUpdates(hashes, loaders, mcVersion);
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

    public static class ResolvedUrlInfo {
        public String projectSlug;
        public String specificVersionId; // null if not pointing to a specific version

        public ResolvedUrlInfo(String projectSlug, String specificVersionId) {
            this.projectSlug = projectSlug;
            this.specificVersionId = specificVersionId;
        }
    }

    public static ResolvedUrlInfo parseUrlInfo(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            String[] parts = trimmed.split("/");
            String slug = null;
            String verId = null;
            for (int i = 0; i < parts.length; i++) {
                if ((parts[i].equals("plugin") || parts[i].equals("mod") || parts[i].equals("project")) && i + 1 < parts.length) {
                    slug = parts[i + 1].split("\\?")[0].split("#")[0];
                }
                if (parts[i].equals("version") && i + 1 < parts.length) {
                    verId = parts[i + 1].split("\\?")[0].split("#")[0];
                }
            }
            if (slug == null && parts.length > 0) {
                slug = parts[parts.length - 1].split("\\?")[0].split("#")[0];
            }
            return new ResolvedUrlInfo(slug, verId);
        }
        return new ResolvedUrlInfo(trimmed, null);
    }

    public static String extractSlugOrId(String input) {
        ResolvedUrlInfo info = parseUrlInfo(input);
        return info != null ? info.projectSlug : null;
    }
}
