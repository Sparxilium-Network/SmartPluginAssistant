package com.sparxilium.smartpluginassistant.model;

public class UpdateResult {
    private final String latestVersionNumber;
    private final String latestDownloadUrl;
    private final String supportedGameVersions;
    private final String versionId; // Optional, for platforms that use internal version IDs

    public UpdateResult(String latestVersionNumber, String latestDownloadUrl, String supportedGameVersions, String versionId) {
        this.latestVersionNumber = latestVersionNumber;
        this.latestDownloadUrl = latestDownloadUrl;
        this.supportedGameVersions = supportedGameVersions;
        this.versionId = versionId;
    }

    public String getLatestVersionNumber() {
        return latestVersionNumber;
    }

    public String getLatestDownloadUrl() {
        return latestDownloadUrl;
    }

    public String getSupportedGameVersions() {
        return supportedGameVersions;
    }

    public String getVersionId() {
        return versionId;
    }
}
