package com.sparxilium.smartpluginassistant.service;

import com.sparxilium.smartpluginassistant.model.HangarVersion;
import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import com.sparxilium.smartpluginassistant.model.UpdateResult;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PluginManagerService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PluginManagerService.class);
    private final InstanceManager instanceManager;
    private final Map<String, PluginRepository> repositories;

    public PluginManagerService(InstanceManager instanceManager, List<PluginRepository> repositoryList) {
        this.instanceManager = instanceManager;
        this.repositories = new java.util.HashMap<>();
        if (repositoryList != null) {
            for (PluginRepository repo : repositoryList) {
                this.repositories.put(repo.getPlatformKey(), repo);
            }
        }
    }

    public List<InstalledPlugin> scanPlugins(ServerInstance instance) {
        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        List<InstalledPlugin> plugins = new ArrayList<>();

        if (!Files.exists(pluginsDir) || !Files.isDirectory(pluginsDir)) {
            return plugins;
        }

        try (Stream<Path> stream = Files.list(pluginsDir)) {
            List<Path> files = stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".jar") || name.endsWith(".jar.disabled");
            }).collect(Collectors.toList());

            for (Path path : files) {
                File file = path.toFile();
                String fileName = file.getName();
                boolean enabled = !fileName.endsWith(".disabled");
                long size = file.length();
                long lastModified = file.lastModified();
                String sha512 = calculateSha512(file);

                InstalledPlugin plugin = new InstalledPlugin(fileName, sha512, size, lastModified, enabled);
                
                // 1. Check download history record in metadata store
                PluginMetadataStore.DownloadRecord record = PluginMetadataStore.findRecord(instanceManager, instance, fileName, sha512);
                if (record != null) {
                    if (record.versionNumber != null && !record.versionNumber.isBlank()) {
                        plugin.setCurrentVersionNumber(record.versionNumber);
                    }
                    plugin.setProjectId(record.projectId);
                    plugin.setVersionId(record.versionId);
                    if (record.hostingPlatform != null) plugin.setHostingPlatform(record.hostingPlatform);
                    if (record.hangarNamespace != null) plugin.setHangarNamespace(record.hangarNamespace);
                    // Update record if sha512 is missing in legacy record
                    if (record.sha512 == null && sha512 != null) {
                        record.sha512 = sha512;
                        PluginMetadataStore.saveRecord(instanceManager, instance, record);
                    }
                }

                // 2. Read exact version from inside jar (plugin.yml, paper-plugin.yml, bungeecord.yml, velocity-plugin.json) if not recorded
                if (plugin.getCurrentVersionNumber().equals("Unknown")) {
                    String internalVersion = readPluginVersionFromJar(file);
                    if (internalVersion != null && !internalVersion.isBlank()) {
                        plugin.setCurrentVersionNumber(internalVersion.trim());
                    } else {
                        // 3. Fallback: deduce current version number from filename (e.g., EssentialsX-2.20.1.jar -> 2.20.1)
                        String cleanName = fileName.replace(".jar.disabled", "").replace(".jar", "");
                        int lastDash = cleanName.lastIndexOf('-');
                        if (lastDash > 0 && lastDash < cleanName.length() - 1) {
                            plugin.setCurrentVersionNumber(cleanName.substring(lastDash + 1));
                        }
                    }
                }

                plugins.add(plugin);
            }
        } catch (IOException e) {
            logger.error("Exception occurred", e);
        }

        return plugins;
    }

    public CompletableFuture<List<InstalledPlugin>> checkPluginUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        // Group plugins by hosting platform. Default to modrinth for backward compatibility (hash based).
        Map<String, List<InstalledPlugin>> pluginsByPlatform = plugins.stream()
                .collect(Collectors.groupingBy(p -> {
                    if (p.getHostingPlatform() != null && !p.getHostingPlatform().isBlank()) {
                        return p.getHostingPlatform().toLowerCase();
                    }
                    if (p.getHangarNamespace() != null && !p.getHangarNamespace().isBlank()) {
                        return "hangar";
                    }
                    return "modrinth";
                }));

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, List<InstalledPlugin>> entry : pluginsByPlatform.entrySet()) {
            String platformKey = entry.getKey();
            List<InstalledPlugin> platformPlugins = entry.getValue();

            PluginRepository repo = repositories.get(platformKey);
            if (repo == null) {
                logger.warn("No PluginRepository found for platform: {}", platformKey);
                continue;
            }

            logger.info("Checking {} plugins for updates on platform: {}", platformPlugins.size(), platformKey);

            CompletableFuture<Void> f = repo.checkForUpdates(instance, platformPlugins)
                    .thenAccept(updateMap -> {
                        for (InstalledPlugin plugin : platformPlugins) {
                            UpdateResult update = updateMap.get(plugin);
                            if (update != null) {
                                boolean isNewer = false;
                                
                                // ID comparison if exact versionId was given (usually modrinth/hangar/spiget)
                                boolean isSameVersionId = plugin.getVersionId() != null && plugin.getVersionId().equals(update.getVersionId());
                                
                                if (!isSameVersionId) {
                                    if (update.getLatestVersionNumber() != null && update.getLatestVersionNumber().equalsIgnoreCase(plugin.getCurrentVersionNumber())) {
                                        // Exact version string match
                                    } else {
                                        isNewer = isNewerVersion(plugin.getCurrentVersionNumber(), update.getLatestVersionNumber());
                                    }
                                }

                                if (isNewer) {
                                    plugin.setUpdateAvailable(true);
                                    plugin.setLatestVersionId(update.getVersionId());
                                    plugin.setLatestVersionNumber(update.getLatestVersionNumber());
                                    plugin.setLatestDownloadUrl(update.getLatestDownloadUrl());
                                    plugin.setSupportedGameVersions(update.getSupportedGameVersions());
                                    
                                    // Make sure we have the platform recorded
                                    if (plugin.getHostingPlatform() == null) {
                                        plugin.setHostingPlatform(platformKey);
                                    }
                                } else {
                                    plugin.setUpdateAvailable(false);
                                    if (plugin.getSupportedGameVersions() == null || plugin.getSupportedGameVersions().equals("-")) {
                                        plugin.setSupportedGameVersions(instance.getMcVersion() != null ? instance.getMcVersion() : "-");
                                    }
                                }
                            } else {
                                plugin.setUpdateAvailable(false);
                                if (plugin.getSupportedGameVersions() == null || plugin.getSupportedGameVersions().equals("-")) {
                                    plugin.setSupportedGameVersions(instance.getMcVersion() != null ? instance.getMcVersion() : "-");
                                }
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error checking updates for platform {}: {}", platformKey, ex.getMessage(), ex);
                        return null;
                    });
            futures.add(f);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> plugins);
    }

    public CompletableFuture<Void> updatePlugin(ServerInstance instance, InstalledPlugin plugin) {
        if (!plugin.isUpdateAvailable() || plugin.getLatestDownloadUrl() == null) {
            return CompletableFuture.completedFuture(null);
        }

        String platformKey = plugin.getHostingPlatform();
        if (platformKey == null || platformKey.isBlank()) {
            if (plugin.getHangarNamespace() != null && !plugin.getHangarNamespace().isBlank()) platformKey = "hangar";
            else platformKey = "modrinth";
        }

        PluginRepository repo = repositories.get(platformKey);
        if (repo == null) {
            return CompletableFuture.failedFuture(new RuntimeException("No repository found for platform: " + platformKey));
        }

        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path oldFilePath = pluginsDir.resolve(plugin.getFileName());
        String rawName = plugin.getLatestFileName() != null ? plugin.getLatestFileName() : plugin.getFileName();
        if (!plugin.isEnabled() && !rawName.endsWith(".disabled")) {
            rawName = rawName + ".disabled";
        }
        final String newName = rawName;
        Path newFilePath = pluginsDir.resolve(newName);

        return repo.downloadUpdate(instance, plugin.getLatestDownloadUrl(), newFilePath, null)
                .thenAccept(downloadedPath -> {
                    if (!oldFilePath.equals(newFilePath)) {
                        try {
                            Files.deleteIfExists(oldFilePath);
                        } catch (IOException e) {
                            logger.error("Exception occurred", e);
                        }
                    }
                    // Calculate new sha512, then save record to metadata store
                    String newSha512 = calculateSha512(newFilePath.toFile());
                    PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                            plugin.getProjectId(),
                            plugin.getLatestVersionId(),
                            plugin.getLatestVersionNumber(),
                            newName,
                            newSha512
                    );
                    record.hostingPlatform = plugin.getHostingPlatform();
                    record.hangarNamespace = plugin.getHangarNamespace();
                    PluginMetadataStore.saveRecord(instanceManager, instance, record);
                });
    }

    public boolean togglePluginEnabled(ServerInstance instance, InstalledPlugin plugin) {
        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path currentPath = pluginsDir.resolve(plugin.getFileName());

        if (!Files.exists(currentPath)) return false;

        String currentName = plugin.getFileName();
        String targetName;
        boolean newEnabled;

        if (currentName.endsWith(".disabled")) {
            targetName = currentName.substring(0, currentName.length() - ".disabled".length());
            newEnabled = true;
        } else {
            targetName = currentName + ".disabled";
            newEnabled = false;
        }

        Path targetPath = pluginsDir.resolve(targetName);
        try {
            Files.move(currentPath, targetPath);
            plugin.setFileName(targetName);
            plugin.setEnabled(newEnabled);
            return true;
        } catch (IOException e) {
            logger.error("Exception occurred", e);
            return false;
        }
    }

    public boolean deletePlugin(ServerInstance instance, InstalledPlugin plugin) {
        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path filePath = pluginsDir.resolve(plugin.getFileName());
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            logger.error("Exception occurred", e);
            return false;
        }
    }

    public static String calculateSha1(File file) {
        return calculateHash(file, "SHA-1");
    }

    public static String calculateSha512(File file) {
        return calculateHash(file, "SHA-512");
    }

    public static String calculateHash(File file, String algorithm) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] byteArray = new byte[8192];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static class JarPluginInfo {
        public String name;
        public String version;
        public String description;

        public JarPluginInfo(String name, String version, String description) {
            this.name = name;
            this.version = version;
            this.description = description;
        }
    }

    public static JarPluginInfo readJarPluginInfo(File jarFile) {
        if (!jarFile.exists() || !jarFile.getName().toLowerCase().contains(".jar")) {
            return null;
        }

        String name = null;
        String version = null;
        String description = null;

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            String[] descriptors = {"plugin.yml", "paper-plugin.yml", "bungeecord.yml", "velocity-plugin.json", "fabric.mod.json", "mcmod.info"};
            for (String desc : descriptors) {
                java.util.zip.ZipEntry entry = jar.getEntry(desc);
                if (entry != null) {
                    try (var is = jar.getInputStream(entry);
                         var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmed = line.trim();
                            if (name == null && (trimmed.startsWith("name:") || trimmed.startsWith("\"name\":") || trimmed.startsWith("\"id\":"))) {
                                String n = trimmed.replaceFirst("(?i)^\"?(name|id)\"?\\s*:\\s*", "")
                                        .replace("\"", "").replace("'", "").replace(",", "").trim();
                                if (!n.isBlank() && !n.startsWith("${") && !n.equalsIgnoreCase("@name@")) {
                                    name = n;
                                }
                            }
                            if (version == null && (trimmed.startsWith("version:") || trimmed.startsWith("\"version\":"))) {
                                String ver = trimmed.replaceFirst("(?i)^\"?version\"?\\s*:\\s*", "")
                                        .replace("\"", "").replace("'", "").replace(",", "").trim();
                                if (!ver.isBlank() && !ver.startsWith("${") && !ver.equalsIgnoreCase("@version@")) {
                                    version = ver;
                                }
                            }
                            if (description == null && (trimmed.startsWith("description:") || trimmed.startsWith("\"description\":"))) {
                                String d = trimmed.replaceFirst("(?i)^\"?description\"?\\s*:\\s*", "")
                                        .replace("\"", "").replace("'", "").replace(",", "").trim();
                                if (!d.isBlank() && !d.startsWith("${")) {
                                    description = d;
                                }
                            }
                        }
                    }
                }
                if (name != null && version != null) break;
            }
        } catch (Exception ignored) {}

        if (name == null && version == null) return null;
        return new JarPluginInfo(name, version, description);
    }

    public static String readPluginVersionFromJar(File jarFile) {
        JarPluginInfo info = readJarPluginInfo(jarFile);
        return info != null ? info.version : null;
    }

    public static String normalizeVersionNumber(String ver) {
        if (ver == null) return "";
        String clean = ver.trim();
        // Remove common platform prefixes like bukkit-, spigot-, paper-, fabric-, v, etc.
        clean = clean.replaceAll("(?i)^(bukkit|spigot|paper|purpur|folia|velocity|bungee|bungeecord|fabric|sponge|forge|neoforge|release|rel|v)[-_.]+", "");
        // Also remove platform suffix like -bukkit, -spigot, -paper, -fabric, etc.
        clean = clean.replaceAll("(?i)[-_.]+(bukkit|spigot|paper|purpur|folia|velocity|bungee|bungeecord|fabric|sponge|forge|neoforge|all|universal)$", "");

        // Extract primary semantic version pattern like 5.5.71 or 2.6.21
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(\\.\\d+)+)").matcher(clean);
        if (m.find()) {
            return m.group(1);
        }
        return clean.toLowerCase().replaceAll("^[vV]", "");
    }

    public static boolean isNewerVersion(String currentVersion, String latestVersion) {
        if (latestVersion == null || latestVersion.isBlank()) return false;
        if (currentVersion == null || currentVersion.isBlank() || currentVersion.equals("-")) return true;

        String curNorm = normalizeVersionNumber(currentVersion);
        String latNorm = normalizeVersionNumber(latestVersion);

        if (curNorm.equalsIgnoreCase(latNorm)) return false;

        // Split semantic parts: 5.5.71 -> [5, 5, 71]
        String[] curParts = curNorm.split("[.-]");
        String[] latParts = latNorm.split("[.-]");

        int maxLen = Math.max(curParts.length, latParts.length);
        for (int i = 0; i < maxLen; i++) {
            String cPart = i < curParts.length ? curParts[i] : "0";
            String lPart = i < latParts.length ? latParts[i] : "0";

            String cDigits = cPart.replaceAll("\\D", "");
            String lDigits = lPart.replaceAll("\\D", "");

            if (!cDigits.isEmpty() && !lDigits.isEmpty()) {
                try {
                    int cNum = Integer.parseInt(cDigits);
                    int lNum = Integer.parseInt(lDigits);
                    if (lNum > cNum) return true;
                    if (lNum < cNum) return false;
                } catch (NumberFormatException ignored) {}
            }

            int cmp = lPart.compareToIgnoreCase(cPart);
            if (cmp > 0) return true;
            if (cmp < 0) return false;
        }
        return false;
    }
}
