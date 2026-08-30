package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainController {
    // Root & Top Bar
    @FXML private BorderPane rootPane;
    @FXML private Label appTitleLabel;
    @FXML private Label appSubtitleLabel;
    @FXML private Button appSettingsBtn;
    @FXML private Button addInstanceBtn;

    // Instance sidebar
    @FXML private Label instanceListHeaderLabel;
    @FXML private Button refreshInstancesBtn;
    @FXML private FlowPane instanceContainer;
    @FXML private Label selectedInstanceNameLabel;
    @FXML private Label selectedInstanceInfoLabel;
    @FXML private VBox instanceDetailPanel;
    @FXML private Button instanceSettingsBtn;
    @FXML private Button openPluginsFolderBtn;
    @FXML private Button exportZipBtn;
    @FXML private Button deleteInstanceBtn;

    // Toolbar
    @FXML private Button browseModrinthBtn;
    @FXML private Button addByUrlBtn;
    @FXML private Button checkUpdatesBtn;
    @FXML private Button updateAllBtn;
    @FXML private Button refreshPluginsBtn;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator globalProgress;

    // Plugins Table
    @FXML private TableView<InstalledPlugin> pluginTableView;
    @FXML private TableColumn<InstalledPlugin, String> colName;
    @FXML private TableColumn<InstalledPlugin, String> colSize;
    @FXML private TableColumn<InstalledPlugin, String> colStatus;
    @FXML private TableColumn<InstalledPlugin, String> colUpdate;
    @FXML private TableColumn<InstalledPlugin, Void> colActions;

    private final InstanceManager instanceManager = new InstanceManager();
    private final ModrinthService modrinthService = new ModrinthService();
    private final PluginManagerService pluginManagerService = new PluginManagerService(instanceManager, modrinthService);

    private ServerInstance currentSelectedInstance;
    private final ObservableList<InstalledPlugin> installedPluginsList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        setupTableColumns();
        applyI18n();
        refreshInstanceList();
        setupResponsiveLayout();
    }

    private void setupResponsiveLayout() {
        if (rootPane == null) return;
        rootPane.widthProperty().addListener((obs, oldW, newW) -> {
            boolean isCompact = newW.doubleValue() < 980;
            if (isCompact) {
                if (!rootPane.getStyleClass().contains("compact-mode")) {
                    rootPane.getStyleClass().add("compact-mode");
                }
            } else {
                rootPane.getStyleClass().remove("compact-mode");
            }
            updateButtonLabels(isCompact);
        });
    }

    private void updateButtonLabels(boolean isCompact) {
        boolean isEn = "en".equalsIgnoreCase(I18n.getCurrentLang());
        if (isCompact) {
            // Icon only / shorter labels when window is small
            appSettingsBtn.setText("⚙️");
            addInstanceBtn.setText("+ " + (isEn ? "New" : "新增實例"));
            instanceSettingsBtn.setText("⚙️");
            openPluginsFolderBtn.setText("📂 " + (isEn ? "Folder" : "資料夾"));
            exportZipBtn.setText("📦 ZIP");
            deleteInstanceBtn.setText("🗑️ " + (isEn ? "Delete" : "刪除"));
            browseModrinthBtn.setText("🔍 " + (isEn ? "Modrinth" : "Modrinth 插件"));
            addByUrlBtn.setText("🔗 " + (isEn ? "URL" : "網址新增"));
            checkUpdatesBtn.setText("🔄 " + (isEn ? "Check" : "檢查更新"));
            updateAllBtn.setText("⚡ " + (isEn ? "Update All" : "全部更新"));
            refreshPluginsBtn.setText("↻");
            refreshInstancesBtn.setText("↻");
        } else {
            appSettingsBtn.setText(I18n.get("app_settings.title"));
            addInstanceBtn.setText(I18n.get("app.add_instance"));
            instanceSettingsBtn.setText(I18n.get("app.instance_settings"));
            openPluginsFolderBtn.setText(I18n.get("app.open_plugins_folder"));
            exportZipBtn.setText(I18n.get("app.export_zip"));
            deleteInstanceBtn.setText(I18n.get("app.delete_instance"));
            browseModrinthBtn.setText(I18n.get("app.browse_modrinth"));
            addByUrlBtn.setText(I18n.get("app.add_by_url"));
            checkUpdatesBtn.setText(I18n.get("app.check_updates"));
            updateAllBtn.setText(I18n.get("app.update_all"));
            refreshPluginsBtn.setText(I18n.get("app.refresh_list"));
            refreshInstancesBtn.setText(I18n.get("app.refresh"));
        }
    }

    private void applyI18n() {
        appTitleLabel.setText(I18n.get("app.title"));
        appSubtitleLabel.setText(I18n.get("app.subtitle"));
        updateButtonLabels(rootPane != null && rootPane.getWidth() < 980);

        instanceListHeaderLabel.setText(I18n.get("app.instance_list"));
        refreshInstancesBtn.setText(I18n.get("app.refresh"));

        instanceSettingsBtn.setText(I18n.get("app.instance_settings"));
        openPluginsFolderBtn.setText(I18n.get("app.open_plugins_folder"));
        exportZipBtn.setText(I18n.get("app.export_zip"));
        deleteInstanceBtn.setText(I18n.get("app.delete_instance"));

        browseModrinthBtn.setText(I18n.get("app.browse_modrinth"));
        addByUrlBtn.setText(I18n.get("app.add_by_url"));
        checkUpdatesBtn.setText(I18n.get("app.check_updates"));
        updateAllBtn.setText(I18n.get("app.update_all"));
        refreshPluginsBtn.setText(I18n.get("app.refresh_list"));

        colName.setText(I18n.get("table.col_name"));
        colSize.setText(I18n.get("table.col_size"));
        colStatus.setText(I18n.get("table.col_status"));
        colUpdate.setText(I18n.get("table.col_update"));
        colActions.setText(I18n.get("table.col_actions"));

        if (currentSelectedInstance == null) {
            selectedInstanceNameLabel.setText(I18n.get("app.no_instance_selected"));
            selectedInstanceInfoLabel.setText(I18n.get("app.select_instance_hint"));
            setInstanceButtonsDisabled(true);
        } else {
            selectInstance(currentSelectedInstance);
        }
    }

    @FXML
    private void handleOpenAppSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/app-settings-dialog.fxml"));
            Parent root = loader.load();

            AppSettingsDialogController controller = loader.getController();
            controller.init(() -> {
                applyI18n();
                pluginTableView.refresh();
                refreshInstanceList();
            });

            Stage stage = new Stage();
            stage.setTitle(I18n.get("app_settings.title"));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupTableColumns() {
        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colSize.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFormattedSize()));

        colStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    Label statusBadge = new Label(plugin.isEnabled() ? I18n.get("table.status_enabled") : I18n.get("table.status_disabled"));
                    statusBadge.setStyle(plugin.isEnabled() ?
                            "-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4;" :
                            "-fx-background-color: #7f8c8d; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4;");
                    setGraphic(statusBadge);
                    setText(null);
                }
            }
        });

        colUpdate.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin.isUpdateAvailable()) {
                        Label updateBadge = new Label(I18n.get("table.has_update", plugin.getLatestVersionNumber()));
                        updateBadge.getStyleClass().add("badge-update");
                        setGraphic(updateBadge);
                    } else {
                        Label currentBadge = new Label(I18n.get("table.latest_version"));
                        currentBadge.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
                        setGraphic(currentBadge);
                    }
                    setText(null);
                }
            }
        });

        colActions.setCellFactory(column -> new TableCell<>() {
            private final Button toggleBtn = new Button();
            private final Button updateBtn = new Button();
            private final Button deleteBtn = new Button();
            private final HBox actionsBox = new HBox(6, toggleBtn, updateBtn, deleteBtn);

            {
                updateBtn.getStyleClass().add("btn-primary");
                deleteBtn.getStyleClass().add("btn-danger");
                toggleBtn.getStyleClass().add("btn-secondary");

                toggleBtn.setOnAction(e -> {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin != null && currentSelectedInstance != null) {
                        pluginManagerService.togglePluginEnabled(currentSelectedInstance, plugin);
                        refreshPlugins();
                    }
                });

                updateBtn.setOnAction(e -> {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin != null && currentSelectedInstance != null && plugin.isUpdateAvailable()) {
                        statusLabel.setText(I18n.get("app.updating_plugin", plugin.getFileName()));
                        pluginManagerService.updatePlugin(currentSelectedInstance, plugin)
                                .thenAccept(v -> Platform.runLater(() -> {
                                    statusLabel.setText(I18n.get("app.update_success"));
                                    refreshPlugins();
                                }))
                                .exceptionally(ex -> {
                                    Platform.runLater(() -> statusLabel.setText(I18n.get("app.update_failed", ex.getMessage())));
                                    return null;
                                });
                    }
                });

                deleteBtn.setOnAction(e -> {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin != null && currentSelectedInstance != null) {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("app.confirm_delete_plugin", plugin.getFileName()), ButtonType.YES, ButtonType.NO);
                        alert.showAndWait().ifPresent(btn -> {
                            if (btn == ButtonType.YES) {
                                pluginManagerService.deletePlugin(currentSelectedInstance, plugin);
                                refreshPlugins();
                            }
                        });
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    toggleBtn.setText(plugin.isEnabled() ? I18n.get("table.btn_disable") : I18n.get("table.btn_enable"));
                    updateBtn.setText(I18n.get("table.btn_update"));
                    deleteBtn.setText(I18n.get("table.btn_delete"));
                    updateBtn.setVisible(plugin.isUpdateAvailable());
                    updateBtn.setManaged(plugin.isUpdateAvailable());
                    setGraphic(actionsBox);
                }
            }
        });

        pluginTableView.setItems(installedPluginsList);
    }

    public void refreshInstanceList() {
        instanceContainer.getChildren().clear();
        List<ServerInstance> instances = instanceManager.getInstances();

        for (ServerInstance inst : instances) {
            VBox card = createInstanceCard(inst);
            instanceContainer.getChildren().add(card);
        }

        if (!instances.isEmpty()) {
            if (currentSelectedInstance == null || !instances.contains(currentSelectedInstance)) {
                selectInstance(instances.get(0));
            } else {
                selectInstance(currentSelectedInstance);
            }
        }
    }

    private VBox createInstanceCard(ServerInstance instance) {
        VBox card = new VBox(6);
        card.setPrefWidth(180);
        card.setPrefHeight(100);
        card.getStyleClass().add("instance-card");

        if (currentSelectedInstance != null && currentSelectedInstance.getId().equals(instance.getId())) {
            card.getStyleClass().add("instance-card-selected");
        }

        Label nameLabel = new Label(instance.getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #ffffff;");

        HBox badges = new HBox(6);
        Label loaderBadge = new Label(instance.getLoader().toUpperCase());
        loaderBadge.getStyleClass().add("badge-loader");
        Label versionBadge = new Label(instance.getMcVersion());
        versionBadge.getStyleClass().add("badge-version");
        badges.getChildren().addAll(loaderBadge, versionBadge);

        card.getChildren().addAll(nameLabel, badges);

        card.setOnMouseClicked(e -> {
            selectInstance(instance);
            refreshInstanceList();
        });

        return card;
    }

    private void setInstanceButtonsDisabled(boolean disabled) {
        instanceSettingsBtn.setDisable(disabled);
        openPluginsFolderBtn.setDisable(disabled);
        exportZipBtn.setDisable(disabled);
        deleteInstanceBtn.setDisable(disabled);
        browseModrinthBtn.setDisable(disabled);
        addByUrlBtn.setDisable(disabled);
        checkUpdatesBtn.setDisable(disabled);
        refreshPluginsBtn.setDisable(disabled);
    }

    private void selectInstance(ServerInstance instance) {
        this.currentSelectedInstance = instance;
        if (instance == null) {
            selectedInstanceNameLabel.setText(I18n.get("app.no_instance_selected"));
            selectedInstanceInfoLabel.setText(I18n.get("app.select_instance_hint"));
            setInstanceButtonsDisabled(true);
            installedPluginsList.clear();
            return;
        }

        setInstanceButtonsDisabled(false);
        selectedInstanceNameLabel.setText(instance.getName());

        String fuzzyText = "";
        if (!instance.getExtraCompatibleLoaders().isEmpty()) {
            fuzzyText = " " + I18n.get("instance.fuzzy_tag", String.join(", ", instance.getExtraCompatibleLoaders()));
        }

        selectedInstanceInfoLabel.setText(
                I18n.get("instance.core", instance.getLoader()) + " | " +
                I18n.get("instance.mc_version", instance.getMcVersion()) + fuzzyText + " | " +
                I18n.get("instance.path", instanceManager.getInstanceDirectory(instance).toAbsolutePath())
        );
        refreshPlugins();
    }

    @FXML
    private void handleExportInstanceZip() {
        if (currentSelectedInstance == null) return;
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("app.export_zip_title"));
        fileChooser.setInitialFileName(currentSelectedInstance.getName() + ".zip");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP Archive (*.zip)", "*.zip"));
        java.io.File file = fileChooser.showSaveDialog(exportZipBtn.getScene().getWindow());
        if (file != null) {
            try {
                instanceManager.exportInstanceToZip(currentSelectedInstance, file.toPath());
                Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("app.export_zip_success", currentSelectedInstance.getName(), file.getAbsolutePath()), ButtonType.OK);
                alert.showAndWait();
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.export_zip_failed", e.getMessage()), ButtonType.OK);
                alert.showAndWait();
            }
        }
    }

    @FXML
    private void handleOpenInstanceSettings() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/instance-settings-dialog.fxml"));
            Parent root = loader.load();

            InstanceSettingsDialogController controller = loader.getController();
            controller.init(currentSelectedInstance);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("settings.title") + " - " + currentSelectedInstance.getName());
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();

            if (controller.isSaved()) {
                instanceManager.updateInstance(currentSelectedInstance);
                selectInstance(currentSelectedInstance);
                refreshInstanceList();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    public void refreshPlugins() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> plugins = pluginManagerService.scanPlugins(currentSelectedInstance);
        installedPluginsList.setAll(plugins);
        updateAllBtn.setVisible(false);
        statusLabel.setText(I18n.get("app.plugin_count", plugins.size()));
    }

    @FXML
    private void handleCheckUpdates() {
        if (currentSelectedInstance == null || installedPluginsList.isEmpty()) {
            statusLabel.setText(I18n.get("app.no_plugins_to_check"));
            return;
        }

        globalProgress.setVisible(true);
        statusLabel.setText(I18n.get("app.checking_updates"));

        pluginManagerService.checkPluginUpdates(currentSelectedInstance, installedPluginsList)
                .thenAccept(updatedList -> Platform.runLater(() -> {
                    globalProgress.setVisible(false);
                    pluginTableView.refresh();

                    long updateCount = updatedList.stream().filter(InstalledPlugin::isUpdateAvailable).count();
                    if (updateCount > 0) {
                        statusLabel.setText(I18n.get("app.updates_found", updateCount));
                        updateAllBtn.setVisible(true);
                    } else {
                        statusLabel.setText(I18n.get("app.all_up_to_date"));
                        updateAllBtn.setVisible(false);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        globalProgress.setVisible(false);
                        statusLabel.setText(I18n.get("app.check_updates_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleUpdateAll() {
        if (currentSelectedInstance == null) return;

        List<InstalledPlugin> toUpdate = installedPluginsList.stream()
                .filter(InstalledPlugin::isUpdateAvailable)
                .toList();

        if (toUpdate.isEmpty()) return;

        globalProgress.setVisible(true);
        statusLabel.setText(I18n.get("app.updating_all", toUpdate.size()));

        CompletableFuture<?>[] futures = toUpdate.stream()
                .map(p -> pluginManagerService.updatePlugin(currentSelectedInstance, p))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures)
                .thenAccept(v -> Platform.runLater(() -> {
                    globalProgress.setVisible(false);
                    statusLabel.setText(I18n.get("app.update_all_success"));
                    refreshPlugins();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        globalProgress.setVisible(false);
                        statusLabel.setText(I18n.get("app.update_all_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleOpenModrinthBrowser() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/modrinth-browser-dialog.fxml"));
            Parent root = loader.load();

            ModrinthBrowserController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, instanceManager, this::refreshPlugins);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("modrinth.window_title", currentSelectedInstance.getName()));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleAddByUrl() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/add-by-url-dialog.fxml"));
            Parent root = loader.load();

            AddByUrlDialogController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, instanceManager, this::refreshPlugins);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("url.window_title", currentSelectedInstance.getName()));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCreateInstance() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/create-instance-dialog.fxml"));
            Parent root = loader.load();

            Stage stage = new Stage();
            stage.setTitle(I18n.get("create.title"));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();

            CreateInstanceDialogController controller = loader.getController();
            ServerInstance created = controller.getCreatedInstance();
            if (created != null) {
                instanceManager.createInstance(created);
                selectInstance(created);
                refreshInstanceList();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleOpenPluginsFolder() {
        if (currentSelectedInstance == null) return;
        Path pluginsDir = instanceManager.getPluginsDirectory(currentSelectedInstance);
        try {
            if (!java.nio.file.Files.exists(pluginsDir)) {
                java.nio.file.Files.createDirectories(pluginsDir);
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pluginsDir.toFile());
            } else {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", pluginsDir.toAbsolutePath().toString()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", pluginsDir.toAbsolutePath().toString()).start();
                } else {
                    new ProcessBuilder("xdg-open", pluginsDir.toAbsolutePath().toString()).start();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR, "無法開啟資料夾: " + e.getMessage(), ButtonType.OK);
            alert.showAndWait();
        }
    }

    @FXML
    private void handleDeleteCurrentInstance() {
        if (currentSelectedInstance == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("app.confirm_delete_instance", currentSelectedInstance.getName()), ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                instanceManager.deleteInstance(currentSelectedInstance);
                currentSelectedInstance = null;
                refreshInstanceList();
            }
        });
    }
}
