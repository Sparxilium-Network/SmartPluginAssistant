package com.sparxilium.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthProject {
    @JsonProperty("id")
    private String id;

    @JsonProperty("slug")
    private String slug;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("body")
    private String body;

    @JsonProperty("categories")
    private List<String> categories;

    @JsonProperty("loaders")
    private List<String> loaders;

    @JsonProperty("game_versions")
    private List<String> gameVersions;

    @JsonProperty("icon_url")
    private String iconUrl;

    @JsonProperty("downloads")
    private int downloads;

    @JsonProperty("followers")
    private int followers;

    @JsonProperty("source_url")
    private String sourceUrl;

    @JsonProperty("issues_url")
    private String issuesUrl;

    @JsonProperty("wiki_url")
    private String wikiUrl;

    @JsonProperty("discord_url")
    private String discordUrl;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public List<String> getLoaders() {
        return loaders;
    }

    public void setLoaders(List<String> loaders) {
        this.loaders = loaders;
    }

    public List<String> getGameVersions() {
        return gameVersions;
    }

    public void setGameVersions(List<String> gameVersions) {
        this.gameVersions = gameVersions;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public int getDownloads() {
        return downloads;
    }

    public void setDownloads(int downloads) {
        this.downloads = downloads;
    }

    public int getFollowers() {
        return followers;
    }

    public void setFollowers(int followers) {
        this.followers = followers;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getIssuesUrl() {
        return issuesUrl;
    }

    public void setIssuesUrl(String issuesUrl) {
        this.issuesUrl = issuesUrl;
    }

    public String getWikiUrl() {
        return wikiUrl;
    }

    public void setWikiUrl(String wikiUrl) {
        this.wikiUrl = wikiUrl;
    }

    public String getDiscordUrl() {
        return discordUrl;
    }

    public void setDiscordUrl(String discordUrl) {
        this.discordUrl = discordUrl;
    }
}
