package com.sparxilium.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VoxelProduct {
    private long id;
    private String title;
    private String subtitle;
    private String price;
    private String currency;
    private boolean canDownload;
    private String url;
    private Owner owner;
    private String thumbnailURL;
    private String headerURL;
    private String supportedServerSoftware;
    private String supportedMinecraftVersions;
    private Long creationTime;
    private Long lastUpdateTime;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Owner {
        private String name;
        private String url;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isCanDownload() {
        return canDownload;
    }

    public void setCanDownload(boolean canDownload) {
        this.canDownload = canDownload;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Owner getOwner() {
        return owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public String getThumbnailURL() {
        return thumbnailURL;
    }

    public void setThumbnailURL(String thumbnailURL) {
        this.thumbnailURL = thumbnailURL;
    }

    public String getHeaderURL() {
        return headerURL;
    }

    public void setHeaderURL(String headerURL) {
        this.headerURL = headerURL;
    }

    public String getSupportedServerSoftware() {
        return supportedServerSoftware;
    }

    public void setSupportedServerSoftware(String supportedServerSoftware) {
        this.supportedServerSoftware = supportedServerSoftware;
    }

    public String getSupportedMinecraftVersions() {
        return supportedMinecraftVersions;
    }

    public void setSupportedMinecraftVersions(String supportedMinecraftVersions) {
        this.supportedMinecraftVersions = supportedMinecraftVersions;
    }

    public Long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Long creationTime) {
        this.creationTime = creationTime;
    }

    public Long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(Long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public boolean isFree() {
        if (price == null) return true;
        try {
            return Double.parseDouble(price.trim()) == 0.0;
        } catch (Exception e) {
            return false;
        }
    }
}
