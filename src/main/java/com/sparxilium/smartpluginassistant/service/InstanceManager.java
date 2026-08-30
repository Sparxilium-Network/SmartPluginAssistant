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
    private static final String INSTANCES_FILE_NAME = "instances.json";

    private final Path rootDataDir;
    private final Path instancesFile;
    private final ObjectMapper objectMapper;
    private final List<ServerInstance> instances = new ArrayList<>();

    public InstanceManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        String userHome = System.getProperty("user.home");
        this.rootDataDir = Paths.get(userHome, APP_DATA_DIR_NAME);
        this.instancesFile = rootDataDir.resolve(INSTANCES_FILE_NAME);

        initStorage();
        loadInstances();
    }

    private void initStorage() {
        try {
            if (!Files.exists(rootDataDir)) {
                Files.createDirectories(rootDataDir);
            }
            Path defaultInstancesDir = rootDataDir.resolve("instances");
            if (!Files.exists(defaultInstancesDir)) {
                Files.createDirectories(defaultInstancesDir);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void loadInstances() {
        instances.clear();
        if (Files.exists(instancesFile)) {
            try {
                List<ServerInstance> loaded = objectMapper.readValue(instancesFile.toFile(), new TypeReference<List<ServerInstance>>() {});
                if (loaded != null) {
                    instances.addAll(loaded);
                }
            } catch (IOException e) {
                System.err.println("Error reading instances.json: " + e.getMessage());
            }
        }

        if (instances.isEmpty()) {
            ServerInstance defaultInstance = new ServerInstance("Survival-Server", "paper", "1.21.1");
            createInstance(defaultInstance);
        }
    }

    public synchronized void saveInstances() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(instancesFile.toFile(), instances);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized ServerInstance createInstance(ServerInstance instance) {
        if (instance.getId() == null || instance.getId().isBlank()) {
            instance.setId(ServerInstance.sanitizeFileName(instance.getName()));
        }
        instance.setCreatedAt(LocalDateTime.now());
        instance.setLastModifiedAt(LocalDateTime.now());

        Path instancePath = getInstanceDirectory(instance);
        Path pluginsPath = instancePath.resolve("plugins");
        try {
            Files.createDirectories(pluginsPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        instances.add(instance);
        saveInstances();
        return instance;
    }

    public synchronized void updateInstance(ServerInstance instance) {
        instance.setLastModifiedAt(LocalDateTime.now());
        saveInstances();
    }

    public synchronized void deleteInstance(ServerInstance instance) {
        // Also clean up default instance directory if it was under instances/
        if (instance.getCustomDirectory() == null || instance.getCustomDirectory().isBlank()) {
            Path dir = getInstanceDirectory(instance);
            try {
                if (Files.exists(dir)) {
                    // Try to delete files inside
                    try (var stream = Files.walk(dir)) {
                        stream.sorted(java.util.Comparator.reverseOrder())
                              .map(Path::toFile)
                              .forEach(java.io.File::delete);
                    }
                }
            } catch (Exception ignored) {}
        }
        instances.removeIf(i -> i.getId().equals(instance.getId()));
        saveInstances();
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
            // Also write instance metadata config inside zip
            byte[] metadataBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(instance);
            java.util.zip.ZipEntry metaEntry = new java.util.zip.ZipEntry("instance.json");
            zos.putNextEntry(metaEntry);
            zos.write(metadataBytes);
            zos.closeEntry();

            try (var stream = Files.walk(instanceDir)) {
                List<Path> paths = stream.filter(p -> !Files.isDirectory(p)).toList();
                for (Path p : paths) {
                    String relativePath = instanceDir.relativize(p).toString().replace('\\', '/');
                    java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(relativePath);
                    zos.putNextEntry(zipEntry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
