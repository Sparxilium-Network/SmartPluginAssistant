package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.hangar.HangarVersion;
import com.sparxilium.smartpluginassistant.model.InstalledPlugin;
import com.sparxilium.smartpluginassistant.model.modrinth.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.HangarService;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import com.sparxilium.smartpluginassistant.service.VoxelService;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MainController {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MainController.class);

    // Root & Top Bar
    @FXML private BorderPane rootPane;
    @FXML private Label appTitleLabel;
    @FXML private Label appSubtitleLabel;
    @FXML private Button appSettingsBtn;
    @FXML private Button addInstanceBtn;
    @FXML private SplitPane mainSplitPane;

    // Instance sidebar
    @FXML private Label instanceListHeaderLabel;
    @FXML private Button refreshInstancesBtn;
    @FXML private FlowPane instanceContainer;
    @FXML private Label selectedInstanceNameLabel;
    @FXML private Label selectedInstanceInfoLabel;
    @FXML private VBox instanceDetailPanel;
    @FXML private Button instanceSettingsBtn;
    @FXML private Button openPluginsFolderBtn;
    @FXML private Button exportInstanceZipBtn;
    @FXML private Button exportPluginsZipBtn;
    @FXML private Button exportScriptBtn;
    @FXML private Button deleteInstanceBtn;

    // Toolbar
    @FXML private Button downloadPluginsBtn;
    @FXML private Button addByUrlBtn;
    @FXML private Button importPluginsBtn;
    @FXML private Button checkUpdatesBtn;
    @FXML private Button updateAllBtn;
    @FXML private Button refreshPluginsBtn;
    @FXML private TextField pluginSearchField;
    @FXML private ComboBox<String> pluginFilterCombo;
    @FXML private Button batchEnableBtn;
    @FXML private Button batchDisableBtn;
    @FXML private Button batchDeleteBtn;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator globalProgress;

    // Plugins Table
    @FXML private TableView<InstalledPlugin> pluginTableView;
    @FXML private TableColumn<InstalledPlugin, Boolean> colSelect;
    @FXML private TableColumn<InstalledPlugin, String> colName;
    @FXML private TableColumn<InstalledPlugin, String> colPlatform;
    @FXML private TableColumn<InstalledPlugin, String> colCurrentVersion;
    @FXML private TableColumn<InstalledPlugin, String> colLastModified;
    @FXML private TableColumn<InstalledPlugin, String> colUpdate;
    @FXML private TableColumn<InstalledPlugin, Void> colActions;

    private final InstanceManager instanceManager = new InstanceManager();
    private final com.sparxilium.smartpluginassistant.service.HttpDownloadService downloadService = new com.sparxilium.smartpluginassistant.service.HttpDownloadService(java.net.http.HttpClient.newHttpClient());
    private final ModrinthService modrinthService = new ModrinthService(downloadService);
    private final HangarService hangarService = new HangarService(downloadService);
    private final VoxelService voxelService = new VoxelService(downloadService);
    private final com.sparxilium.smartpluginassistant.service.SpigetService spigetService = new com.sparxilium.smartpluginassistant.service.SpigetService(downloadService);
    private final PluginManagerService pluginManagerService = new PluginManagerService(instanceManager, java.util.List.of(modrinthService, hangarService, voxelService, spigetService));

    private ServerInstance currentSelectedInstance;
    private final ObservableList<InstalledPlugin> installedPluginsList = FXCollections.observableArrayList();
    private javafx.collections.transformation.FilteredList<InstalledPlugin> filteredPluginsList;

    private static final String PREF_MAIN_SPLIT_POSITION = "main_split_position";

    @FXML
    public void initialize() {
        if (mainSplitPane != null) {
            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double savedPos = prefs.getDouble(PREF_MAIN_SPLIT_POSITION, 0.25);
            
            Platform.runLater(() -> {
                mainSplitPane.setDividerPositions(savedPos);
                if (!mainSplitPane.getDividers().isEmpty()) {
                    mainSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldV, newV) -> {
                        prefs.putDouble(PREF_MAIN_SPLIT_POSITION, newV.doubleValue());
                    });
                }
            });
        }
        setupTableColumns();
        setupFilterBindings();
        applyI18n();
        refreshInstanceList();
        setupResponsiveLayout();
    }

    private void setupFilterBindings() {
        filteredPluginsList = new javafx.collections.transformation.FilteredList<>(installedPluginsList, p -> true);
        javafx.collections.transformation.SortedList<InstalledPlugin> sortedList = new javafx.collections.transformation.SortedList<>(filteredPluginsList);
        sortedList.comparatorProperty().bind(pluginTableView.comparatorProperty());
        pluginTableView.setItems(sortedList);

        if (pluginSearchField != null) {
            pluginSearchField.textProperty().addListener((obs, oldV, newV) -> updatePluginFilterPredicate());
        }
        if (pluginFilterCombo != null) {
            pluginFilterCombo.valueProperty().addListener((obs, oldV, newV) -> updatePluginFilterPredicate());
        }
    }

    private void updatePluginFilterPredicate() {
        if (filteredPluginsList == null) return;
        String search = pluginSearchField != null && pluginSearchField.getText() != null ? pluginSearchField.getText().trim().toLowerCase() : "";
        int filterIndex = pluginFilterCombo != null ? pluginFilterCombo.getSelectionModel().getSelectedIndex() : 0;
        if (filterIndex < 0) filterIndex = 0;

        final int currentFilter = filterIndex;

        filteredPluginsList.setPredicate(plugin -> {
            if (plugin == null) return false;

            // Search keyword filter
            if (!search.isEmpty()) {
                boolean matchName = plugin.getFileName() != null && plugin.getFileName().toLowerCase().contains(search);
                boolean matchPluginName = plugin.getPluginName() != null && plugin.getPluginName().toLowerCase().contains(search);
                boolean matchVer = plugin.getCurrentVersionNumber() != null && plugin.getCurrentVersionNumber().toLowerCase().contains(search);
                if (!matchName && !matchPluginName && !matchVer) {
                    return false;
                }
            }

            // Dropdown filter: 0=All, 1=Enabled, 2=Disabled, 3=Updates Available
            switch (currentFilter) {
                case 1:
                    return plugin.isEnabled();
                case 2:
                    return !plugin.isEnabled();
                case 3:
                    return plugin.isUpdateAvailable();
                default:
                    return true;
            }
        });
    }

    private void updateFilterComboOptions() {
        if (pluginFilterCombo == null) return;
        int prevIndex = pluginFilterCombo.getSelectionModel().getSelectedIndex();
        if (prevIndex < 0) prevIndex = 0;

        long allCount = installedPluginsList.size();
        long enabledCount = installedPluginsList.stream().filter(InstalledPlugin::isEnabled).count();
        long disabledCount = installedPluginsList.stream().filter(p -> !p.isEnabled()).count();
        long updatesCount = installedPluginsList.stream().filter(InstalledPlugin::isUpdateAvailable).count();

        pluginFilterCombo.getItems().setAll(
                I18n.get("app.filter_all", allCount),
                I18n.get("app.filter_enabled", enabledCount),
                I18n.get("app.filter_disabled", disabledCount),
                I18n.get("app.filter_updates", updatesCount)
        );

        pluginFilterCombo.getSelectionModel().select(Math.min(prevIndex, pluginFilterCombo.getItems().size() - 1));
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
            addInstanceBtn.setText(I18n.get("app.add_instance_compact"));
            instanceSettingsBtn.setText("⚙️");
            openPluginsFolderBtn.setText(I18n.get("app.open_plugins_folder_compact"));
            exportInstanceZipBtn.setText(I18n.get("app.export_instance_zip_compact"));
            exportPluginsZipBtn.setText(I18n.get("app.export_plugins_zip_compact"));
            if (exportScriptBtn != null) exportScriptBtn.setText(I18n.get("app.export_script_compact"));
            deleteInstanceBtn.setText(I18n.get("app.delete_instance_compact"));
            if (downloadPluginsBtn != null) downloadPluginsBtn.setText(I18n.get("app.download_plugins_compact"));
            addByUrlBtn.setText(I18n.get("app.add_by_url_compact"));
            if (importPluginsBtn != null) importPluginsBtn.setText(I18n.get("app.import_plugins_compact"));
            checkUpdatesBtn.setText(I18n.get("app.check_updates_compact"));
            updateAllBtn.setText(I18n.get("app.update_all_compact"));
            batchEnableBtn.setText(I18n.get("app.btn_batch_enable_compact"));
            batchDisableBtn.setText(I18n.get("app.btn_batch_disable_compact"));
            batchDeleteBtn.setText(I18n.get("app.btn_batch_delete_compact"));
            refreshPluginsBtn.setText("↻");
            refreshInstancesBtn.setText("↻");
        } else {
            appSettingsBtn.setText(I18n.get("app_settings.title"));
            addInstanceBtn.setText(I18n.get("app.add_instance"));
            instanceSettingsBtn.setText(I18n.get("app.instance_settings"));
            openPluginsFolderBtn.setText(I18n.get("app.open_plugins_folder"));
            exportInstanceZipBtn.setText(I18n.get("app.export_instance_zip"));
            exportPluginsZipBtn.setText(I18n.get("app.export_plugins_zip"));
            if (exportScriptBtn != null) exportScriptBtn.setText(I18n.get("app.export_script"));
            deleteInstanceBtn.setText(I18n.get("app.delete_instance"));
            if (downloadPluginsBtn != null) downloadPluginsBtn.setText(I18n.get("app.download_plugins"));
            addByUrlBtn.setText(I18n.get("app.add_by_url"));
            if (importPluginsBtn != null) importPluginsBtn.setText(I18n.get("app.import_plugins"));
            checkUpdatesBtn.setText(I18n.get("app.check_updates"));
            updateAllBtn.setText(I18n.get("app.update_all"));
            batchEnableBtn.setText(I18n.get("app.btn_batch_enable"));
            batchDisableBtn.setText(I18n.get("app.btn_batch_disable"));
            batchDeleteBtn.setText(I18n.get("app.btn_batch_delete"));
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
        exportInstanceZipBtn.setText(I18n.get("app.export_instance_zip"));
        exportPluginsZipBtn.setText(I18n.get("app.export_plugins_zip"));
        if (exportScriptBtn != null) exportScriptBtn.setText(I18n.get("app.export_script"));
        deleteInstanceBtn.setText(I18n.get("app.delete_instance"));

        if (downloadPluginsBtn != null) downloadPluginsBtn.setText(I18n.get("app.download_plugins"));
        addByUrlBtn.setText(I18n.get("app.add_by_url"));
        if (importPluginsBtn != null) importPluginsBtn.setText(I18n.get("app.import_plugins"));
        checkUpdatesBtn.setText(I18n.get("app.check_updates"));
        updateAllBtn.setText(I18n.get("app.update_all"));
        batchEnableBtn.setText(I18n.get("app.btn_batch_enable"));
        batchDisableBtn.setText(I18n.get("app.btn_batch_disable"));
        batchDeleteBtn.setText(I18n.get("app.btn_batch_delete"));
        refreshPluginsBtn.setText(I18n.get("app.refresh_list"));

        colSelect.setText("");
        colName.setText(I18n.get("table.col_name"));
        if (colPlatform != null) colPlatform.setText(I18n.get("table.col_platform"));
        colCurrentVersion.setText(I18n.get("table.col_current_version"));
        colLastModified.setText(I18n.get("table.col_last_modified"));
        colUpdate.setText(I18n.get("table.col_update"));
        colActions.setText(I18n.get("table.col_actions"));

        if (pluginSearchField != null) {
            pluginSearchField.setPromptText(I18n.get("app.search_plugins_prompt"));
        }
        updateFilterComboOptions();

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

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("app_settings_dialog_w", 480);
            double h = prefs.getDouble("app_settings_dialog_h", 380);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("app_settings.title"));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(420);
            stage.setMinHeight(300);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("app_settings_dialog_w", stage.getWidth());
                    prefs.putDouble("app_settings_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();
        } catch (IOException e) {
            logger.error("Exception occurred", e);
        }
    }

    private void setupTableColumns() {
        // Configure TableView to support MULTIPLE selection (Ctrl / Shift clicking)
        pluginTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Synchronize row selection with plugin.selected property
        pluginTableView.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<InstalledPlugin>) c -> {
            Set<InstalledPlugin> currentSelectedSet = new HashSet<>(pluginTableView.getSelectionModel().getSelectedItems());
            for (InstalledPlugin p : installedPluginsList) {
                p.setSelected(currentSelectedSet.contains(p));
            }
            pluginTableView.refresh();
        });

        // Space bar key to toggle selection of selected rows
        pluginTableView.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                var selected = pluginTableView.getSelectionModel().getSelectedItems();
                if (!selected.isEmpty()) {
                    boolean anyUnchecked = selected.stream().anyMatch(p -> !p.isSelected());
                    for (InstalledPlugin p : selected) {
                        p.setSelected(anyUnchecked);
                    }
                    pluginTableView.refresh();
                    e.consume();
                }
            }
        });

        // Select CheckBox column
        colSelect.setCellValueFactory(cellData -> new javafx.beans.property.SimpleBooleanProperty(cellData.getValue().isSelected()));
        colSelect.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(e -> {
                    InstalledPlugin p = getTableRow().getItem();
                    if (p != null) {
                        p.setSelected(checkBox.isSelected());
                        if (checkBox.isSelected()) {
                            if (!pluginTableView.getSelectionModel().getSelectedItems().contains(p)) {
                                pluginTableView.getSelectionModel().select(p);
                            }
                        } else {
                            int idx = pluginTableView.getItems().indexOf(p);
                            if (idx >= 0) {
                                pluginTableView.getSelectionModel().clearSelection(idx);
                            }
                        }
                    }
                });
            }
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    InstalledPlugin p = getTableRow().getItem();
                    checkBox.setSelected(p.isSelected());
                    setAlignment(javafx.geometry.Pos.CENTER);
                    setGraphic(checkBox);
                }
            }
        });

        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colName.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    setText(item);
                    if (!plugin.isEnabled()) {
                        setStyle("-fx-text-fill: #7f8c8d; -fx-opacity: 0.7;");
                    } else {
                        setStyle("-fx-text-fill: #ffffff;");
                    }
                }
            }
        });

        // Platform column (Modrinth / Hangar / Local)
        colPlatform.setCellValueFactory(cellData -> {
            InstalledPlugin p = cellData.getValue();
            String platform = resolvePlatform(p);
            return new javafx.beans.property.SimpleStringProperty(platform);
        });
        colPlatform.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    String platform = resolvePlatform(plugin);
                    Label platformBadge = new Label();
                    if ("modrinth".equals(platform)) {
                        platformBadge.setText(I18n.get("table.platform_modrinth"));
                        platformBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 8; -fx-background-radius: 10;");
                    } else if ("hangar".equals(platform)) {
                        platformBadge.setText(I18n.get("table.platform_hangar"));
                        platformBadge.getStyleClass().add("badge-hangar");
                    } else if ("voxel".equals(platform)) {
                        platformBadge.setText(I18n.get("table.platform_voxel"));
                        platformBadge.setStyle("-fx-background-color: #e67e22; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 8; -fx-background-radius: 10;");
                    } else if ("spiget".equals(platform)) {
                        platformBadge.setText(I18n.get("table.platform_spiget"));
                        platformBadge.setStyle("-fx-background-color: #f39c12; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 8; -fx-background-radius: 10;");
                    } else {
                        platformBadge.setText(I18n.get("table.platform_local"));
                        platformBadge.setStyle("-fx-background-color: #4e5157; -fx-text-fill: #bcbec4; -fx-font-size: 11px; -fx-padding: 2 8; -fx-background-radius: 10;");
                    }
                    setAlignment(javafx.geometry.Pos.CENTER);
                    setGraphic(platformBadge);
                    setText(null);
                }
            }
        });
        
        // Current Version column with Architecture Incompatibility Warning Icon
        colCurrentVersion.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getCurrentVersionNumber()));
        colCurrentVersion.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    InstalledPlugin plugin = getTableRow().getItem();
                    HBox box = new HBox(6);
                    box.setAlignment(javafx.geometry.Pos.CENTER);

                    Label verLabel = new Label(item != null ? item : "-");
                    verLabel.setStyle("-fx-text-fill: #bcbec4; -fx-font-size: 12px;");
                    box.getChildren().add(verLabel);

                    if (plugin.isLoaderIncompatible()) {
                        Label warnIcon = new Label("⚠️");
                        String tip = I18n.get("table.incompatible_loader_warn", 
                                plugin.getSupportedLoadersSummary() != null ? plugin.getSupportedLoadersSummary() : "Other");
                        Tooltip.install(warnIcon, new Tooltip(tip));
                        box.getChildren().add(warnIcon);
                    }

                    setAlignment(javafx.geometry.Pos.CENTER);
                    setGraphic(box);
                    setText(null);
                }
            }
        });

        colLastModified.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().getFormattedLastModified()));
        colLastModified.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label timeLabel = new Label(item);
                    timeLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px;");
                    setAlignment(javafx.geometry.Pos.CENTER);
                    setGraphic(timeLabel);
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
                    setAlignment(javafx.geometry.Pos.CENTER);
                    if (plugin.isUpdateAvailable()) {
                        String verType = plugin.getLatestVersionType();
                        boolean isPrerelease = verType != null && (verType.equalsIgnoreCase("beta") || verType.equalsIgnoreCase("alpha"));
                        Label updateBadge = new Label();
                        if (isPrerelease) {
                            updateBadge.setText(I18n.get("table.has_update_prerelease", plugin.getLatestVersionNumber(), verType));
                            updateBadge.getStyleClass().add("badge-update-prerelease");
                        } else {
                            updateBadge.setText(I18n.get("table.has_update", plugin.getLatestVersionNumber()));
                            updateBadge.getStyleClass().add("badge-update");
                        }
                        setGraphic(updateBadge);
                    } else {
                        Label currentBadge = new Label(I18n.get("table.latest_version"));
                        currentBadge.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px; -fx-alignment: center;");
                        setGraphic(currentBadge);
                    }
                    setText(null);
                }
            }
        });

        // Row Factory with ContextMenu (Enable/Disable, Delete)
        pluginTableView.setRowFactory(tv -> {
            TableRow<InstalledPlugin> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            MenuItem toggleItem = new MenuItem();
            MenuItem deleteItem = new MenuItem();

            toggleItem.setOnAction(e -> {
                InstalledPlugin plugin = row.getItem();
                if (plugin != null && currentSelectedInstance != null) {
                    pluginManagerService.togglePluginEnabled(currentSelectedInstance, plugin);
                    pluginTableView.refresh();
                    updateFilterComboOptions();
                    updatePluginFilterPredicate();
                }
            });

            deleteItem.setOnAction(e -> {
                InstalledPlugin plugin = row.getItem();
                if (plugin != null && currentSelectedInstance != null) {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("app.confirm_single_delete", plugin.getFileName()), ButtonType.YES, ButtonType.NO);
                    alert.setTitle(I18n.get("table.ctx_delete"));
                    alert.setHeaderText(null);
                    com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                    alert.showAndWait().ifPresent(btnType -> {
                        if (btnType == ButtonType.YES) {
                            pluginManagerService.deletePlugin(currentSelectedInstance, plugin);
                            installedPluginsList.remove(plugin);
                            updateFilterComboOptions();
                            updatePluginFilterPredicate();
                            statusLabel.setText(I18n.get("app.plugin_count", installedPluginsList.size()));
                        }
                    });
                }
            });

            contextMenu.getItems().addAll(toggleItem, deleteItem);

            contextMenu.setOnShowing(e -> {
                InstalledPlugin plugin = row.getItem();
                if (plugin != null) {
                    toggleItem.setText(plugin.isEnabled() ? I18n.get("table.ctx_disable") : I18n.get("table.ctx_enable"));
                    deleteItem.setText(I18n.get("table.ctx_delete"));
                }
            });

            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu)
            );
            return row;
        });

        // ColActions only has Update button (since Enable/Disable & Delete moved to Batch Checkbox actions and Right-click menu)
        colActions.setCellFactory(column -> new TableCell<>() {
            private final Button updateBtn = new Button();
            private final HBox actionsBox = new HBox(6, updateBtn);

            {
                updateBtn.getStyleClass().add("btn-primary");
                actionsBox.setAlignment(javafx.geometry.Pos.CENTER);

                updateBtn.setOnAction(e -> {
                    InstalledPlugin plugin = getTableRow().getItem();
                    if (plugin != null && currentSelectedInstance != null && plugin.isUpdateAvailable()) {
                        statusLabel.setText(I18n.get("app.updating_plugin", plugin.getFileName()));
                        pluginManagerService.updatePlugin(currentSelectedInstance, plugin)
                                .thenAccept(v -> Platform.runLater(() -> {
                                    statusLabel.setText(I18n.get("app.update_success"));
                                    // Update the single plugin in-place without wiping updateAvailable on other plugins
                                    String rawName = plugin.getLatestFileName() != null ? plugin.getLatestFileName() : plugin.getFileName();
                                    if (!plugin.isEnabled() && !rawName.endsWith(".disabled")) {
                                        rawName = rawName + ".disabled";
                                    }
                                    plugin.setFileName(rawName);
                                    if (plugin.getLatestVersionNumber() != null) {
                                        plugin.setCurrentVersionNumber(plugin.getLatestVersionNumber());
                                    }
                                    plugin.setUpdateAvailable(false);
                                    plugin.setLatestDownloadUrl(null);
                                    plugin.setLatestFileName(null);
                                    plugin.setLastModifiedTime(System.currentTimeMillis());

                                    // Refresh view and update counters
                                    pluginTableView.refresh();
                                    updateFilterComboOptions();
                                    updatePluginFilterPredicate();

                                    long remainingUpdates = installedPluginsList.stream().filter(InstalledPlugin::isUpdateAvailable).count();
                                    if (remainingUpdates > 0) {
                                        updateAllBtn.setVisible(true);
                                    } else {
                                        updateAllBtn.setVisible(false);
                                    }
                                }))
                                .exceptionally(ex -> {
                                    Platform.runLater(() -> statusLabel.setText(I18n.get("app.update_failed", ex.getMessage())));
                                    return null;
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
                    updateBtn.setText(I18n.get("table.btn_update"));
                    updateBtn.setVisible(plugin.isUpdateAvailable());
                    updateBtn.setManaged(plugin.isUpdateAvailable());
                    setGraphic(actionsBox);
                }
            }
        });
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

        HBox topRow = new HBox(8);
        topRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Instance Icon
        javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
        iconView.setFitWidth(28);
        iconView.setFitHeight(28);

        Path instanceDir = instanceManager.getInstanceDirectory(instance);
        Path iconPath = instanceDir.resolve("icon.png");
        if (java.nio.file.Files.exists(iconPath)) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    iconPath.toUri().toString(), 28, 28, iconView::setImage);
        } else if (instance.getIcon() != null && !instance.getIcon().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    instance.getIcon(), 28, 28, iconView::setImage);
        }

        Label nameLabel = new Label(instance.getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #ffffff;");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        if (iconView.getImage() != null || java.nio.file.Files.exists(iconPath) || (instance.getIcon() != null && !instance.getIcon().isBlank())) {
            topRow.getChildren().addAll(iconView, nameLabel);
        } else {
            topRow.getChildren().add(nameLabel);
        }

        HBox badges = new HBox(6);
        Label loaderBadge = new Label(instance.getLoader().toUpperCase());
        loaderBadge.getStyleClass().add("badge-loader");
        Label versionBadge = new Label(instance.getMcVersion());
        versionBadge.getStyleClass().add("badge-version");
        badges.getChildren().addAll(loaderBadge, versionBadge);

        card.getChildren().addAll(topRow, badges);

        // Right-Click Context Menu for changing instance icon
        ContextMenu contextMenu = new ContextMenu();
        MenuItem changeIconItem = new MenuItem(I18n.get("instance.ctx_change_icon"));
        changeIconItem.setOnAction(e -> handleSelectCustomIcon(instance));
        MenuItem removeIconItem = new MenuItem(I18n.get("instance.ctx_remove_icon"));
        removeIconItem.setOnAction(e -> handleRemoveCustomIcon(instance));
        contextMenu.getItems().addAll(changeIconItem, removeIconItem);

        card.setOnContextMenuRequested(e -> {
            contextMenu.show(card, e.getScreenX(), e.getScreenY());
        });

        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                selectInstance(instance);
                refreshInstanceList();
            }
        });

        return card;
    }

    private void handleSelectCustomIcon(ServerInstance instance) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("instance.ctx_change_icon"));
        fileChooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(I18n.get("instance.icon_file_filter"), "*.png", "*.jpg", "*.jpeg", "*.webp")
        );
        java.io.File selected = fileChooser.showOpenDialog(rootPane.getScene().getWindow());
        if (selected != null) {
            try {
                Path instanceDir = instanceManager.getInstanceDirectory(instance);
                if (!java.nio.file.Files.exists(instanceDir)) {
                    java.nio.file.Files.createDirectories(instanceDir);
                }
                Path destIcon = instanceDir.resolve("icon.png");
                java.nio.file.Files.copy(selected.toPath(), destIcon, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String iconUri = destIcon.toUri().toString();
                com.sparxilium.smartpluginassistant.service.ImageCacheService.evict(iconUri);
                com.sparxilium.smartpluginassistant.service.ImageCacheService.evict(destIcon.toAbsolutePath().toString());
                instance.setIcon(iconUri);
                instanceManager.updateInstance(instance);
                refreshInstanceList();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("instance.icon_change_failed", ex.getMessage()), ButtonType.OK);
                alert.showAndWait();
            }
        }
    }

    private void handleRemoveCustomIcon(ServerInstance instance) {
        try {
            Path instanceDir = instanceManager.getInstanceDirectory(instance);
            Path destIcon = instanceDir.resolve("icon.png");
            com.sparxilium.smartpluginassistant.service.ImageCacheService.evict(destIcon.toUri().toString());
            com.sparxilium.smartpluginassistant.service.ImageCacheService.evict(destIcon.toAbsolutePath().toString());
            java.nio.file.Files.deleteIfExists(destIcon);
            instance.setIcon(null);
            instanceManager.updateInstance(instance);
            refreshInstanceList();
        } catch (Exception ignored) {}
    }

    private void setInstanceButtonsDisabled(boolean disabled) {
        instanceSettingsBtn.setDisable(disabled);
        openPluginsFolderBtn.setDisable(disabled);
        exportInstanceZipBtn.setDisable(disabled);
        exportPluginsZipBtn.setDisable(disabled);
        if (exportScriptBtn != null) exportScriptBtn.setDisable(disabled);
        deleteInstanceBtn.setDisable(disabled);
        if (downloadPluginsBtn != null) downloadPluginsBtn.setDisable(disabled);
        addByUrlBtn.setDisable(disabled);
        checkUpdatesBtn.setDisable(disabled);
        refreshPluginsBtn.setDisable(disabled);
        if (pluginSearchField != null) pluginSearchField.setDisable(disabled);
        if (pluginFilterCombo != null) pluginFilterCombo.setDisable(disabled);
        batchEnableBtn.setDisable(disabled);
        batchDisableBtn.setDisable(disabled);
        batchDeleteBtn.setDisable(disabled);
    }

    @FXML
    private void handleBatchEnable() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> selected = installedPluginsList.stream().filter(InstalledPlugin::isSelected).toList();
        if (selected.isEmpty()) return;
        for (InstalledPlugin p : selected) {
            if (!p.isEnabled()) {
                pluginManagerService.togglePluginEnabled(currentSelectedInstance, p);
            }
        }
        refreshPlugins();
    }

    @FXML
    private void handleBatchDisable() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> selected = installedPluginsList.stream().filter(InstalledPlugin::isSelected).toList();
        if (selected.isEmpty()) return;
        for (InstalledPlugin p : selected) {
            if (p.isEnabled()) {
                pluginManagerService.togglePluginEnabled(currentSelectedInstance, p);
            }
        }
        refreshPlugins();
    }

    @FXML
    private void handleBatchDelete() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> selected = installedPluginsList.stream().filter(InstalledPlugin::isSelected).toList();
        if (selected.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("app.confirm_batch_delete", selected.size()), ButtonType.YES, ButtonType.NO);
        alert.setTitle(I18n.get("app.btn_batch_delete"));
        alert.setHeaderText(null);
        com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                for (InstalledPlugin p : selected) {
                    pluginManagerService.deletePlugin(currentSelectedInstance, p);
                }
                refreshPlugins();
            }
        });
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
                I18n.get("instance.mc_version", instance.getMcVersion()) + fuzzyText
        );
        refreshPlugins();
    }

    @FXML
    private void handleOpenInstanceSettings() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/instance-settings-dialog.fxml"));
            Parent root = loader.load();

            InstanceSettingsDialogController controller = loader.getController();
            controller.init(currentSelectedInstance);

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("instance_settings_dialog_w", 580);
            double h = prefs.getDouble("instance_settings_dialog_h", 460);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("settings.title") + " - " + currentSelectedInstance.getName());
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(480);
            stage.setMinHeight(380);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("instance_settings_dialog_w", stage.getWidth());
                    prefs.putDouble("instance_settings_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();

            if (controller.isSaved()) {
                instanceManager.updateInstance(currentSelectedInstance);
                selectInstance(currentSelectedInstance);
                refreshInstanceList();
            }
        } catch (IOException e) {
            logger.error("Exception occurred", e);
        }
    }

    @FXML
    public void refreshPlugins() {
        if (currentSelectedInstance == null) return;
        List<InstalledPlugin> plugins = pluginManagerService.scanPlugins(currentSelectedInstance);
        installedPluginsList.setAll(plugins);
        updateAllBtn.setVisible(false);
        updateFilterComboOptions();
        updatePluginFilterPredicate();
        statusLabel.setText(I18n.get("app.plugin_count", plugins.size()));
    }

    /** Resolve the hosting platform string for a plugin ("modrinth", "hangar", "local") */
    private String resolvePlatform(InstalledPlugin p) {
        if (p.getHostingPlatform() != null && !p.getHostingPlatform().isBlank()) {
            return p.getHostingPlatform().toLowerCase();
        }
        // Legacy: if no explicit hostingPlatform, infer from projectId (Modrinth) or hangarNamespace
        if (p.getHangarNamespace() != null && !p.getHangarNamespace().isBlank()) return "hangar";
        if (p.getProjectId() != null && !p.getProjectId().isBlank()) return "modrinth";
        return "local";
    }

    @FXML
    private void handleCheckUpdates() {
        if (currentSelectedInstance == null || installedPluginsList.isEmpty()) {
            statusLabel.setText(I18n.get("app.no_plugins_to_check"));
            return;
        }

        globalProgress.setVisible(true);
        statusLabel.setText(I18n.get("app.checking_updates"));

        List<InstalledPlugin> pluginsCopy = new java.util.ArrayList<>(installedPluginsList);

        // Check all plugins (repo lookup is dynamic now)
        pluginManagerService.checkPluginUpdates(currentSelectedInstance, pluginsCopy)
                .thenAccept(updatedList -> Platform.runLater(() -> {
                    globalProgress.setVisible(false);
                    updateFilterComboOptions();
                    updatePluginFilterPredicate();
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
    private void handleOpenPluginDownloader() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/plugin-downloader-dialog.fxml"));
            Parent root = loader.load();

            PluginDownloaderController controller = loader.getController();
            
            Stage stage = new Stage();
            stage.setTitle(I18n.get("app.download_plugins_title", currentSelectedInstance.getName()));
            stage.initModality(Modality.APPLICATION_MODAL);
            
            com.sparxilium.smartpluginassistant.controller.module.PluginBrowserContext context = new com.sparxilium.smartpluginassistant.controller.module.PluginBrowserContext(
                    currentSelectedInstance,
                    modrinthService,
                    hangarService,
                    voxelService,
                    spigetService,
                    instanceManager,
                    this::refreshPlugins,
                    stage
            );
            
            controller.init(context);

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("downloader_dialog_w", 1200);
            double h = prefs.getDouble("downloader_dialog_h", 760);

            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(900);
            stage.setMinHeight(600);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("downloader_dialog_w", stage.getWidth());
                    prefs.putDouble("downloader_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();
        } catch (IOException e) {
            logger.error("Failed to open plugin downloader", e);
        }
    }

    @FXML
    private void handleAddByUrl() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/add-by-url-dialog.fxml"));
            Parent root = loader.load();

            AddByUrlDialogController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, hangarService, instanceManager, this::refreshPlugins);

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("add_by_url_dialog_w", 620);
            double h = prefs.getDouble("add_by_url_dialog_h", 480);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("url.window_title", currentSelectedInstance.getName()));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(540);
            stage.setMinHeight(380);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("add_by_url_dialog_w", stage.getWidth());
                    prefs.putDouble("add_by_url_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();
        } catch (IOException e) {
            logger.error("Exception occurred", e);
        }
    }

    @FXML
    private void handleOpenImportPlugins() {
        if (currentSelectedInstance == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/import-plugins-dialog.fxml"));
            Parent root = loader.load();

            ImportPluginsDialogController controller = loader.getController();
            controller.init(currentSelectedInstance, modrinthService, instanceManager, this::refreshPlugins);

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("import_plugins_dialog_w", 860);
            double h = prefs.getDouble("import_plugins_dialog_h", 620);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("import.window_title", currentSelectedInstance.getName()));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(720);
            stage.setMinHeight(480);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("import_plugins_dialog_w", stage.getWidth());
                    prefs.putDouble("import_plugins_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();
        } catch (IOException e) {
            logger.error("Exception occurred", e);
        }
    }

    @FXML
    private void handleCreateInstance() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/sparxilium/smartpluginassistant/create-instance-dialog.fxml"));
            Parent root = loader.load();

            java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
            double w = prefs.getDouble("create_instance_dialog_w", 520);
            double h = prefs.getDouble("create_instance_dialog_h", 420);

            Stage stage = new Stage();
            stage.setTitle(I18n.get("create.title"));
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene scene = new Scene(root, w, h);
            scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(460);
            stage.setMinHeight(340);

            stage.setOnCloseRequest(e -> {
                if (!stage.isMaximized()) {
                    prefs.putDouble("create_instance_dialog_w", stage.getWidth());
                    prefs.putDouble("create_instance_dialog_h", stage.getHeight());
                }
            });

            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(stage);
            stage.showAndWait();

            CreateInstanceDialogController controller = loader.getController();
            ServerInstance created = controller.getCreatedInstance();
            if (created != null) {
                instanceManager.createInstance(created);
                selectInstance(created);
                refreshInstanceList();
            }
        } catch (IOException e) {
            logger.error("Exception occurred", e);
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
            logger.error("Exception occurred", e);
            Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.open_folder_error", e.getMessage()), ButtonType.OK);
            com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
            alert.showAndWait();
        }
    }

    @FXML
    private void handleExportInstanceZip() {
        if (currentSelectedInstance == null) return;
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("app.export_instance_zip_title"));
        fileChooser.setInitialFileName(currentSelectedInstance.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + "-instance.zip");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP Archive (*.zip)", "*.zip"));

        java.io.File targetFile = fileChooser.showSaveDialog(rootPane.getScene().getWindow());
        if (targetFile != null) {
            try {
                instanceManager.exportInstanceToZip(currentSelectedInstance, targetFile.toPath());
                Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("app.export_instance_zip_success", currentSelectedInstance.getName(), targetFile.getAbsolutePath()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_instance_zip_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            } catch (Exception e) {
                logger.error("Failed to export instance to zip", e);
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.export_zip_failed", e.getMessage()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_instance_zip_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            }
        }
    }

    @FXML
    private void handleExportPluginsZip() {
        if (currentSelectedInstance == null) return;
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("app.export_plugins_zip_title"));
        fileChooser.setInitialFileName(currentSelectedInstance.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + "-plugins.zip");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP Archive (*.zip)", "*.zip"));

        java.io.File targetFile = fileChooser.showSaveDialog(rootPane.getScene().getWindow());
        if (targetFile != null) {
            try {
                instanceManager.exportPluginsToZip(currentSelectedInstance, targetFile.toPath());
                Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("app.export_plugins_zip_success", currentSelectedInstance.getName(), targetFile.getAbsolutePath()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_plugins_zip_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            } catch (Exception e) {
                logger.error("Failed to export plugins to zip", e);
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.export_zip_failed", e.getMessage()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_plugins_zip_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            }
        }
    }

    @FXML
    private void handleExportScript() {
        if (currentSelectedInstance == null) return;
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("app.export_script_title"));
        fileChooser.setInitialFileName("install-plugins-" + currentSelectedInstance.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + ".sh");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Shell Script (*.sh)", "*.sh"));

        java.io.File targetFile = fileChooser.showSaveDialog(rootPane.getScene().getWindow());
        if (targetFile == null) return;

        globalProgress.setVisible(true);
        statusLabel.setText(I18n.get("app.export_script_generating"));

        CompletableFuture.supplyAsync(() -> {
            List<InstalledPlugin> currentPlugins = new ArrayList<>(installedPluginsList);
            List<CompletableFuture<InstanceManager.ScriptItem>> itemFutures = new ArrayList<>();

            for (InstalledPlugin p : currentPlugins) {
                String sha512 = p.getSha512();
                String fileName = p.getFileName();
                boolean enabled = p.isEnabled();

                // 1. Direct hit from plugin latestDownloadUrl if available
                if (p.getLatestDownloadUrl() != null && !p.getLatestDownloadUrl().isBlank()) {
                    itemFutures.add(CompletableFuture.completedFuture(
                            new InstanceManager.ScriptItem(fileName, p.getLatestDownloadUrl(), sha512, p.getHostingPlatform(), p.getCurrentVersionNumber(), enabled)
                    ));
                    continue;
                }

                // 2. Check metadata store
                PluginMetadataStore.DownloadRecord record = PluginMetadataStore.findRecord(instanceManager, currentSelectedInstance, fileName, sha512);

                if (record != null && "hangar".equalsIgnoreCase(record.hostingPlatform) && record.hangarNamespace != null) {
                    // Query Hangar for download URL
                    String[] parts = record.hangarNamespace.split("/", 2);
                    if (parts.length == 2) {
                        String author = parts[0];
                        String slug = parts[1];
                        String platform = HangarService.toPlatformKey(currentSelectedInstance.getLoader());

                        CompletableFuture<InstanceManager.ScriptItem> hf = hangarService.getVersions(author, slug, platform, null, 0, 10)
                                .thenApply(page -> {
                                    for (HangarVersion hv : page.versions()) {
                                        if (record.versionNumber != null && record.versionNumber.equalsIgnoreCase(hv.getVersionNumber())
                                                || (record.versionId != null && record.versionId.equalsIgnoreCase(String.valueOf(hv.getId())))) {
                                            HangarVersion.PlatformDownload pd = hv.getPaperDownload();
                                            if (pd != null && pd.downloadUrl != null) {
                                                return new InstanceManager.ScriptItem(fileName, pd.downloadUrl, sha512, "hangar", hv.getVersionNumber(), enabled);
                                            }
                                        }
                                    }
                                    // Fallback to first paper download in page
                                    if (!page.versions().isEmpty()) {
                                        HangarVersion first = page.versions().get(0);
                                        HangarVersion.PlatformDownload pd = first.getPaperDownload();
                                        if (pd != null && pd.downloadUrl != null) {
                                            return new InstanceManager.ScriptItem(fileName, pd.downloadUrl, sha512, "hangar", first.getVersionNumber(), enabled);
                                        }
                                    }
                                    return new InstanceManager.ScriptItem(fileName, null, sha512, "hangar", p.getCurrentVersionNumber(), enabled);
                                }).exceptionally(ex -> new InstanceManager.ScriptItem(fileName, null, sha512, "hangar", p.getCurrentVersionNumber(), enabled));
                        itemFutures.add(hf);
                        continue;
                    }
                }

                if (record != null && record.versionId != null && !record.versionId.isBlank()) {
                    // Query Modrinth version by versionId
                    CompletableFuture<InstanceManager.ScriptItem> mf = modrinthService.getVersion(record.versionId)
                            .thenApply(mv -> {
                                ModrinthVersion.ModrinthFile primary = mv.getPrimaryFile();
                                String dlUrl = primary != null ? primary.getUrl() : null;
                                return new InstanceManager.ScriptItem(fileName, dlUrl, sha512, "modrinth", mv.getVersionNumber(), enabled);
                            }).exceptionally(ex -> new InstanceManager.ScriptItem(fileName, null, sha512, "modrinth", p.getCurrentVersionNumber(), enabled));
                    itemFutures.add(mf);
                    continue;
                }

                if (sha512 != null && !sha512.isBlank()) {
                    // Query Modrinth by SHA-512
                    CompletableFuture<InstanceManager.ScriptItem> hf = modrinthService.getVersionByHash(sha512)
                            .thenApply(mv -> {
                                if (mv != null && mv.getPrimaryFile() != null) {
                                    return new InstanceManager.ScriptItem(fileName, mv.getPrimaryFile().getUrl(), sha512, "modrinth", mv.getVersionNumber(), enabled);
                                }
                                return new InstanceManager.ScriptItem(fileName, null, sha512, "local", p.getCurrentVersionNumber(), enabled);
                            }).exceptionally(ex -> new InstanceManager.ScriptItem(fileName, null, sha512, "local", p.getCurrentVersionNumber(), enabled));
                    itemFutures.add(hf);
                    continue;
                }

                // Fallback: local plugin without online match
                itemFutures.add(CompletableFuture.completedFuture(
                        new InstanceManager.ScriptItem(fileName, null, sha512, "local", p.getCurrentVersionNumber(), enabled)
                ));
            }

            return CompletableFuture.allOf(itemFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> itemFutures.stream().map(CompletableFuture::join).toList())
                    .join();
        }).thenAccept(scriptItems -> Platform.runLater(() -> {
            globalProgress.setVisible(false);
            statusLabel.setText(I18n.get("app.status_ready"));
            try {
                instanceManager.exportPluginsScript(currentSelectedInstance, scriptItems, targetFile.toPath());
                long resolvedCount = scriptItems.stream().filter(item -> item.downloadUrl != null && !item.downloadUrl.isBlank()).count();
                Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("app.export_script_success", currentSelectedInstance.getName(), targetFile.getAbsolutePath(), resolvedCount), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_script_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            } catch (Exception e) {
                logger.error("Failed to export plugins script", e);
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.export_zip_failed", e.getMessage()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_script_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            }
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                globalProgress.setVisible(false);
                statusLabel.setText(I18n.get("app.status_ready"));
                logger.error("Failed to resolve script items", ex);
                Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("app.export_zip_failed", ex.getMessage()), ButtonType.OK);
                alert.setTitle(I18n.get("app.export_script_title"));
                alert.setHeaderText(null);
                com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
                alert.showAndWait();
            });
            return null;
        });
    }

    @FXML
    private void handleDeleteCurrentInstance() {
        if (currentSelectedInstance == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("app.confirm_delete_instance", currentSelectedInstance.getName()), ButtonType.YES, ButtonType.NO);
        alert.setTitle(I18n.get("app.delete_instance"));
        alert.setHeaderText(null);
        com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(alert);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                instanceManager.deleteInstance(currentSelectedInstance);
                currentSelectedInstance = null;
                refreshInstanceList();
            }
        });
    }
}
