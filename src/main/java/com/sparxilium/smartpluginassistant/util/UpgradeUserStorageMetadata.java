package com.sparxilium.smartpluginassistant.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class UpgradeUserStorageMetadata {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UpgradeUserStorageMetadata.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public static void main(String[] args) {
        String userHome = System.getProperty("user.home");
        Path instancesDir = Paths.get(userHome, ".smartpluginassistant", "instances");
        if (!Files.exists(instancesDir)) {
            System.out.println("No instances directory found at " + instancesDir);
            return;
        }

        try (var stream = Files.list(instancesDir)) {
            for (Path instDir : stream.filter(Files::isDirectory).toList()) {
                Path pluginsDir = instDir.resolve("plugins");
                if (!Files.exists(pluginsDir)) continue;

                Path metaFile = pluginsDir.resolve(".plugin_metadata.json");
                Map<String, Map<String, Object>> oldRecords = new HashMap<>();
                if (Files.exists(metaFile)) {
                    try {
                        oldRecords = mapper.readValue(metaFile.toFile(), new TypeReference<Map<String, Map<String, Object>>>() {});
                    } catch (Exception e) {
                        System.err.println("Error reading " + metaFile + ": " + e.getMessage());
                    }
                }

                Map<String, PluginMetadataStore.DownloadRecord> newRecords = new LinkedHashMap<>();

                // 1. Walk through existing jars and compute SHA-512
                try (var jarStream = Files.list(pluginsDir)) {
                    for (Path jarPath : jarStream.toList()) {
                        String fName = jarPath.getFileName().toString();
                        if (!fName.toLowerCase().endsWith(".jar") && !fName.toLowerCase().endsWith(".jar.disabled")) continue;

                        String sha512 = PluginManagerService.calculateSha512(jarPath.toFile());
                        if (sha512 == null) continue;

                        // Match against oldRecords by filename or previous records
                        Map<String, Object> found = null;
                        if (oldRecords.containsKey(fName.toLowerCase())) {
                            found = oldRecords.get(fName.toLowerCase());
                        } else if (oldRecords.containsKey(fName.replace(".disabled", "").toLowerCase())) {
                            found = oldRecords.get(fName.replace(".disabled", "").toLowerCase());
                        } else {
                            for (Map<String, Object> r : oldRecords.values()) {
                                if (fName.equalsIgnoreCase((String) r.get("fileName")) ||
                                        fName.replace(".disabled", "").equalsIgnoreCase(((String) r.get("fileName")).replace(".disabled", ""))) {
                                    found = r;
                                    break;
                                }
                            }
                        }

                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord();
                        record.fileName = fName;
                        record.sha512 = sha512;
                        if (found != null) {
                            record.projectId = (String) found.get("projectId");
                            record.versionId = (String) found.get("versionId");
                            record.versionNumber = (String) found.get("versionNumber");
                            record.hostingPlatform = (String) found.get("hostingPlatform");
                            record.hangarNamespace = (String) found.get("hangarNamespace");
                        }

                        newRecords.put(fName.toLowerCase(), record);
                        String clean = fName.replace(".disabled", "").toLowerCase();
                        newRecords.put(clean, record);
                        newRecords.put(sha512.toLowerCase(), record);
                    }
                }

                // 2. Also preserve any records that were previously installed or had sha512 (excluding 40-character sha1 keys)
                for (Map.Entry<String, Map<String, Object>> entry : oldRecords.entrySet()) {
                    String key = entry.getKey();
                    if (key.length() == 40 && key.matches("^[0-9a-fA-F]+$")) {
                        // Drop SHA-1 hex key
                        continue;
                    }
                    Map<String, Object> r = entry.getValue();
                    if (r != null) {
                        String s512 = (String) r.get("sha512");
                        String fName = (String) r.get("fileName");
                        if (fName != null && !newRecords.containsKey(key)) {
                            PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord();
                            record.fileName = fName;
                            record.sha512 = s512;
                            record.projectId = (String) r.get("projectId");
                            record.versionId = (String) r.get("versionId");
                            record.versionNumber = (String) r.get("versionNumber");
                            record.hostingPlatform = (String) r.get("hostingPlatform");
                            record.hangarNamespace = (String) r.get("hangarNamespace");
                            newRecords.put(key, record);
                            if (s512 != null) {
                                newRecords.put(s512.toLowerCase(), record);
                            }
                        }
                    }
                }

                mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), newRecords);
                System.out.println("Upgraded metadata for instance: " + instDir.getFileName() + " (" + newRecords.size() + " entries)");
            }
        } catch (IOException e) {
            logger.error("Exception occurred during upgrade", e);
        }
    }
}
