package com.sparxilium.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthVersion {
    @JsonProperty("id")
    private String id;

    @JsonProperty("project_id")
    private String projectId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("version_number")
    private String versionNumber;

    @JsonProperty("changelog")
    private String changelog;

    @JsonProperty("game_versions")
    private List<String> gameVersions;

    @JsonProperty("version_type")
    private String versionType;

    @JsonProperty("loaders")
    private List<String> loaders;

    @JsonProperty("files")
    private List<ModrinthFile> files;

    @JsonProperty("dependencies")
    private List<ModrinthDependency> dependencies;

    @JsonProperty("date_published")
    private String datePublished;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(String versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public List<String> getGameVersions() {
        return gameVersions;
    }

    public void setGameVersions(List<String> gameVersions) {
        this.gameVersions = gameVersions;
    }

    public String getVersionType() {
        return versionType;
    }

    public void setVersionType(String versionType) {
        this.versionType = versionType;
    }

    public List<String> getLoaders() {
        return loaders;
    }

    public void setLoaders(List<String> loaders) {
        this.loaders = loaders;
    }

    public List<ModrinthFile> getFiles() {
        return files;
    }

    public void setFiles(List<ModrinthFile> files) {
        this.files = files;
    }

    public String getDatePublished() {
        return datePublished;
    }

    public void setDatePublished(String datePublished) {
        this.datePublished = datePublished;
    }

    public List<ModrinthDependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<ModrinthDependency> dependencies) {
        this.dependencies = dependencies;
    }

    public ModrinthFile getPrimaryFile() {
        if (files == null || files.isEmpty()) return null;
        for (ModrinthFile f : files) {
            if (f.isPrimary()) return f;
        }
        return files.get(0);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModrinthDependency {
        @JsonProperty("version_id")
        private String versionId;

        @JsonProperty("project_id")
        private String projectId;

        @JsonProperty("file_name")
        private String fileName;

        @JsonProperty("dependency_type")
        private String dependencyType; // "required", "optional", "incompatible", "embedded"

        public String getVersionId() {
            return versionId;
        }

        public void setVersionId(String versionId) {
            this.versionId = versionId;
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getDependencyType() {
            return dependencyType;
        }

        public void setDependencyType(String dependencyType) {
            this.dependencyType = dependencyType;
        }

        public boolean isRequired() {
            return "required".equalsIgnoreCase(dependencyType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModrinthFile {
        @JsonProperty("hashes")
        private Map<String, String> hashes;

        @JsonProperty("url")
        private String url;

        @JsonProperty("filename")
        private String filename;

        @JsonProperty("primary")
        private boolean primary;

        @JsonProperty("size")
        private long size;

        public Map<String, String> getHashes() {
            return hashes;
        }

        public void setHashes(Map<String, String> hashes) {
            this.hashes = hashes;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public boolean isPrimary() {
            return primary;
        }

        public void setPrimary(boolean primary) {
            this.primary = primary;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }
}
