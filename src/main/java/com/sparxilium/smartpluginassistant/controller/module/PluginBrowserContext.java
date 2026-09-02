package com.sparxilium.smartpluginassistant.controller.module;

import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.HangarService;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.SpigetService;
import com.sparxilium.smartpluginassistant.service.VoxelService;
import javafx.stage.Stage;

public class PluginBrowserContext {
    private final ServerInstance currentInstance;
    private final ModrinthService modrinthService;
    private final HangarService hangarService;
    private final VoxelService voxelService;
    private final SpigetService spigetService;
    private final InstanceManager instanceManager;
    private final Runnable onPluginInstalledCallback;
    private final Stage parentStage;

    public PluginBrowserContext(ServerInstance currentInstance, ModrinthService modrinthService, HangarService hangarService, VoxelService voxelService, SpigetService spigetService, InstanceManager instanceManager, Runnable onPluginInstalledCallback, Stage parentStage) {
        this.currentInstance = currentInstance;
        this.modrinthService = modrinthService;
        this.hangarService = hangarService;
        this.voxelService = voxelService;
        this.spigetService = spigetService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;
        this.parentStage = parentStage;
    }

    public ServerInstance getCurrentInstance() {
        return currentInstance;
    }

    public ModrinthService getModrinthService() {
        return modrinthService;
    }

    public HangarService getHangarService() {
        return hangarService;
    }

    public VoxelService getVoxelService() {
        return voxelService;
    }

    public SpigetService getSpigetService() {
        return spigetService;
    }

    public InstanceManager getInstanceManager() {
        return instanceManager;
    }

    public Runnable getOnPluginInstalledCallback() {
        return onPluginInstalledCallback;
    }
    
    public Stage getParentStage() {
        return parentStage;
    }
}
