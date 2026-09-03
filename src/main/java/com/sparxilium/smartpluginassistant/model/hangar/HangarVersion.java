package com.sparxilium.smartpluginassistant.model.hangar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Represents a single version returned from GET /api/v1/projects/{author}/{slug}/versions
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HangarVersion {

    @JsonProperty("id")
    private long id;

    @JsonProperty("projectId")
    private long projectId;

    /** Version name / number, e.g. "4.11-7a2d09a" */
    @JsonProperty("name")
    private String name;

    @JsonProperty("author")
    private String author;

    @JsonProperty("channel")
    private Channel channel;

    /** Map of platform -> PlatformDownload. Key: "PAPER", "WATERFALL", "VELOCITY" */
    @JsonProperty("downloads")
    private Map<String, PlatformDownload> downloads;

    /** Map of platform -> list of supported MC versions */
    @JsonProperty("platformDependencies")
    private Map<String, List<String>> platformDependencies;

    /** Map of platform -> list of plugin dependencies */
    @JsonProperty("pluginDependencies")
    private Map<String, List<HangarDependency>> pluginDependencies;

    @JsonProperty("description")
    private String description;

    @JsonProperty("createdAt")
    private String createdAt;

    // --- Getters ---

    public long getId() { return id; }
    public long getProjectId() { return projectId; }

    /** Version number string, same as what Hangar calls "name" */
    public String getVersionNumber() { return name; }
    public String getName() { return name; }
    public String getAuthor() { return author; }

    public Channel getChannel() { return channel; }
    public Map<String, PlatformDownload> getDownloads() { return downloads; }
    public Map<String, List<String>> getPlatformDependencies() { return platformDependencies; }
    public Map<String, List<HangarDependency>> getPluginDependencies() { return pluginDependencies; }
    public String getDescription() { return description; }
    public String getCreatedAt() { return createdAt; }

    /** Returns true if this version is considered unstable (Alpha/Beta) */
    public boolean isUnstable() {
        return channel != null && channel.isUnstable();
    }

    /** Returns "alpha" or "beta" based on channel flags, or "release" if stable */
    public String getVersionType() {
        if (channel == null) return "release";
        String channelName = channel.getName() != null ? channel.getName().toLowerCase() : "";
        if (channel.isUnstable()) {
            if (channelName.contains("alpha")) return "alpha";
            return "beta";
        }
        return "release";
    }

    /** Gets the download info for PAPER platform */
    public PlatformDownload getPaperDownload() {
        if (downloads == null) return null;
        return downloads.get("PAPER");
    }

    /** Gets the download URL for PAPER platform */
    public String getPaperDownloadUrl() {
        PlatformDownload pd = getPaperDownload();
        return pd != null ? pd.downloadUrl : null;
    }

    /** Gets the file info for PAPER platform */
    public FileInfo getPaperFileInfo() {
        PlatformDownload pd = getPaperDownload();
        return pd != null ? pd.fileInfo : null;
    }

    /** Gets supported MC versions for PAPER platform */
    public List<String> getPaperVersions() {
        if (platformDependencies == null) return List.of();
        List<String> vers = platformDependencies.get("PAPER");
        return vers != null ? vers : List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Channel {
        @JsonProperty("name")
        public String name;
        @JsonProperty("color")
        public String color;
        @JsonProperty("flags")
        public List<String> flags;

        public String getName() { return name; }
        public String getColor() { return color; }
        public List<String> getFlags() { return flags; }

        public boolean isUnstable() {
            return flags != null && flags.contains("UNSTABLE");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlatformDownload {
        @JsonProperty("downloadUrl")
        public String downloadUrl;
        @JsonProperty("fileInfo")
        public FileInfo fileInfo;
        @JsonProperty("externalUrl")
        public String externalUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileInfo {
        @JsonProperty("name")
        public String name;
        @JsonProperty("sizeBytes")
        public long sizeBytes;
        @JsonProperty("sha256Hash")
        public String sha256Hash;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HangarDependency {
        @JsonProperty("required")
        public boolean required;
        @JsonProperty("namespace")
        public HangarProject.Namespace namespace;
        @JsonProperty("externalUrl")
        public String externalUrl;

        public boolean isRequired() { return required; }
        public String getNamespaceString() {
            return namespace != null ? namespace.owner + "/" + namespace.slug : null;
        }
    }
}
