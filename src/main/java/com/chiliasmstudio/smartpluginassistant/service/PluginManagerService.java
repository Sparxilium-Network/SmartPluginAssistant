package com.chiliasmstudio.smartpluginassistant.service;

import com.chiliasmstudio.smartpluginassistant.model.InstalledPlugin;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthVersion;
import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;

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
    private final InstanceManager instanceManager;
    private final ModrinthService modrinthService;

    public PluginManagerService(InstanceManager instanceManager, ModrinthService modrinthService) {
        this.instanceManager = instanceManager;
        this.modrinthService = modrinthService;
    }

    /**
     * Scan the instance's plugins/ directory and compute SHA1 hashes for all jars
     */
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
                String sha1 = calculateSha1(file);

                InstalledPlugin plugin = new InstalledPlugin(fileName, sha1, size, enabled);
                plugins.add(plugin);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return plugins;
    }

    /**
     * Check updates for all installed plugins in an instance
     */
    public CompletableFuture<List<InstalledPlugin>> checkPluginUpdates(ServerInstance instance, List<InstalledPlugin> plugins) {
        List<String> sha1List = plugins.stream()
                .map(InstalledPlugin::getSha1)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return modrinthService.checkUpdates(sha1List, instance.getLoader(), instance.getMcVersion())
                .thenApply(updateMap -> {
                    for (InstalledPlugin plugin : plugins) {
                        String hash = plugin.getSha1();
                        if (hash != null && updateMap.containsKey(hash)) {
                            ModrinthVersion version = updateMap.get(hash);
                            ModrinthVersion.ModrinthFile primaryFile = version.getPrimaryFile();

                            plugin.setUpdateAvailable(true);
                            plugin.setLatestVersionId(version.getId());
                            plugin.setLatestVersionNumber(version.getVersionNumber());
                            plugin.setProjectId(version.getProjectId());

                            if (primaryFile != null) {
                                plugin.setLatestDownloadUrl(primaryFile.getUrl());
                                plugin.setLatestFileName(primaryFile.getFilename());
                            }
                        } else {
                            plugin.setUpdateAvailable(false);
                        }
                    }
                    return plugins;
                });
    }

    /**
     * Update an individual plugin to its latest version
     */
    public CompletableFuture<Void> updatePlugin(ServerInstance instance, InstalledPlugin plugin) {
        if (!plugin.isUpdateAvailable() || plugin.getLatestDownloadUrl() == null) {
            return CompletableFuture.completedFuture(null);
        }

        Path pluginsDir = instanceManager.getPluginsDirectory(instance);
        Path oldFilePath = pluginsDir.resolve(plugin.getFileName());
        String newName = plugin.getLatestFileName() != null ? plugin.getLatestFileName() : plugin.getFileName();
        if (!plugin.isEnabled() && !newName.endsWith(".disabled")) {
            newName = newName + ".disabled";
        }
        Path newFilePath = pluginsDir.resolve(newName);

        return modrinthService.downloadFile(plugin.getLatestDownloadUrl(), newFilePath, null)
                .thenAccept(downloadedPath -> {
                    // Delete old file if different filename
                    if (!oldFilePath.equals(newFilePath)) {
                        try {
                            Files.deleteIfExists(oldFilePath);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
    }

    /**
     * Enable or disable a plugin by renaming with .disabled
     */
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

    /**
     * Delete a plugin file
     */
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

    /**
     * Compute SHA-1 hash of a file
     */
    public static String calculateSha1(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
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
}
