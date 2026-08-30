package com.chiliasmstudio.smartpluginassistant.service;

import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;
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

        // Use user home / .smartpluginassistant or local ./data directory
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

        // If no instances, create a sample Paper 1.21 instance
        if (instances.isEmpty()) {
            ServerInstance defaultInstance = new ServerInstance("Survival Server", "paper", "1.21");
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
        if (instance.getId() == null) {
            instance.setId(java.util.UUID.randomUUID().toString());
        }
        instance.setCreatedAt(LocalDateTime.now());
        instance.setLastModifiedAt(LocalDateTime.now());

        // Ensure instance folder and plugins folder exist
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
}
