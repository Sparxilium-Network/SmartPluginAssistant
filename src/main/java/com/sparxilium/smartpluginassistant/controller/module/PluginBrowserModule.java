package com.sparxilium.smartpluginassistant.controller.module;

import javafx.scene.Node;

public interface PluginBrowserModule {
    /**
     * Gets the user-facing name of this provider (e.g., "Modrinth")
     */
    String getProviderName();

    /**
     * Gets the style class used for the sidebar button (e.g., "modrinth-btn")
     */
    String getStyleClass();

    /**
     * Gets the icon element or emoji string for the sidebar
     */
    String getIconString();

    /**
     * Gets an optional custom Node icon for the sidebar (takes precedence over getIconString if non-null)
     */
    default Node getIconNode() {
        return null;
    }

    /**
     * Returns the root node of this browser module's UI
     */
    Node getRootNode();

    /**
     * Initializes the module with the shared context
     */
    void initializeModule(PluginBrowserContext context);
    
    /**
     * Called when the module is selected/shown
     */
    default void onShow() {}
}
