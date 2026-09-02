package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserContext;
import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserModule;
import com.sparxilium.smartpluginassistant.service.I18n;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PluginDownloaderController {
    @FXML private Label sidebarHeaderLabel;
    @FXML private VBox moduleListContainer;
    @FXML private StackPane centerPane;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PluginDownloaderController.class);

    private final List<PluginBrowserModule> modules = new ArrayList<>();
    private final ToggleGroup moduleToggleGroup = new ToggleGroup();

    public void init(PluginBrowserContext context) {
        sidebarHeaderLabel.setText(I18n.get("downloader.sidebar_title"));
        
        loadModule("/com/sparxilium/smartpluginassistant/modrinth-browser-dialog.fxml", context);
        loadModule("/com/sparxilium/smartpluginassistant/hangar-browser-dialog.fxml", context);
        
        buildSidebar();

        if (!modules.isEmpty()) {
            selectModule(modules.get(0));
        }
    }

    private void loadModule(String fxmlPath, PluginBrowserContext context) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();
            Object controller = loader.getController();
            
            if (controller instanceof PluginBrowserModule) {
                PluginBrowserModule module = (PluginBrowserModule) controller;
                module.initializeModule(context);
                modules.add(module);
            } else {
                logger.error("Controller for {} does not implement PluginBrowserModule", fxmlPath);
            }
        } catch (IOException e) {
            logger.error("Failed to load module: " + fxmlPath, e);
        }
    }

    private void buildSidebar() {
        moduleListContainer.getChildren().clear();
        for (PluginBrowserModule module : modules) {
            ToggleButton btn = new ToggleButton();
            btn.getStyleClass().addAll("module-sidebar-btn", module.getStyleClass());
            btn.setToggleGroup(moduleToggleGroup);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            
            HBox content = new HBox(8);
            content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Label iconLabel = new Label(module.getIconString());
            iconLabel.getStyleClass().add("module-icon");
            Label textLabel = new Label(module.getProviderName());
            content.getChildren().addAll(iconLabel, textLabel);
            
            btn.setGraphic(content);
            btn.setUserData(module);

            btn.setOnAction(e -> {
                if (btn.isSelected()) {
                    selectModule(module);
                } else {
                    // Prevent unselecting the current tab
                    btn.setSelected(true);
                }
            });

            moduleListContainer.getChildren().add(btn);
        }
    }

    private void selectModule(PluginBrowserModule module) {
        // Select the toggle button
        for (javafx.scene.Node node : moduleListContainer.getChildren()) {
            if (node instanceof ToggleButton) {
                ToggleButton btn = (ToggleButton) node;
                if (btn.getUserData() == module) {
                    btn.setSelected(true);
                    break;
                }
            }
        }
        
        centerPane.getChildren().setAll(module.getRootNode());
        module.onShow();
    }
}
