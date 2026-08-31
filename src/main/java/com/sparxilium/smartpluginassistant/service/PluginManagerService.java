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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PluginManagerService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PluginManagerService.class);
    private final InstanceManager instanceManager;
    private final ModrinthService modrinthService;
    private final HangarService hangarService;

    public PluginManagerService(InstanceManager instanceManager, ModrinthService modrinthService) {
        this.instanceManager = instanceManager;
        this.modrinthService = modrinthService;
        this.hangarService = new HangarService();
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
                String sha1 = calculateSha1(file);
                String sha512 = calculateSha512(file);

                InstalledPlugin plugin = new InstalledPlugin(fileName, sha1, sha512, size, lastModified, enabled);
                
                // 1. Check download history record in metadata store
                PluginMetadataStore.DownloadRecord record = PluginMetadataStore.findRecord(instanceManager, instance, fileName, sha1, sha512);
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
            e.printStackTrace();
        }

        return plugins;
    }

    public CompletableFuture<List<InstalledPlugin>> checkPluginUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        List<String> hashesList = plugins.stream()
                .map(p -> p.getSha512() != null ? p.getSha512() : p.getSha1())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return modrinthService.checkUpdates(hashesList, instance.getEffectiveLoaders(), instance.getMcVersion())
                .thenApply(updateMap -> {
                    for (InstalledPlugin plugin : plugins) {
                        String hash = plugin.getSha512() != null ? plugin.getSha512() : plugin.getSha1();
                        if (hash != null && updateMap.containsKey(hash)) {
                            ModrinthVersion version = updateMap.get(hash);
                            ModrinthVersion.ModrinthFile primaryFile = version.getPrimaryFile();

                            String latestVerNum = version.getVersionNumber();
                            String currentVerNum = plugin.getCurrentVersionNumber();
                            String currentVerId = plugin.getVersionId();

                            // 1. Direct ID comparison: If we know the exact installed version ID and it equals the returned version ID, it is the same version!
                            boolean isSameVersion = currentVerId != null && currentVerId.equals(version.getId());
                            
                            // 2. Direct exact string comparison (including any platform tags/prefixes/suffixes)
                            if (!isSameVersion && currentVerNum != null && currentVerNum.equalsIgnoreCase(latestVerNum)) {
                                isSameVersion = true;
                            }

                            // 3. Normalized semantic comparison
                            boolean isNewer = !isSameVersion && isNewerVersion(currentVerNum, latestVerNum);

                            if (isNewer) {
                                plugin.setUpdateAvailable(true);
                                plugin.setLatestVersionId(version.getId());
                                plugin.setLatestVersionNumber(latestVerNum);
                                plugin.setLatestVersionType(version.getVersionType());
                                plugin.setProjectId(version.getProjectId());

                                if (version.getGameVersions() != null && !version.getGameVersions().isEmpty()) {
                                    if (version.getGameVersions().size() > 2) {
                                        plugin.setSupportedGameVersions(version.getGameVersions().get(0) + " ~ " + version.getGameVersions().get(version.getGameVersions().size() - 1));
                                    } else {
                                        plugin.setSupportedGameVersions(String.join(", ", version.getGameVersions()));
                                    }
                                }

                                if (version.getLoaders() != null && !version.getLoaders().isEmpty()) {
                                    String primaryLoader = instance.getLoader() != null ? instance.getLoader().toLowerCase() : "paper";
                                    boolean supportsPrimary = version.getLoaders().stream().anyMatch(l -> l.equalsIgnoreCase(primaryLoader));
                                    plugin.setLoaderIncompatible(!supportsPrimary);
                                    plugin.setSupportedLoadersSummary(String.join(", ", version.getLoaders()));
                                } else {
                                    plugin.setLoaderIncompatible(false);
                                }

                                if (primaryFile != null) {
                                    plugin.setLatestDownloadUrl(primaryFile.getUrl());
                                    plugin.setLatestFileName(primaryFile.getFilename());
                                }
                            } else {
                                plugin.setUpdateAvailable(false);
                                plugin.setLoaderIncompatible(false);
                                // Save/refresh download record with exact version, sha1, sha512 and projectId
                                if (plugin.getProjectId() == null || plugin.getVersionId() == null) {
                                    plugin.setProjectId(version.getProjectId());
                                    plugin.setVersionId(version.getId());
                                    plugin.setCurrentVersionNumber(version.getVersionNumber());
                                    String sha1 = plugin.getSha1();
                                    String sha512 = plugin.getSha512();
                                    PluginMetadataStore.saveRecord(instanceManager, instance,
                                            new PluginMetadataStore.DownloadRecord(version.getProjectId(), version.getId(), version.getVersionNumber(), plugin.getFileName(), sha1, sha512));
                                }
                                if (plugin.getSupportedGameVersions() == null || plugin.getSupportedGameVersions().equals("-")) {
                                    plugin.setSupportedGameVersions(instance.getMcVersion() != null ? instance.getMcVersion() : "-");
                                }
                            }
                        } else {
                            plugin.setUpdateAvailable(false);
                            plugin.setLoaderIncompatible(false);
                            if (plugin.getSupportedGameVersions() == null || plugin.getSupportedGameVersions().equals("-")) {
                                plugin.setSupportedGameVersions(instance.getMcVersion() != null ? instance.getMcVersion() : "-");
                            }
                        }
                    }
                    return plugins;
                });
    }

    /**
     * Check updates for Hangar plugins.
     * Hangar does not support hash-based lookup, so we use the stored hangarNamespace
     * to query the latest version from Hangar API and compare version numbers.
     */
    public CompletableFuture<List<InstalledPlugin>> checkHangarPluginUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        String platform = HangarService.toPlatformKey(instance.getLoader());
        String mcVersion = instance.getMcVersion();

        // Only process plugins with a known Hangar namespace
        List<InstalledPlugin> hangarPlugins = plugins.stream()
                .filter(p -> p.getHangarNamespace() != null && !p.getHangarNamespace().isBlank())
                .collect(Collectors.toList());

        if (hangarPlugins.isEmpty()) {
            logger.info("checkHangarPluginUpdates: no Hangar plugins to check");
            return CompletableFuture.completedFuture(plugins);
        }

        logger.info("checkHangarPluginUpdates: checking {} Hangar plugins on platform={}, mc={}", hangarPlugins.size(), platform, mcVersion);

        // Fire one request per Hangar plugin (Hangar has no batch update endpoint)
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (InstalledPlugin plugin : hangarPlugins) {
            String[] parts = plugin.getHangarNamespace().split("/", 2);
            if (parts.length < 2) continue;
            String author = parts[0];
            String slug = parts[1];

            CompletableFuture<Void> f = hangarService.getVersions(author, slug, platform, null, 0, 5)
                    .thenAccept(page -> {
                        if (page.versions().isEmpty()) {
                            logger.info("checkHangarPluginUpdates: no versions found for {}", plugin.getHangarNamespace());
                            return;
                        }
                        // Find the newest stable version (unless allowPrereleases is on)
                        HangarVersion latest = null;
                        for (HangarVersion v : page.versions()) {
                            if (!instance.isAllowPrereleases() && v.isUnstable()) continue;
                            latest = v;
                            break;
                        }
                        if (latest == null) latest = page.versions().get(0);

                        String latestVerNum = latest.getVersionNumber();
                        String currentVerNum = plugin.getCurrentVersionNumber();
                        boolean isNewer = isNewerVersion(currentVerNum, latestVerNum);
                        logger.info("checkHangarPluginUpdates: {} current={} latest={} isNewer={}", plugin.getHangarNamespace(), currentVerNum, latestVerNum, isNewer);

                        if (isNewer) {
                            plugin.setUpdateAvailable(true);
                            plugin.setLatestVersionNumber(latestVerNum);
                            plugin.setLatestVersionType(latest.getVersionType());

                            // Get download URL
                            HangarVersion.PlatformDownload pd = latest.getPaperDownload();
                            if (pd != null && pd.downloadUrl != null) {
                                plugin.setLatestDownloadUrl(pd.downloadUrl);
                                if (pd.fileInfo != null) plugin.setLatestFileName(pd.fileInfo.name);
                            }

                            // Supported game versions
                            List<String> gameVers = latest.getPaperVersions();
                            if (!gameVers.isEmpty()) {
                                if (gameVers.size() > 2) {
                                    plugin.setSupportedGameVersions(gameVers.get(0) + " ~ " + gameVers.get(gameVers.size() - 1));
                                } else {
                                    plugin.setSupportedGameVersions(String.join(", ", gameVers));
                                }
                            }
                        } else {
                            plugin.setUpdateAvailable(false);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.warn("checkHangarPluginUpdates: error checking {}: {}", plugin.getHangarNamespace(), ex.getMessage());
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

        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path oldFilePath = pluginsDir.resolve(plugin.getFileName());
        String rawName = plugin.getLatestFileName() != null ? plugin.getLatestFileName() : plugin.getFileName();
        if (!plugin.isEnabled() && !rawName.endsWith(".disabled")) {
            rawName = rawName + ".disabled";
        }
        final String newName = rawName;
        Path newFilePath = pluginsDir.resolve(newName);

        return modrinthService.downloadFile(plugin.getLatestDownloadUrl(), newFilePath, null)
                .thenAccept(downloadedPath -> {
                    if (!oldFilePath.equals(newFilePath)) {
                        try {
                            Files.deleteIfExists(oldFilePath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    // Calculate new sha1 and sha512, then save record to metadata store
                    String newSha1 = calculateSha1(newFilePath.toFile());
                    String newSha512 = calculateSha512(newFilePath.toFile());
                    PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                            plugin.getProjectId(),
                            plugin.getLatestVersionId(),
                            plugin.getLatestVersionNumber(),
                            newName,
                            newSha1,
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
            e.printStackTrace();
            return false;
        }
    }

    public boolean deletePlugin(ServerInstance instance, InstalledPlugin plugin) {
        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path filePath = pluginsDir.resolve(plugin.getFileName());
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            e.printStackTrace();
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
