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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AddByUrlDialogController {
    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(AddByUrlDialogController.class);

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
    private boolean isIncompatibleVersion = false;

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

        if (prereleaseConfirmCheckBox != null) {
            prereleaseConfirmCheckBox.selectedProperty().addListener((obs, oldV, newV) -> {
                if (isPrereleaseOnly || isIncompatibleVersion) {
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
        logger.info("Resolving Modrinth URL input: '{}'", input);
        ModrinthService.ResolvedUrlInfo urlInfo = ModrinthService.parseUrlInfo(input);
        if (urlInfo == null || urlInfo.projectSlug == null || urlInfo.projectSlug.isEmpty()) {
            logger.warn("Failed to parse project slug from URL: '{}'", input);
            showAlert(Alert.AlertType.WARNING, I18n.get("url.err_invalid_url"));
            return;
        }

        logger.info("Parsed URL info: slug='{}', specificVersionId='{}'", urlInfo.projectSlug, urlInfo.specificVersionId);

        progressIndicator.setVisible(true);
        previewContainer.setVisible(false);
        isPrereleaseOnly = false;
        isIncompatibleVersion = false;

        modrinthService.getProject(urlInfo.projectSlug)
                .thenCompose(project -> {
                    this.resolvedProject = project;
                    logger.info("Fetched project: id='{}', title='{}', slug='{}'", project.getId(), project.getTitle(), project.getSlug());

                    if (urlInfo.specificVersionId != null && !urlInfo.specificVersionId.isBlank()) {
                        // User specifically pasted a direct version URL (e.g. /version/4.11-7a2d09a)
                        logger.info("Resolving specific version '{}' for project '{}'...", urlInfo.specificVersionId, project.getId());
                        return modrinthService.resolveVersionByProjectAndVersion(project.getId(), urlInfo.specificVersionId)
                                .thenApply(ver -> {
                                    if (ver != null) {
                                        logger.info("Successfully resolved specific version: number='{}', id='{}'", ver.getVersionNumber(), ver.getId());
                                        return List.of(ver);
                                    } else {
                                        logger.warn("Could not find specific version '{}', resolving all versions as fallback", urlInfo.specificVersionId);
                                        return Collections.<ModrinthVersion>emptyList();
                                    }
                                });
                    } else {
                        // Query project versions
                        List<String> loaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : Collections.emptyList();
                        String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;
                        logger.info("Fetching versions with loaders={}, mcVersion={}", loaders, mcVersion);
                        return modrinthService.getProjectVersions(project.getId(), loaders, mcVersion)
                                .thenCompose(compatVersions -> {
                                    if (!compatVersions.isEmpty()) {
                                        logger.info("Found {} compatible versions", compatVersions.size());
                                        return CompletableFuture.completedFuture(compatVersions);
                                    }
                                    logger.warn("No strictly compatible versions found, fetching ANY project versions as fallback");
                                    return modrinthService.getProjectVersions(project.getId(), Collections.emptyList(), null);
                                });
                    }
                })
                .thenAccept(versions -> Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    if (versions.isEmpty()) {
                        logger.warn("No versions returned at all for project '{}'", resolvedProject.getId());
                        showAlert(Alert.AlertType.WARNING, I18n.get("url.no_compat_version"));
                        return;
                    }

                    List<String> currentLoaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : Collections.emptyList();
                    String currentMc = currentInstance != null ? currentInstance.getMcVersion() : null;

                    ModrinthVersion candidate = versions.get(0);
                    logger.info("Evaluating candidate version: number='{}', type='{}', loaders={}, mcVersions={}",
                            candidate.getVersionNumber(), candidate.getVersionType(), candidate.getLoaders(), candidate.getGameVersions());

                    boolean matchesLoader = currentLoaders.isEmpty() || candidate.getLoaders() == null ||
                            candidate.getLoaders().stream().anyMatch(l -> currentLoaders.stream().anyMatch(cl -> cl.equalsIgnoreCase(l)));
                    boolean matchesGameVer = currentMc == null || candidate.getGameVersions() == null ||
                            candidate.getGameVersions().contains(currentMc);

                    boolean isRelease = "release".equalsIgnoreCase(candidate.getVersionType());

                    if (matchesLoader && matchesGameVer) {
                        if (isRelease) {
                            this.targetVersion = candidate;
                            this.isPrereleaseOnly = false;
                            this.isIncompatibleVersion = false;
                        } else {
                            this.targetVersion = candidate;
                            this.isPrereleaseOnly = true;
                            this.isIncompatibleVersion = false;
                        }
                    } else {
                        // Incompatible version (e.g. huskHomes on different MC/loader or specific version pasted)
                        this.targetVersion = candidate;
                        this.isIncompatibleVersion = true;
                        this.isPrereleaseOnly = false;
                    }

                    logger.info("Candidate evaluated: isRelease={}, isPrereleaseOnly={}, isIncompatibleVersion={}",
                            isRelease, isPrereleaseOnly, isIncompatibleVersion);

                    showPreview();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Failed to resolve URL: " + input, ex);
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

        if (isIncompatibleVersion) {
            String verLoaders = targetVersion.getLoaders() != null ? String.join(", ", targetVersion.getLoaders()) : "-";
            String verGameVers = targetVersion.getGameVersions() != null ? String.join(", ", targetVersion.getGameVersions()) : "-";
            String instLoader = currentInstance != null ? currentInstance.getLoader() : "-";
            String instMc = currentInstance != null ? currentInstance.getMcVersion() : "-";

            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.incompat_version_warn", verGameVers, verLoaders, instLoader, instMc));
            prereleaseConfirmCheckBox.setText(I18n.get("url.incompat_confirm_check", targetVersion.getVersionNumber()));
            prereleaseConfirmCheckBox.setSelected(false);
            downloadBtn.setDisable(true);
        } else if (isPrereleaseOnly) {
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
        if ((isPrereleaseOnly || isIncompatibleVersion) && prereleaseConfirmCheckBox != null && !prereleaseConfirmCheckBox.isSelected()) {
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
