package com.sparxilium.smartpluginassistant.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class PluginMetadataStore {
    private static final Logger logger = LogManager.getLogger(PluginMetadataStore.class);
    private static final String METADATA_FILE_NAME = ".plugin_metadata.json";
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DownloadRecord {
        @JsonProperty("projectId")
        public String projectId;

        @JsonProperty("versionId")
        public String versionId;

        @JsonProperty("versionNumber")
        public String versionNumber;

        @JsonProperty("fileName")
        public String fileName;

        @JsonProperty("sha1")
        public String sha1;

        @JsonProperty("downloadedAt")
        public LocalDateTime downloadedAt;

        /** "modrinth", "hangar", or "local" */
        @JsonProperty("hostingPlatform")
        public String hostingPlatform;

        /** For Hangar plugins: "author/slug" e.g. "William278/HuskHomes" */
        @JsonProperty("hangarNamespace")
        public String hangarNamespace;

        public DownloadRecord() {}

        public DownloadRecord(String projectId, String versionId, String versionNumber, String fileName, String sha1) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.versionNumber = versionNumber;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.downloadedAt = LocalDateTime.now();
        }
    }

    private static Path getMetadataFilePath(InstanceManager instanceManager, ServerInstance instance) {
        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        return pluginsDir.resolve(METADATA_FILE_NAME);
    }

    public static synchronized Map<String, DownloadRecord> loadRecords(InstanceManager instanceManager, ServerInstance instance) {
        Path file = getMetadataFilePath(instanceManager, instance);
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<Map<String, DownloadRecord>>() {});
        } catch (IOException e) {
            logger.warn("Failed to read plugin metadata records from {}: {}", file, e.getMessage());
            return new HashMap<>();
        }
    }

    public static synchronized void saveRecord(InstanceManager instanceManager, ServerInstance instance, DownloadRecord record) {
        if (record == null || (record.fileName == null && record.sha1 == null)) return;
        Map<String, DownloadRecord> records = loadRecords(instanceManager, instance);

        if (record.fileName != null) {
            records.put(record.fileName.toLowerCase(), record);
            String cleanName = record.fileName.replace(".disabled", "").toLowerCase();
            records.put(cleanName, record);
        }
        if (record.sha1 != null && !record.sha1.isBlank()) {
            records.put(record.sha1.toLowerCase(), record);
        }

        Path file = getMetadataFilePath(instanceManager, instance);
        try {
            if (file.getParent() != null && !Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), records);
            logger.info("Saved plugin download record for file: {}, version: {}", record.fileName, record.versionNumber);
        } catch (IOException e) {
            logger.error("Failed to save plugin metadata record to {}: {}", file, e.getMessage());
        }
    }

    public static synchronized DownloadRecord findRecord(InstanceManager instanceManager, ServerInstance instance, String fileName, String sha1) {
        Map<String, DownloadRecord> records = loadRecords(instanceManager, instance);
        if (sha1 != null && records.containsKey(sha1.toLowerCase())) {
            return records.get(sha1.toLowerCase());
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (records.containsKey(lower)) {
                return records.get(lower);
            }
            String clean = lower.replace(".disabled", "");
            if (records.containsKey(clean)) {
                return records.get(clean);
            }
        }
        return null;
    }
}
