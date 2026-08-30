package com.sparxilium.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InstalledPlugin {
    private String fileName;
    private String projectId;
    private String versionId;
    private String pluginName;
    private String currentVersionNumber;
    private String sha1;
    private String sha512;
    private long fileSizeBytes;
    private long lastModifiedTime;
    private boolean enabled;
    private boolean selected;
    private boolean loaderIncompatible;
    private String supportedLoadersSummary;

    private boolean updateAvailable;
    private String latestVersionNumber;
    private String latestVersionId;
    private String latestDownloadUrl;
    private String latestFileName;
    private String supportedGameVersions = "-";

    public InstalledPlugin() {
        this.enabled = true;
    }

    public InstalledPlugin(String fileName, String sha1, long fileSizeBytes, long lastModifiedTime, boolean enabled) {
        this.fileName = fileName;
        this.sha1 = sha1;
        this.fileSizeBytes = fileSizeBytes;
        this.lastModifiedTime = lastModifiedTime;
        this.enabled = enabled;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getPluginName() {
        return pluginName != null ? pluginName : (fileName != null ? fileName.replace(".jar", "").replace(".disabled", "") : "Unknown");
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getCurrentVersionNumber() {
        return currentVersionNumber != null ? currentVersionNumber : "Unknown";
    }

    public void setCurrentVersionNumber(String currentVersionNumber) {
        this.currentVersionNumber = currentVersionNumber;
    }

    public String getSha1() {
        return sha1;
    }

    public void setSha1(String sha1) {
        this.sha1 = sha1;
    }

    public String getSha512() {
        return sha512;
    }

    public void setSha512(String sha512) {
        this.sha512 = sha512;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public String getFormattedLastModified() {
        if (lastModifiedTime <= 0) return "-";
        try {
            java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(lastModifiedTime)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime();
            return dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return "-";
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isLoaderIncompatible() {
        return loaderIncompatible;
    }

    public void setLoaderIncompatible(boolean loaderIncompatible) {
        this.loaderIncompatible = loaderIncompatible;
    }

    public String getSupportedLoadersSummary() {
        return supportedLoadersSummary;
    }

    public void setSupportedLoadersSummary(String supportedLoadersSummary) {
        this.supportedLoadersSummary = supportedLoadersSummary;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public void setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
    }

    public String getLatestVersionNumber() {
        return latestVersionNumber;
    }

    public void setLatestVersionNumber(String latestVersionNumber) {
        this.latestVersionNumber = latestVersionNumber;
    }

    public String getLatestVersionId() {
        return latestVersionId;
    }

    public void setLatestVersionId(String latestVersionId) {
        this.latestVersionId = latestVersionId;
    }

    public String getLatestDownloadUrl() {
        return latestDownloadUrl;
    }

    public void setLatestDownloadUrl(String latestDownloadUrl) {
        this.latestDownloadUrl = latestDownloadUrl;
    }

    public String getLatestFileName() {
        return latestFileName;
    }

    public void setLatestFileName(String latestFileName) {
        this.latestFileName = latestFileName;
    }

    public String getSupportedGameVersions() {
        return supportedGameVersions != null ? supportedGameVersions : "-";
    }

    public void setSupportedGameVersions(String supportedGameVersions) {
        this.supportedGameVersions = supportedGameVersions;
    }

    public String getFormattedSize() {
        if (fileSizeBytes <= 0) return "0 B";
        if (fileSizeBytes < 1024) return fileSizeBytes + " B";
        if (fileSizeBytes < 1024 * 1024) return String.format("%.1f KB", fileSizeBytes / 1024.0);
        return String.format("%.2f MB", fileSizeBytes / (1024.0 * 1024.0));
    }
}
