package com.sparxilium.smartpluginassistant.service;

import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.model.UpdateResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface PluginRepository {
    /**
     * Gets the key corresponding to the hostingPlatform metadata.
     * Examples: "modrinth", "hangar", "spiget", "voxel"
     */
    String getPlatformKey();

    /**
     * Checks for updates for a list of plugins belonging to this platform.
     * The map key is the original plugin object, and the value is the update result.
     * If a plugin has no update, it does not need to be in the map (or its value can be null).
     */
    CompletableFuture<Map<InstalledPlugin, UpdateResult>> checkForUpdates(ServerInstance instance, List<InstalledPlugin> plugins);

    /**
     * Downloads an update file from this repository.
     * Implementations should inject any required API tokens or headers.
     */
    CompletableFuture<Path> downloadUpdate(ServerInstance instance, String downloadUrl, Path targetPath, Consumer<Double> progressCallback);
}
