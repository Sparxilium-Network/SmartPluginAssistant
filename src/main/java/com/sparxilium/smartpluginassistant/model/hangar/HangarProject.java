package com.sparxilium.smartpluginassistant.model.hangar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Represents a Hangar project returned from GET /api/v1/projects
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HangarProject {

    @JsonProperty("id")
    private long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("namespace")
    private Namespace namespace;

    @JsonProperty("description")
    private String description;

    @JsonProperty("category")
    private String category;

    @JsonProperty("avatarUrl")
    private String avatarUrl;

    @JsonProperty("stats")
    private Stats stats;

    @JsonProperty("lastUpdated")
    private String lastUpdated;

    @JsonProperty("supportedPlatforms")
    private Map<String, List<String>> supportedPlatforms;

    public long getId() { return id; }
    public String getName() { return name; }
    public Namespace getNamespace() { return namespace; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getAvatarUrl() { return avatarUrl; }
    public Stats getStats() { return stats; }
    public String getLastUpdated() { return lastUpdated; }
    public Map<String, List<String>> getSupportedPlatforms() { return supportedPlatforms; }

    /** Returns "author/slug" namespace string, e.g. "William278/HuskHomes" */
    public String getNamespaceString() {
        if (namespace == null) return "";
        return namespace.owner + "/" + namespace.slug;
    }

    public String getAuthor() {
        return namespace != null ? namespace.owner : "";
    }

    public String getSlug() {
        return namespace != null ? namespace.slug : name;
    }

    public long getDownloads() {
        return stats != null ? stats.downloads : 0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Namespace {
        @JsonProperty("owner")
        public String owner;
        @JsonProperty("slug")
        public String slug;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        @JsonProperty("downloads")
        public long downloads;
        @JsonProperty("stars")
        public long stars;
        @JsonProperty("views")
        public long views;
    }
}
