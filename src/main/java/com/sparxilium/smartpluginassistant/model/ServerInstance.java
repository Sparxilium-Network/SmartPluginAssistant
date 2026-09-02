package com.sparxilium.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerInstance {
    public static final int CURRENT_CONFIG_VERSION = 1;

    private int configVersion = CURRENT_CONFIG_VERSION;
    private String id;
    private String name;
    private String loader; // paper, spigot, purpur, folia, velocity, bungeecord, etc.
    private String mcVersion; // e.g. 1.20.4, 1.21.1
    private String customDirectory; // optional custom path, else default instances/<id>
    private String icon; // icon name or color
    private java.util.List<String> extraCompatibleLoaders = new java.util.ArrayList<>();
    private boolean allowPrereleases = false; // allow beta and alpha updates
    private java.util.Map<String, String> apiTokens = new java.util.HashMap<>();
    private LocalDateTime createdAt;
    private LocalDateTime lastModifiedAt;

    public ServerInstance() {
        this.createdAt = LocalDateTime.now();
        this.lastModifiedAt = LocalDateTime.now();
        this.loader = "paper";
        this.mcVersion = "1.21.1";
        this.extraCompatibleLoaders = new java.util.ArrayList<>();
        this.allowPrereleases = false;
    }

    public java.util.List<String> getExtraCompatibleLoaders() {
        return extraCompatibleLoaders != null ? extraCompatibleLoaders : java.util.List.of();
    }

    public void setExtraCompatibleLoaders(java.util.List<String> extraCompatibleLoaders) {
        this.extraCompatibleLoaders = extraCompatibleLoaders != null ? extraCompatibleLoaders : new java.util.ArrayList<>();
    }

    /**
     * Get available compatible loaders that this core can potentially load.
     * e.g., folia -> paper, spigot, bukkit, purpur
     * paper -> spigot, bukkit
     * purpur -> paper, spigot, bukkit
     * spigot -> bukkit
     * velocity -> none (empty)
     * bungeecord -> waterfall
     */
    public static java.util.List<String> getAvailableCompatibleLoadersFor(String loaderName) {
        if (loaderName == null) return java.util.List.of();
        switch (loaderName.toLowerCase()) {
            case "folia":
                return java.util.List.of("paper", "spigot", "bukkit", "purpur");
            case "paper":
                return java.util.List.of("spigot", "bukkit");
            case "purpur":
                return java.util.List.of("paper", "spigot", "bukkit");
            case "spigot":
                return java.util.List.of("bukkit");
            case "bungeecord":
                return java.util.List.of("waterfall");
            default:
                return java.util.List.of(); // velocity, fabric, sponge, etc. have no downstream
        }
    }

    /**
     * Returns list of effective loaders: primary loader + checked extra compatible loaders
     */
    public java.util.List<String> getEffectiveLoaders() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (loader != null && !loader.isBlank()) {
            result.add(loader.toLowerCase());
        }
        if (extraCompatibleLoaders != null) {
            for (String l : extraCompatibleLoaders) {
                if (l != null && !l.isBlank()) {
                    result.add(l.toLowerCase());
                }
            }
        }
        return new java.util.ArrayList<>(result);
    }

    public ServerInstance(String name, String loader, String mcVersion) {
        this();
        this.name = name;
        this.id = sanitizeFileName(name);
        this.loader = loader;
        this.mcVersion = mcVersion;
    }

    public static String sanitizeFileName(String name) {
        if (name == null) return "server";
        // Replace invalid filesystem characters: \ / : * ? " < > | with _
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "server" : sanitized;
    }

    public static boolean isValidFileName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        // Check if contains illegal filename characters
        return !name.matches(".*[\\\\/:*?\"<>|].*");
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLoader() {
        return loader;
    }

    public void setLoader(String loader) {
        this.loader = loader;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public String getCustomDirectory() {
        return customDirectory;
    }

    public void setCustomDirectory(String customDirectory) {
        this.customDirectory = customDirectory;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public boolean isAllowPrereleases() {
        return allowPrereleases;
    }

    public void setAllowPrereleases(boolean allowPrereleases) {
        this.allowPrereleases = allowPrereleases;
    }

    public java.util.Map<String, String> getApiTokens() {
        if (apiTokens == null) {
            apiTokens = new java.util.HashMap<>();
        }
        return apiTokens;
    }

    public void setApiTokens(java.util.Map<String, String> apiTokens) {
        this.apiTokens = apiTokens != null ? apiTokens : new java.util.HashMap<>();
    }

    public String getApiToken(String platform) {
        if (apiTokens == null || platform == null) return null;
        String val = apiTokens.get(platform.toLowerCase());
        return (val != null && !val.isBlank()) ? val.trim() : null;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(LocalDateTime lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    @Override
    public String toString() {
        return name + " (" + loader + " - " + mcVersion + ")";
    }
}
