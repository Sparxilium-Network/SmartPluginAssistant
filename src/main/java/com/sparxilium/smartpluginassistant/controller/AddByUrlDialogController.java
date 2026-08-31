package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ModrinthProject;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;

public class AddByUrlDialogController {
    @FXML private Label titleLabel;
    @FXML private Label hintLabel;
    @FXML private TextField urlField;
    @FXML private Button resolveBtn;
    @FXML private VBox previewContainer;
    @FXML private ImageView iconView;
    @FXML private Label projectTitleLabel;
    @FXML private Label authorLabel;
    @FXML private Label descLabel;
    @FXML private Label targetVersionLabel;
    @FXML private VBox prereleaseWarningBox;
    @FXML private Label prereleaseWarningLabel;
    @FXML private CheckBox prereleaseConfirmCheckBox;
    @FXML private Button downloadBtn;
    @FXML private Button closeBtn;
    @FXML private ProgressIndicator progressIndicator;

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private ModrinthProject resolvedProject;
    private ModrinthVersion targetVersion;
    private boolean isPrereleaseOnly = false;

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

        if (prereleaseConfirmCheckBox != null) {
            prereleaseConfirmCheckBox.selectedProperty().addListener((obs, oldV, newV) -> {
                if (isPrereleaseOnly) {
                    downloadBtn.setDisable(!newV);
                }
            });
        }

        applyI18n();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("url.title"));
        hintLabel.setText(I18n.get("url.hint"));
        resolveBtn.setText(I18n.get("url.btn_resolve"));
        downloadBtn.setText(I18n.get("url.btn_download"));
        closeBtn.setText(I18n.get("url.btn_close"));
    }

    @FXML
    private void handleResolve() {
        String input = urlField.getText().trim();
        String slug = ModrinthService.extractSlugOrId(input);
        if (slug == null || slug.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, I18n.get("url.err_invalid_url"));
            return;
        }

        progressIndicator.setVisible(true);
        previewContainer.setVisible(false);
        isPrereleaseOnly = false;

        modrinthService.getProject(slug)
                .thenCompose(project -> {
                    this.resolvedProject = project;
                    List<String> loaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : java.util.Collections.emptyList();
                    String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;
                    return modrinthService.getProjectVersions(project.getId(), loaders, mcVersion);
                })
                .thenAccept(versions -> Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    if (versions.isEmpty()) {
                        showAlert(Alert.AlertType.WARNING, I18n.get("url.no_compat_version",
                                resolvedProject.getTitle(),
                                (currentInstance != null ? currentInstance.getEffectiveLoaders() : ""),
                                currentInstance.getMcVersion()));
                        return;
                    }

                    // Find first release version
                    ModrinthVersion releaseVer = versions.stream()
                            .filter(v -> "release".equalsIgnoreCase(v.getVersionType()))
                            .findFirst()
                            .orElse(null);

                    if (releaseVer != null) {
                        this.targetVersion = releaseVer;
                        this.isPrereleaseOnly = false;
                    } else {
                        // Only pre-release (beta/alpha) versions available
                        this.targetVersion = versions.get(0);
                        this.isPrereleaseOnly = true;
                    }

                    showPreview();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        progressIndicator.setVisible(false);
                        showAlert(Alert.AlertType.ERROR, I18n.get("url.err_resolve_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private void showPreview() {
        previewContainer.setVisible(true);
        projectTitleLabel.setText(resolvedProject.getTitle());
        authorLabel.setText(I18n.get("url.project_id", resolvedProject.getSlug()));
        descLabel.setText(resolvedProject.getDescription());

        ModrinthVersion.ModrinthFile file = targetVersion.getPrimaryFile();
        String fileName = file != null ? file.getFilename() : "Unknown";
        String verType = targetVersion.getVersionType() != null ? targetVersion.getVersionType().toUpperCase() : "RELEASE";
        targetVersionLabel.setText(I18n.get("url.compat_version", targetVersion.getVersionNumber() + " [" + verType + "]", fileName));

        if (isPrereleaseOnly) {
            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.prerelease_only_warn", verType));
            prereleaseConfirmCheckBox.setText(I18n.get("url.prerelease_confirm_check", targetVersion.getVersionNumber()));
            prereleaseConfirmCheckBox.setSelected(false);
            downloadBtn.setDisable(true);
        } else {
            prereleaseWarningBox.setVisible(false);
            prereleaseWarningBox.setManaged(false);
            downloadBtn.setDisable(false);
        }

        if (resolvedProject.getIconUrl() != null && !resolvedProject.getIconUrl().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    resolvedProject.getIconUrl(), 48, 48, iconView::setImage);
        }
    }

    @FXML
    private void handleDownload() {
        if (targetVersion == null || targetVersion.getPrimaryFile() == null) return;
        if (isPrereleaseOnly && prereleaseConfirmCheckBox != null && !prereleaseConfirmCheckBox.isSelected()) {
            return;
        }

        downloadBtn.setDisable(true);
        downloadBtn.setText(I18n.get("modrinth.btn_installing"));

        ModrinthVersion.ModrinthFile primaryFile = targetVersion.getPrimaryFile();
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path dest = pluginsDir.resolve(primaryFile.getFilename());

        modrinthService.downloadFile(primaryFile.getUrl(), dest, null)
                .thenAccept(path -> Platform.runLater(() -> {
                    // Record metadata for future update checks
                    try {
                        String sha1 = primaryFile.getHashes() != null ? primaryFile.getHashes().get("sha1") : null;
                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                resolvedProject.getId(),
                                targetVersion.getId(),
                                targetVersion.getVersionNumber(),
                                primaryFile.getFilename(),
                                sha1
                        );
                        PluginMetadataStore.saveRecord(instanceManager, currentInstance, record);
                    } catch (Exception ignored) {}

                    downloadBtn.setText(I18n.get("url.download_complete"));
                    downloadBtn.setStyle("-fx-background-color: #2ecc71;");
                    if (onPluginInstalledCallback != null) {
                        onPluginInstalledCallback.run();
                    }
                    Stage stage = (Stage) downloadBtn.getScene().getWindow();
                    stage.close();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        downloadBtn.setDisable(false);
                        downloadBtn.setText(I18n.get("url.btn_download"));
                        showAlert(Alert.AlertType.ERROR, I18n.get("url.err_download_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) urlField.getScene().getWindow();
        stage.close();
    }

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.showAndWait();
    }
}
