package com.chiliasmstudio.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthSearchResult {
    @JsonProperty("project_id")
    private String projectId;

    @JsonProperty("slug")
    private String slug;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("categories")
    private List<String> categories;

    @JsonProperty("client_side")
    private String clientSide;

    @JsonProperty("server_side")
    private String serverSide;

    @JsonProperty("project_type")
    private String projectType;

    @JsonProperty("downloads")
    private int downloads;

    @JsonProperty("icon_url")
    private String iconUrl;

    @JsonProperty("author")
    private String author;

    @JsonProperty("display_categories")
    private List<String> displayCategories;

    @JsonProperty("versions")
    private List<String> versions;

    @JsonProperty("follows")
    private int follows;

    @JsonProperty("date_modified")
    private String dateModified;

    @JsonProperty("gallery")
    private List<String> gallery;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
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

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public String getClientSide() {
        return clientSide;
    }

    public void setClientSide(String clientSide) {
        this.clientSide = clientSide;
    }

    public String getServerSide() {
        return serverSide;
    }

    public void setServerSide(String serverSide) {
        this.serverSide = serverSide;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public int getDownloads() {
        return downloads;
    }

    public void setDownloads(int downloads) {
        this.downloads = downloads;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public List<String> getDisplayCategories() {
        return displayCategories;
    }

    public void setDisplayCategories(List<String> displayCategories) {
        this.displayCategories = displayCategories;
    }

    public List<String> getVersions() {
        return versions;
    }

    public void setVersions(List<String> versions) {
        this.versions = versions;
    }

    public int getFollows() {
        return follows;
    }

    public void setFollows(int follows) {
        this.follows = follows;
    }

    public String getDateModified() {
        return dateModified;
    }

    public void setDateModified(String dateModified) {
        this.dateModified = dateModified;
    }

    public List<String> getGallery() {
        return gallery;
    }

    public void setGallery(List<String> gallery) {
        this.gallery = gallery;
    }
}
