package com.sparxilium.smartpluginassistant.controller.module;

import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.HangarService;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import javafx.stage.Stage;

public class PluginBrowserContext {
    private final ServerInstance currentInstance;
    private final ModrinthService modrinthService;
    private final HangarService hangarService;
    private final InstanceManager instanceManager;
    private final Runnable onPluginInstalledCallback;
    private final Stage parentStage;

    public PluginBrowserContext(ServerInstance currentInstance, ModrinthService modrinthService, HangarService hangarService, InstanceManager instanceManager, Runnable onPluginInstalledCallback, Stage parentStage) {
        this.currentInstance = currentInstance;
        this.modrinthService = modrinthService;
        this.hangarService = hangarService;
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
