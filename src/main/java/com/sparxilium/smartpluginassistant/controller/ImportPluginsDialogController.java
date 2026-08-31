package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class ImportPluginsDialogController {
    private static final Logger logger = LogManager.getLogger(ImportPluginsDialogController.class);

    @FXML private Label titleLabel;
    @FXML private Label hintLabel;
    @FXML private TextField folderPathField;
    @FXML private Button browseFolderBtn;
    @FXML private Button rescanBtn;
    @FXML private TableView<ImportItem> importTableView;
    @FXML private TableColumn<ImportItem, Boolean> colSelect;
    @FXML private TableColumn<ImportItem, String> colFilename;
    @FXML private TableColumn<ImportItem, String> colSize;
    @FXML private TableColumn<ImportItem, String> colDetected;
    @FXML private TableColumn<ImportItem, String> colModrinth;
    @FXML private Button selectAllBtn;
    @FXML private Button deselectAllBtn;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Button closeBtn;
    @FXML private Button startImportBtn;

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginsImportedCallback;

    private final ObservableList<ImportItem> itemsList = FXCollections.observableArrayList();
    private File currentDirectory;

    public static class ImportItem {
        private final File file;
        private final BooleanProperty selected = new SimpleBooleanProperty(true);
        private final StringProperty fileName = new SimpleStringProperty();
        private final StringProperty sizeFormatted = new SimpleStringProperty();
        private final StringProperty detectedInfo = new SimpleStringProperty("-");
        private final StringProperty modrinthStatus = new SimpleStringProperty("...");
        private String sha1;
        private String sha512;
        private String projectId;
        private String versionId;
        private String versionNumber;

        public ImportItem(File file) {
            this.file = file;
            this.fileName.set(file.getName());
            this.sizeFormatted.set(formatFileSize(file.length()));
        }

        public File getFile() { return file; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean value) { selected.set(value); }
        public BooleanProperty selectedProperty() { return selected; }
        public String getFileName() { return fileName.get(); }
        public String getSizeFormatted() { return sizeFormatted.get(); }
        public String getDetectedInfo() { return detectedInfo.get(); }
        public void setDetectedInfo(String val) { detectedInfo.set(val); }
        public StringProperty detectedInfoProperty() { return detectedInfo; }
        public String getModrinthStatus() { return modrinthStatus.get(); }
        public void setModrinthStatus(String val) { modrinthStatus.set(val); }
        public StringProperty modrinthStatusProperty() { return modrinthStatus; }

        public String getSha1() { return sha1; }
        public void setSha1(String sha1) { this.sha1 = sha1; }
        public String getSha512() { return sha512; }
        public void setSha512(String sha512) { this.sha512 = sha512; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getVersionNumber() { return versionNumber; }
        public void setVersionNumber(String versionNumber) { this.versionNumber = versionNumber; }

        private static String formatFileSize(long bytes) {
            if (bytes <= 0) return "0 B";
            final String[] units = new String[]{"B", "KB", "MB", "GB"};
            int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
            digitGroups = Math.min(digitGroups, units.length - 1);
            return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
        }
    }

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginsImportedCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginsImportedCallback = onPluginsImportedCallback;

        setupTable();
        applyI18n();
        updateSelectionCount();
    }

    private void setupTable() {
        importTableView.setItems(itemsList);

        colSelect.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        colSelect.setCellFactory(CheckBoxTableCell.forTableColumn(colSelect));
        colSelect.setEditable(true);

        colFilename.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getFileName()));
        colSize.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getSizeFormatted()));
        colDetected.setCellValueFactory(cellData -> cellData.getValue().detectedInfoProperty());
        colModrinth.setCellValueFactory(cellData -> cellData.getValue().modrinthStatusProperty());

        importTableView.setEditable(true);

        itemsList.addListener((javafx.collections.ListChangeListener<ImportItem>) c -> updateSelectionCount());
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("import.title"));
        hintLabel.setText(I18n.get("import.hint"));
        browseFolderBtn.setText(I18n.get("import.btn_browse"));
        rescanBtn.setText(I18n.get("import.btn_scan"));
        selectAllBtn.setText(I18n.get("import.btn_select_all"));
        deselectAllBtn.setText(I18n.get("import.btn_deselect_all"));
        closeBtn.setText(I18n.get("import.btn_close"));
        colSelect.setText(I18n.get("import.col_select"));
        colFilename.setText(I18n.get("import.col_filename"));
        colSize.setText(I18n.get("import.col_size"));
        colDetected.setText(I18n.get("import.col_detected"));
        colModrinth.setText(I18n.get("import.col_modrinth"));
        statusLabel.setText(I18n.get("import.hint"));
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        long selectedCount = itemsList.stream().filter(ImportItem::isSelected).count();
        startImportBtn.setText(I18n.get("import.btn_import", selectedCount));
        startImportBtn.setDisable(selectedCount == 0);
    }

    @FXML
    private void handleBrowseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("import.title"));
        if (currentDirectory != null && currentDirectory.exists()) {
            chooser.setInitialDirectory(currentDirectory);
        }
        File selectedDir = chooser.showDialog(folderPathField.getScene().getWindow());
        if (selectedDir != null) {
            currentDirectory = selectedDir;
            folderPathField.setText(selectedDir.getAbsolutePath());
            scanAndResolveDirectory(selectedDir);
        }
    }

    @FXML
    private void handleRescan() {
        if (currentDirectory != null && currentDirectory.exists()) {
            scanAndResolveDirectory(currentDirectory);
        }
    }

    private void scanAndResolveDirectory(File dir) {
        itemsList.clear();
        progressIndicator.setVisible(true);
        statusLabel.setText(I18n.get("import.status_scanning"));
        logger.info("Scanning directory for jar files: {}", dir.getAbsolutePath());

        CompletableFuture.supplyAsync(() -> {
            List<File> jarFiles = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir.toPath())) {
                jarFiles = stream
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".jar") || name.endsWith(".jar.disabled");
                        })
                        .map(Path::toFile)
                        .toList();
            } catch (IOException e) {
                logger.error("Error listing files in directory {}: {}", dir.getAbsolutePath(), e.getMessage(), e);
            }
            return jarFiles;
        }).thenAccept(files -> Platform.runLater(() -> {
            if (files.isEmpty()) {
                progressIndicator.setVisible(false);
                statusLabel.setText(I18n.get("import.no_jars_found"));
                return;
            }

            for (File file : files) {
                ImportItem item = new ImportItem(file);
                item.selectedProperty().addListener((obs, oldV, newV) -> updateSelectionCount());
                itemsList.add(item);
            }

            updateSelectionCount();
            resolveModrinthLinks(itemsList);
        }));
    }

    private void resolveModrinthLinks(List<ImportItem> items) {
        statusLabel.setText(I18n.get("import.status_hashing", 0, items.size()));
        logger.info("Starting SHA-1 calculation and Modrinth linking for {} items", items.size());

        CompletableFuture.runAsync(() -> {
            Map<String, ImportItem> hashMap = new HashMap<>();
            List<String> hashesToQuery = new ArrayList<>();

            for (ImportItem item : items) {
                // 1. Read internal descriptor info
                PluginManagerService.JarPluginInfo info = PluginManagerService.readJarPluginInfo(item.getFile());
                if (info != null) {
                    String descStr = (info.name != null ? info.name : "") +
                            (info.version != null ? " v" + info.version : "");
                    Platform.runLater(() -> item.setDetectedInfo(descStr.isBlank() ? "-" : descStr));
                }

                // 2. Compute SHA-1 and SHA-512
                String sha1 = PluginManagerService.calculateSha1(item.getFile());
                String sha512 = PluginManagerService.calculateSha512(item.getFile());
                item.setSha1(sha1);
                item.setSha512(sha512);
                if (sha512 != null) {
                    hashMap.put(sha512.toLowerCase(), item);
                    hashesToQuery.add(sha512.toLowerCase());
                } else if (sha1 != null) {
                    hashMap.put(sha1.toLowerCase(), item);
                    hashesToQuery.add(sha1.toLowerCase());
                }
            }

            // 3. Batch query Modrinth /version_files
            modrinthService.getVersionsByHashes(hashesToQuery)
                    .thenAccept(versionMap -> Platform.runLater(() -> {
                        for (Map.Entry<String, ModrinthVersion> entry : versionMap.entrySet()) {
                            String hash = entry.getKey().toLowerCase();
                            ModrinthVersion ver = entry.getValue();
                            ImportItem item = hashMap.get(hash);
                            if (item != null && ver != null) {
                                item.setProjectId(ver.getProjectId());
                                item.setVersionId(ver.getId());
                                item.setVersionNumber(ver.getVersionNumber());
                                item.setModrinthStatus(I18n.get("import.modrinth_linked", ver.getVersionNumber(), ver.getVersionType()));
                                logger.info("Linked jar '{}' (hash={}) -> Modrinth project='{}', version='{}'",
                                        item.getFileName(), hash, ver.getProjectId(), ver.getVersionNumber());
                            }
                        }

                        // For unlinked items, mark as local unlinked
                        for (ImportItem item : items) {
                            if (item.getProjectId() == null) {
                                item.setModrinthStatus(I18n.get("import.modrinth_unlinked"));
                            }
                        }

                        progressIndicator.setVisible(false);
                        long selected = itemsList.stream().filter(ImportItem::isSelected).count();
                        statusLabel.setText(I18n.get("import.status_ready", items.size(), selected));
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            logger.error("Failed to query Modrinth by hashes", ex);
                            for (ImportItem item : items) {
                                if (item.getProjectId() == null) {
                                    item.setModrinthStatus(I18n.get("import.modrinth_unlinked"));
                                }
                            }
                            progressIndicator.setVisible(false);
                            statusLabel.setText(I18n.get("import.status_ready", items.size(), itemsList.stream().filter(ImportItem::isSelected).count()));
                        });
                        return null;
                    });
        });
    }

    @FXML
    private void handleSelectAll() {
        for (ImportItem item : itemsList) {
            item.setSelected(true);
        }
        updateSelectionCount();
    }

    @FXML
    private void handleDeselectAll() {
        for (ImportItem item : itemsList) {
            item.setSelected(false);
        }
        updateSelectionCount();
    }

    @FXML
    private void handleStartImport() {
        List<ImportItem> selectedItems = itemsList.stream().filter(ImportItem::isSelected).toList();
        if (selectedItems.isEmpty()) {
            return;
        }

        progressIndicator.setVisible(true);
        startImportBtn.setDisable(true);
        browseFolderBtn.setDisable(true);
        rescanBtn.setDisable(true);

        Path targetPluginsDir = instanceManager.getPluginsDirectory(currentInstance);

        CompletableFuture.runAsync(() -> {
            int total = selectedItems.size();
            int imported = 0;

            for (int i = 0; i < total; i++) {
                ImportItem item = selectedItems.get(i);
                final int currentIdx = i + 1;
                Platform.runLater(() -> statusLabel.setText(I18n.get("import.status_importing", currentIdx, total)));

                try {
                    Path destFile = targetPluginsDir.resolve(item.getFileName());
                    Files.copy(item.getFile().toPath(), destFile, StandardCopyOption.REPLACE_EXISTING);

                    // If Modrinth info is resolved, write to metadata store
                    if (item.getProjectId() != null && item.getVersionId() != null) {
                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                item.getProjectId(),
                                item.getVersionId(),
                                item.getVersionNumber(),
                                item.getFileName(),
                                item.getSha1(),
                                item.getSha512()
                        );
                        PluginMetadataStore.saveRecord(instanceManager, currentInstance, record);
                    }
                    imported++;
                } catch (IOException e) {
                    logger.error("Failed to copy plugin file '{}' to instance: {}", item.getFileName(), e.getMessage(), e);
                }
            }

            final int finalImported = imported;
            Platform.runLater(() -> {
                progressIndicator.setVisible(false);
                startImportBtn.setDisable(false);
                browseFolderBtn.setDisable(false);
                rescanBtn.setDisable(false);
                statusLabel.setText(I18n.get("import.status_complete", finalImported));
                if (onPluginsImportedCallback != null) {
                    onPluginsImportedCallback.run();
                }
            });
        });
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) closeBtn.getScene().getWindow();
        stage.close();
    }
}
