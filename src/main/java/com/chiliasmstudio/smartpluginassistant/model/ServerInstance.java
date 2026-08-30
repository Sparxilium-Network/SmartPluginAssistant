package com.chiliasmstudio.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerInstance {
    private String id;
    private String name;
    private String loader; // paper, spigot, purpur, folia, velocity, bungeecord, etc.
    private String mcVersion; // e.g. 1.20.4, 1.21.1
    private String customDirectory; // optional custom path, else default instances/<id>
    private String icon; // icon name or color
    private LocalDateTime createdAt;
    private LocalDateTime lastModifiedAt;

    public ServerInstance() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
        this.lastModifiedAt = LocalDateTime.now();
        this.loader = "paper";
        this.mcVersion = "1.21";
    }

    public ServerInstance(String name, String loader, String mcVersion) {
        this();
        this.name = name;
        this.loader = loader;
        this.mcVersion = mcVersion;
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
