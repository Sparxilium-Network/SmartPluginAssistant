package com.chiliasmstudio.smartpluginassistant.controller;

import com.chiliasmstudio.smartpluginassistant.model.InstalledPlugin;
import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;
import com.chiliasmstudio.smartpluginassistant.service.InstanceManager;
import com.chiliasmstudio.smartpluginassistant.service.ModrinthService;
import com.chiliasmstudio.smartpluginassistant.service.PluginManagerService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MainController {
    // Instance sidebar / grid
    @FXML private FlowPane instanceContainer;
    @FXML private Label selectedInstanceNameLabel;
    @FXML private Label selectedInstanceInfoLabel;
    @FXML private VBox instanceDetailPanel;

    // Plugins Table
    @FXML private TableView<InstalledPlugin> pluginTableView;
    @FXML private TableColumn<InstalledPlugin, String> colName;
    @FXML private TableColumn<InstalledPlugin, String> colSize;
    @FXML private TableColumn<InstalledPlugin, String> colStatus;
    @FXML private TableColumn<InstalledPlugin, String> colUpdate;
    @FXML private TableColumn<InstalledPlugin, Void> colActions;

    @FXML private Button updateAllBtn;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator globalProgress;

    private final InstanceManager instanceManager = new InstanceManager();
    private final ModrinthService modrinthService = new ModrinthService();
    private final PluginManagerService pluginManagerService = new PluginManagerService(instanceManager, modrinthService);

    private ServerInstance currentSelectedInstance;
    private final ObservableList<InstalledPlugin> installedPluginsList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        setupTableColumns();
        refreshInstanceList();
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
                    Label statusBadge = new Label(plugin.isEnabled() ? "已啟用" : "已停用");
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
                        Label updateBadge = new Label("有新版: " + plugin.getLatestVersionNumber());
                        updateBadge.getStyleClass().add("badge-update");
                        setGraphic(updateBadge);
                    } else {
                        Label currentBadge = new Label("最新版本");
                        currentBadge.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
                        setGraphic(currentBadge);
                    }
                    setText(null);
                }
            }
        });

        colActions.setCellFactory(column -> new TableCell<>() {
            private final Button toggleBtn = new Button();
            private final Button updateBtn = new Button("更新");
            private final Button deleteBtn = new Button("刪除");
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
                        statusLabel.setText("正在更新 " + plugin.getFileName() + "...");
                        pluginManagerService.updatePlugin(currentSelectedInstance, plugin)
                                .thenAccept(v -> Platform.runLater(() -> {
                                    statusLabel.setText("更新完成！");
                                    refreshPlugins();
                                }))
                                .exceptionally(ex -> {
                                    Platform.runLater(() -> statusLabel.setText("更新失敗: " + ex.getMessage()));
                                    return null;
                                });
                    }
                });

                deleteBtn.setOnAction(e -> {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin != null && currentSelectedInstance != null) {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "確定要刪除 " + plugin.getFileName() + " 嗎？", ButtonType.YES, ButtonType.NO);
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
                    toggleBtn.setText(plugin.isEnabled() ? "停用" : "啟用");
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

    private void selectInstance(ServerInstance instance) {
        this.currentSelectedInstance = instance;
        selectedInstanceNameLabel.setText(instance.getName());
        selectedInstanceInfoLabel.setText("核心: " + instance.getLoader() + " | MC版本: " + instance.getMcVersion() + " | 目錄: " + instanceManager.getInstanceDirectory(instance).toAbsolutePath());
        refreshPlugins();
    }

    @FXML
    public void refreshPlugins() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> plugins = pluginManagerService.scanPlugins(currentSelectedInstance);
        installedPluginsList.setAll(plugins);
        updateAllBtn.setVisible(false);
        statusLabel.setText("共有 " + plugins.size() + " 個插件");
    }

    @FXML
    private void handleCheckUpdates() {
        if (currentSelectedInstance == null || installedPluginsList.isEmpty()) {
            statusLabel.setText("無插件可檢查");
            return;
        }

        globalProgress.setVisible(true);
        statusLabel.setText("正在透過 Modrinth API 檢查更新...");

        pluginManagerService.checkPluginUpdates(currentSelectedInstance, installedPluginsList)
                .thenAccept(updatedList -> Platform.runLater(() -> {
                    globalProgress.setVisible(false);
                    pluginTableView.refresh();

                    long updateCount = updatedList.stream().filter(InstalledPlugin::isUpdateAvailable).count();
                    if (updateCount > 0) {
                        statusLabel.setText("檢查完成：發現 " + updateCount + " 個插件可更新！");
                        updateAllBtn.setVisible(true);
                    } else {
                        statusLabel.setText("檢查完成：所有插件均為最新版本。");
                        updateAllBtn.setVisible(false);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        globalProgress.setVisible(false);
                        statusLabel.setText("檢查更新失敗: " + ex.getMessage());
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
        statusLabel.setText("正在批次更新 " + toUpdate.size() + " 個插件...");

        CompletableFuture<?>[] futures = toUpdate.stream()
                .map(p -> pluginManagerService.updatePlugin(currentSelectedInstance, p))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures)
                .thenAccept(v -> Platform.runLater(() -> {
                    globalProgress.setVisible(false);
                    statusLabel.setText("所有插件更新完成！");
                    refreshPlugins();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        globalProgress.setVisible(false);
                        statusLabel.setText("批次更新發生錯誤: " + ex.getMessage());
                    });
                    return null;
                });
    }

    @FXML
    private void handleOpenModrinthBrowser() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/modrinth-browser-dialog.fxml"));
            Parent root = loader.load();

            ModrinthBrowserController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, instanceManager, this::refreshPlugins);

            Stage stage = new Stage();
            stage.setTitle("瀏覽 Modrinth 插件 - " + currentSelectedInstance.getName());
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/style.css").toExternalForm());
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
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/add-by-url-dialog.fxml"));
            Parent root = loader.load();

            AddByUrlDialogController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, instanceManager, this::refreshPlugins);

            Stage stage = new Stage();
            stage.setTitle("透過網址新增插件 - " + currentSelectedInstance.getName());
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCreateInstance() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/create-instance-dialog.fxml"));
            Parent root = loader.load();

            Stage stage = new Stage();
            stage.setTitle("建立新伺服器實例");
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/style.css").toExternalForm());
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
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(pluginsDir.toFile());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleDeleteCurrentInstance() {
        if (currentSelectedInstance == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "確定要刪除實例「" + currentSelectedInstance.getName() + "」的配置嗎？（不會刪除自訂路徑檔案）", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                instanceManager.deleteInstance(currentSelectedInstance);
                currentSelectedInstance = null;
                refreshInstanceList();
            }
        });
    }
}
