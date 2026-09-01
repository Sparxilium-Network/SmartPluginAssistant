package com.sparxilium.smartpluginassistant.service;

import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class InstanceManager {
    private static final String APP_DATA_DIR_NAME = ".smartpluginassistant";
    private static final String INSTANCE_CONFIG_FILE_NAME = "instance.json";
    private static final String LEGACY_INSTANCES_FILE_NAME = "instances.json";

    private final Path rootDataDir;
    private final Path defaultInstancesDir;
    private final ObjectMapper objectMapper;
    private final List<ServerInstance> instances = new ArrayList<>();

    public InstanceManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        String userHome = System.getProperty("user.home");
        this.rootDataDir = Paths.get(userHome, APP_DATA_DIR_NAME);
        this.defaultInstancesDir = rootDataDir.resolve("instances");

        initStorage();
        loadInstances();
    }

    private void initStorage() {
        try {
            if (!Files.exists(rootDataDir)) {
                Files.createDirectories(rootDataDir);
            }
            if (!Files.exists(defaultInstancesDir)) {
                Files.createDirectories(defaultInstancesDir);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void loadInstances() {
        instances.clear();

        // 1. Scan default instances directory for subdirectories containing instance.json (or auto-discover)
        if (Files.exists(defaultInstancesDir) && Files.isDirectory(defaultInstancesDir)) {
            try (var stream = Files.list(defaultInstancesDir)) {
                List<Path> dirs = stream.filter(Files::isDirectory).toList();
                for (Path dir : dirs) {
                    Path configFile = dir.resolve(INSTANCE_CONFIG_FILE_NAME);
                    if (Files.exists(configFile)) {
                        try {
                            ServerInstance inst = objectMapper.readValue(configFile.toFile(), ServerInstance.class);
                            if (inst != null) {
                                if (inst.getId() == null || inst.getId().isBlank()) {
                                    inst.setId(dir.getFileName().toString());
                                }
                                if (inst.getName() == null || inst.getName().isBlank()) {
                                    inst.setName(dir.getFileName().toString());
                                }
                                instances.add(inst);
                            }
                        } catch (Exception e) {
                            System.err.println("Error reading instance config from " + configFile + ": " + e.getMessage());
                        }
                    } else {
                        // Discovered a folder without instance.json -> auto initialize instance.json
                        String dirName = dir.getFileName().toString();
                        ServerInstance discovered = new ServerInstance(dirName, "paper", "1.21.1");
                        discovered.setId(dirName);
                        saveInstanceConfig(discovered);
                        instances.add(discovered);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error scanning instances directory: " + e.getMessage());
            }
        }

        // 2. Backward compatibility: if instances list is empty, check legacy instances.json to migrate
        Path legacyFile = rootDataDir.resolve(LEGACY_INSTANCES_FILE_NAME);
        if (instances.isEmpty() && Files.exists(legacyFile)) {
            try {
                List<ServerInstance> legacyList = objectMapper.readValue(legacyFile.toFile(), new TypeReference<List<ServerInstance>>() {});
                if (legacyList != null) {
                    for (ServerInstance inst : legacyList) {
                        createInstance(inst);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error migrating legacy instances.json: " + e.getMessage());
            }
        }

        // 3. Fallback: if still empty, create default Survival-Server instance
        if (instances.isEmpty()) {
            ServerInstance defaultInstance = new ServerInstance("Survival-Server", "paper", "1.21.1");
            createInstance(defaultInstance);
        }
    }

    public synchronized void saveInstanceConfig(ServerInstance instance) {
        if (instance == null) return;
        Path instancePath = getInstanceDirectory(instance);
        try {
            if (!Files.exists(instancePath)) {
                Files.createDirectories(instancePath);
            }
            Path configFile = instancePath.resolve(INSTANCE_CONFIG_FILE_NAME);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), instance);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void saveInstances() {
        for (ServerInstance instance : instances) {
            saveInstanceConfig(instance);
        }
    }

    public synchronized ServerInstance createInstance(ServerInstance instance) {
        if (instance.getId() == null || instance.getId().isBlank()) {
            instance.setId(ServerInstance.sanitizeFileName(instance.getName()));
        }
        instance.setConfigVersion(ServerInstance.CURRENT_CONFIG_VERSION);
        instance.setCreatedAt(LocalDateTime.now());
        instance.setLastModifiedAt(LocalDateTime.now());

        Path instancePath = getInstanceDirectory(instance);
        Path pluginsPath = instancePath.resolve("plugins");
        try {
            Files.createDirectories(pluginsPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        saveInstanceConfig(instance);

        // Replace if already exists with same ID, else add
        instances.removeIf(i -> i.getId().equals(instance.getId()));
        instances.add(instance);
        return instance;
    }

    public synchronized void updateInstance(ServerInstance instance) {
        instance.setLastModifiedAt(LocalDateTime.now());
        saveInstanceConfig(instance);
    }

    public synchronized void deleteInstance(ServerInstance instance) {
        // Delete instance directory and its files completely
        Path dir = getInstanceDirectory(instance);
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .map(Path::toFile)
                          .forEach(java.io.File::delete);
                }
            }
        } catch (Exception ignored) {}

        instances.removeIf(i -> i.getId().equals(instance.getId()));
    }

    public List<ServerInstance> getInstances() {
        return new ArrayList<>(instances);
    }

    public Path getInstanceDirectory(ServerInstance instance) {
        if (instance.getCustomDirectory() != null && !instance.getCustomDirectory().isBlank()) {
            return Paths.get(instance.getCustomDirectory());
        }
        return rootDataDir.resolve("instances").resolve(instance.getId());
    }

    public Path getPluginsDirectory(ServerInstance instance) {
        Path dir = getInstanceDirectory(instance).resolve("plugins");
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return dir;
    }

    public Path getRootDataDir() {
        return rootDataDir;
    }

    public void exportInstanceToZip(ServerInstance instance, Path targetZipFile) throws IOException {
        Path instanceDir = getInstanceDirectory(instance);
        if (!Files.exists(instanceDir)) {
            Files.createDirectories(instanceDir);
        }

        if (targetZipFile.getParent() != null) {
            Files.createDirectories(targetZipFile.getParent());
        }

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(targetZipFile))) {
            // Write instance metadata config inside zip
            byte[] metadataBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(instance);
            java.util.zip.ZipEntry metaEntry = new java.util.zip.ZipEntry("instance.json");
            zos.putNextEntry(metaEntry);
            zos.write(metadataBytes);
            zos.closeEntry();

            try (var stream = Files.walk(instanceDir)) {
                List<Path> paths = stream.filter(p -> !Files.isDirectory(p)).toList();
                for (Path p : paths) {
                    String relativePath = instanceDir.relativize(p).toString().replace('\\', '/');
                    // Skip if file is already instance.json at root level to prevent ZipException duplicate entry
                    if ("instance.json".equalsIgnoreCase(relativePath)) {
                        continue;
                    }
                    java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(relativePath);
                    zos.putNextEntry(zipEntry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    public void exportPluginsToZip(ServerInstance instance, Path targetZipFile) throws IOException {
        Path pluginsDir = getPluginsDirectory(instance);
        if (!Files.exists(pluginsDir)) {
            Files.createDirectories(pluginsDir);
        }

        if (targetZipFile.getParent() != null) {
            Files.createDirectories(targetZipFile.getParent());
        }

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(targetZipFile))) {
            try (var stream = Files.walk(pluginsDir)) {
                List<Path> paths = stream.filter(p -> !Files.isDirectory(p)).toList();
                for (Path p : paths) {
                    String relativePath = pluginsDir.relativize(p).toString().replace('\\', '/');
                    java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(relativePath);
                    zos.putNextEntry(zipEntry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    public static class ScriptItem {
        public final String fileName;
        public final String downloadUrl;
        public final String expectedSha512;
        public final String platform;
        public final String version;
        public final boolean enabled;

        public ScriptItem(String fileName, String downloadUrl, String expectedSha512, String platform, String version, boolean enabled) {
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.expectedSha512 = expectedSha512;
            this.platform = platform;
            this.version = version;
            this.enabled = enabled;
        }
    }

    public void exportPluginsScript(ServerInstance instance, List<ScriptItem> items, Path targetScriptFile) throws IOException {
        if (targetScriptFile.getParent() != null) {
            Files.createDirectories(targetScriptFile.getParent());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# =============================================================================\n");
        sb.append("# Smart Plugin Assistant - Automated Server Plugin Setup Script\n");
        sb.append("# Instance: ").append(instance.getName()).append("\n");
        sb.append("# Loader: ").append(instance.getLoader()).append(" | Minecraft Version: ").append(instance.getMcVersion()).append("\n");
        sb.append("# Generated at: ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("# =============================================================================\n\n");
        sb.append("set -e\n\n");
        sb.append("# Ensure script is run from server root (or plugins directory)\n");
        sb.append("if [ -d \"plugins\" ]; then\n");
        sb.append("    PLUGINS_DIR=\"plugins\"\n");
        sb.append("else\n");
        sb.append("    PLUGINS_DIR=\".\"\n");
        sb.append("fi\n\n");
        sb.append("echo \"=================================================================\"\n");
        sb.append("echo \"🚀 Starting plugin installation for instance: ").append(instance.getName()).append("\"\n");
        sb.append("echo \"📁 Target plugins directory: $PLUGINS_DIR\"\n");
        sb.append("echo \"=================================================================\"\n");
        sb.append("mkdir -p \"$PLUGINS_DIR\"\n\n");

        sb.append("SUCCESS_COUNT=0\n");
        sb.append("FAILED_COUNT=0\n");
        sb.append("SKIPPED_COUNT=0\n\n");

        sb.append("download_plugin() {\n");
        sb.append("    local filename=\"$1\"\n");
        sb.append("    local url=\"$2\"\n");
        sb.append("    local expected_sha512=\"$3\"\n");
        sb.append("    local target_path=\"$PLUGINS_DIR/$filename\"\n\n");
        sb.append("    echo \"\"\n");
        sb.append("    echo \"📦 Processing: $filename ...\"\n\n");
        sb.append("    if [ -z \"$url\" ]; then\n");
        sb.append("        echo \"⚠️  [SKIP] No download URL available for $filename (Local / Unsupported). Please upload manually.\"\n");
        sb.append("        SKIPPED_COUNT=$((SKIPPED_COUNT + 1))\n");
        sb.append("        return 0\n");
        sb.append("    fi\n\n");
        sb.append("    if [ -f \"$target_path\" ] && [ -n \"$expected_sha512\" ]; then\n");
        sb.append("        local current_sha512=\"\"\n");
        sb.append("        if command -v sha512sum >/dev/null 2>&1; then\n");
        sb.append("            current_sha512=$(sha512sum \"$target_path\" | awk '{print $1}')\n");
        sb.append("        elif command -v shasum >/dev/null 2>&1; then\n");
        sb.append("            current_sha512=$(shasum -a 512 \"$target_path\" | awk '{print $1}')\n");
        sb.append("        fi\n\n");
        sb.append("        if [ \"$current_sha512\" = \"$expected_sha512\" ]; then\n");
        sb.append("            echo \"✅ [EXISTS] $filename is already installed and checksum matches.\"\n");
        sb.append("            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))\n");
        sb.append("            return 0\n");
        sb.append("        fi\n");
        sb.append("    fi\n\n");
        sb.append("    echo \"⬇️  Downloading from $url ...\"\n");
        sb.append("    local tmp_file=\"$target_path.tmp\"\n");
        sb.append("    if command -v curl >/dev/null 2>&1; then\n");
        sb.append("        curl -fsSL -H \"User-Agent: Sparxilium-SmartPluginAssistant/1.0\" -o \"$tmp_file\" \"$url\"\n");
        sb.append("    elif command -v wget >/dev/null 2>&1; then\n");
        sb.append("        wget -q --user-agent=\"Sparxilium-SmartPluginAssistant/1.0\" -O \"$tmp_file\" \"$url\"\n");
        sb.append("    else\n");
        sb.append("        echo \"❌ [ERROR] Neither curl nor wget was found on this system!\"\n");
        sb.append("        FAILED_COUNT=$((FAILED_COUNT + 1))\n");
        sb.append("        return 1\n");
        sb.append("    fi\n\n");
        sb.append("    if [ -n \"$expected_sha512\" ]; then\n");
        sb.append("        local dl_sha512=\"\"\n");
        sb.append("        if command -v sha512sum >/dev/null 2>&1; then\n");
        sb.append("            dl_sha512=$(sha512sum \"$tmp_file\" | awk '{print $1}')\n");
        sb.append("        elif command -v shasum >/dev/null 2>&1; then\n");
        sb.append("            dl_sha512=$(shasum -a 512 \"$tmp_file\" | awk '{print $1}')\n");
        sb.append("        fi\n\n");
        sb.append("        if [ -n \"$dl_sha512\" ] && [ \"$dl_sha512\" != \"$expected_sha512\" ]; then\n");
        sb.append("            echo \"❌ [CHECKSUM FAILED] $filename\"\n");
        sb.append("            echo \"   Expected: $expected_sha512\"\n");
        sb.append("            echo \"   Actual:   $dl_sha512\"\n");
        sb.append("            rm -f \"$tmp_file\"\n");
        sb.append("            FAILED_COUNT=$((FAILED_COUNT + 1))\n");
        sb.append("            return 1\n");
        sb.append("        fi\n");
        sb.append("    fi\n\n");
        sb.append("    mv -f \"$tmp_file\" \"$target_path\"\n");
        sb.append("    echo \"✅ [SUCCESS] Installed: $filename\"\n");
        sb.append("    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))\n");
        sb.append("}\n\n");

        for (ScriptItem item : items) {
            String safeFn = item.fileName.replace("'", "'\\''");
            String safeUrl = item.downloadUrl != null ? item.downloadUrl.replace("'", "'\\''") : "";
            String safeSha = item.expectedSha512 != null ? item.expectedSha512 : "";
            sb.append("download_plugin '").append(safeFn).append("' '").append(safeUrl).append("' '").append(safeSha).append("'\n");
        }

        sb.append("\necho \"\"\n");
        sb.append("echo \"=================================================================\"\n");
        sb.append("echo \"🎉 Plugin setup finished!\"\n");
        sb.append("echo \"   ✅ Succeeded: $SUCCESS_COUNT\"\n");
        sb.append("echo \"   ⚠️  Skipped (Manual): $SKIPPED_COUNT\"\n");
        sb.append("echo \"   ❌ Failed: $FAILED_COUNT\"\n");
        sb.append("echo \"=================================================================\"\n");

        Files.writeString(targetScriptFile, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }
}

