package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.HangarProject;
import com.sparxilium.smartpluginassistant.model.HangarVersion;
import com.sparxilium.smartpluginassistant.model.ModrinthProject;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.HangarService;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
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
    @FXML private Button downloadBtn;
    @FXML private Button closeBtn;
    @FXML private ProgressIndicator progressIndicator;

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private HangarService hangarService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    // Platform mode: "modrinth" or "hangar"
    private String resolvedPlatform = "modrinth";

    // Modrinth state
    private ModrinthProject resolvedModrinthProject;
    private ModrinthVersion targetModrinthVersion;

    // Hangar state
    private HangarProject resolvedHangarProject;
    private HangarVersion targetHangarVersion;

    private boolean isPrereleaseOnly = false;
    private boolean isIncompatibleVersion = false;

    public void init(ServerInstance instance, ModrinthService modrinthService, HangarService hangarService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.hangarService = hangarService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

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
        if (input.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, I18n.get("url.err_invalid_url"));
            return;
        }

        progressIndicator.setVisible(true);
        progressIndicator.setManaged(true);
        previewContainer.setVisible(false);
        previewContainer.setManaged(false);
        isPrereleaseOnly = false;
        isIncompatibleVersion = false;

        if (HangarService.isHangarUrl(input)) {
            resolveHangar(input);
        } else {
            resolveModrinth(input);
        }
    }

    private void resolveHangar(String input) {
        logger.info("Resolving Hangar URL input: '{}'", input);
        HangarService.ResolvedUrlInfo urlInfo = HangarService.parseHangarUrl(input);
        if (urlInfo == null || urlInfo.author == null || urlInfo.slug == null) {
            logger.warn("Failed to parse Hangar author/slug from URL: '{}'", input);
            progressIndicator.setVisible(false);
            progressIndicator.setManaged(false);
            showAlert(Alert.AlertType.WARNING, I18n.get("url.err_invalid_url"));
            return;
        }

        this.resolvedPlatform = "hangar";
        String platform = HangarService.toPlatformKey(currentInstance != null ? currentInstance.getLoader() : "paper");
        String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;

        hangarService.getProject(urlInfo.author, urlInfo.slug)
                .thenCompose(project -> {
                    this.resolvedHangarProject = project;
                    logger.info("Fetched Hangar project: name='{}', namespace='{}'", project.getName(), project.getNamespaceString());

                    return hangarService.getVersions(urlInfo.author, urlInfo.slug, platform, null, 0, 25)
                            .thenApply(page -> {
                                List<HangarVersion> versions = page.versions();
                                if (urlInfo.versionName != null && !urlInfo.versionName.isBlank()) {
                                    for (HangarVersion v : versions) {
                                        if (urlInfo.versionName.equalsIgnoreCase(v.getVersionNumber())) {
                                            return List.of(v);
                                        }
                                    }
                                }
                                return versions;
                            });
                })
                .thenAccept(versions -> Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    progressIndicator.setManaged(false);
                    if (versions.isEmpty()) {
                        logger.warn("No Hangar versions returned for project '{}'", resolvedHangarProject.getName());
                        showAlert(Alert.AlertType.WARNING, I18n.get("url.no_compat_version"));
                        return;
                    }

                    HangarVersion candidate = versions.get(0);
                    this.targetHangarVersion = candidate;

                    List<String> gameVersions = candidate.getPaperVersions();
                    boolean matchesGameVer = mcVersion == null || gameVersions.isEmpty() || gameVersions.contains(mcVersion);
                    boolean isRelease = !candidate.isUnstable();

                    if (matchesGameVer) {
                        this.isIncompatibleVersion = false;
                        this.isPrereleaseOnly = !isRelease;
                    } else {
                        this.isIncompatibleVersion = true;
                        this.isPrereleaseOnly = false;
                    }

                    showHangarPreview();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Failed to resolve Hangar URL: " + input, ex);
                        progressIndicator.setVisible(false);
                        progressIndicator.setManaged(false);
                        showAlert(Alert.AlertType.ERROR, I18n.get("url.err_resolve_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private void showHangarPreview() {
        previewContainer.setVisible(true);
        previewContainer.setManaged(true);
        projectTitleLabel.setText(resolvedHangarProject.getName());
        authorLabel.setText("by " + resolvedHangarProject.getAuthor() + " • Hangar");
        descLabel.setText(resolvedHangarProject.getDescription());

        HangarVersion.PlatformDownload pd = targetHangarVersion.getPaperDownload();
        String fileName = pd != null && pd.fileInfo != null ? pd.fileInfo.name : resolvedHangarProject.getSlug() + ".jar";
        String verType = targetHangarVersion.getVersionType().toUpperCase();
        targetVersionLabel.setText(I18n.get("url.compat_version", targetHangarVersion.getVersionNumber() + " [" + verType + "]", fileName));

        if (isIncompatibleVersion) {
            List<String> gameVersions = targetHangarVersion.getPaperVersions();
            String verGameVers = !gameVersions.isEmpty() ? String.join(", ", gameVersions) : "-";
            String instLoader = currentInstance != null ? currentInstance.getLoader() : "-";
            String instMc = currentInstance != null ? currentInstance.getMcVersion() : "-";

            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.incompat_version_warn", verGameVers, "Paper", instLoader, instMc));
        } else if (isPrereleaseOnly) {
            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.prerelease_only_warn", verType));
        } else {
            prereleaseWarningBox.setVisible(false);
            prereleaseWarningBox.setManaged(false);
        }

        downloadBtn.setDisable(false);

        if (resolvedHangarProject.getAvatarUrl() != null && !resolvedHangarProject.getAvatarUrl().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    resolvedHangarProject.getAvatarUrl(), 48, 48, iconView::setImage);
        }
    }

    private void resolveModrinth(String input) {
        logger.info("Resolving Modrinth URL input: '{}'", input);
        ModrinthService.ResolvedUrlInfo urlInfo = ModrinthService.parseUrlInfo(input);
        if (urlInfo == null || urlInfo.projectSlug == null || urlInfo.projectSlug.isEmpty()) {
            logger.warn("Failed to parse project slug from URL: '{}'", input);
            progressIndicator.setVisible(false);
            progressIndicator.setManaged(false);
            showAlert(Alert.AlertType.WARNING, I18n.get("url.err_invalid_url"));
            return;
        }

        this.resolvedPlatform = "modrinth";
        logger.info("Parsed URL info: slug='{}', specificVersionId='{}'", urlInfo.projectSlug, urlInfo.specificVersionId);

        modrinthService.getProject(urlInfo.projectSlug)
                .thenCompose(project -> {
                    this.resolvedModrinthProject = project;
                    logger.info("Fetched project: id='{}', title='{}', slug='{}'", project.getId(), project.getTitle(), project.getSlug());
                    List<String> loaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : Collections.emptyList();
                    String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;

                    if (urlInfo.specificVersionId != null && !urlInfo.specificVersionId.isBlank()) {
                        logger.info("Resolving specific version '{}' for project '{}' with preferredLoaders={}...", urlInfo.specificVersionId, project.getId(), loaders);
                        return modrinthService.resolveVersionByProjectAndVersion(project.getId(), urlInfo.specificVersionId, loaders)
                                .thenApply(ver -> {
                                    if (ver != null) {
                                        logger.info("Successfully resolved specific version: number='{}', id='{}', loaders={}", ver.getVersionNumber(), ver.getId(), ver.getLoaders());
                                        return List.of(ver);
                                    } else {
                                        logger.warn("Could not find specific version '{}', resolving all versions as fallback", urlInfo.specificVersionId);
                                        return Collections.<ModrinthVersion>emptyList();
                                    }
                                });
                    } else {
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
                    progressIndicator.setManaged(false);
                    if (versions.isEmpty()) {
                        logger.warn("No versions returned at all for project '{}'", resolvedModrinthProject.getId());
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
                        this.targetModrinthVersion = candidate;
                        this.isPrereleaseOnly = !isRelease;
                        this.isIncompatibleVersion = false;
                    } else {
                        this.targetModrinthVersion = candidate;
                        this.isIncompatibleVersion = true;
                        this.isPrereleaseOnly = false;
                    }

                    logger.info("Candidate evaluated: isRelease={}, isPrereleaseOnly={}, isIncompatibleVersion={}",
                            isRelease, isPrereleaseOnly, isIncompatibleVersion);

                    showModrinthPreview();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Failed to resolve URL: " + input, ex);
                        progressIndicator.setVisible(false);
                        progressIndicator.setManaged(false);
                        showAlert(Alert.AlertType.ERROR, I18n.get("url.err_resolve_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private void showModrinthPreview() {
        previewContainer.setVisible(true);
        previewContainer.setManaged(true);
        projectTitleLabel.setText(resolvedModrinthProject.getTitle());
        authorLabel.setText(I18n.get("url.project_id", resolvedModrinthProject.getSlug()) + " • Modrinth");
        descLabel.setText(resolvedModrinthProject.getDescription());

        ModrinthVersion.ModrinthFile file = targetModrinthVersion.getPrimaryFile();
        String fileName = file != null ? file.getFilename() : "Unknown";
        String verType = targetModrinthVersion.getVersionType() != null ? targetModrinthVersion.getVersionType().toUpperCase() : "RELEASE";
        targetVersionLabel.setText(I18n.get("url.compat_version", targetModrinthVersion.getVersionNumber() + " [" + verType + "]", fileName));

        if (isIncompatibleVersion) {
            String verLoaders = targetModrinthVersion.getLoaders() != null ? String.join(", ", targetModrinthVersion.getLoaders()) : "-";
            String verGameVers = targetModrinthVersion.getGameVersions() != null ? String.join(", ", targetModrinthVersion.getGameVersions()) : "-";
            String instLoader = currentInstance != null ? currentInstance.getLoader() : "-";
            String instMc = currentInstance != null ? currentInstance.getMcVersion() : "-";

            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.incompat_version_warn", verGameVers, verLoaders, instLoader, instMc));
        } else if (isPrereleaseOnly) {
            prereleaseWarningBox.setVisible(true);
            prereleaseWarningBox.setManaged(true);
            prereleaseWarningLabel.setText(I18n.get("url.prerelease_only_warn", verType));
        } else {
            prereleaseWarningBox.setVisible(false);
            prereleaseWarningBox.setManaged(false);
        }

        downloadBtn.setDisable(false);

        if (resolvedModrinthProject.getIconUrl() != null && !resolvedModrinthProject.getIconUrl().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    resolvedModrinthProject.getIconUrl(), 48, 48, iconView::setImage);
        }
    }

    @FXML
    private void handleDownload() {
        if ("hangar".equals(resolvedPlatform)) {
            downloadHangarPlugin();
        } else {
            downloadModrinthPlugin();
        }
    }

    private void downloadHangarPlugin() {
        if (targetHangarVersion == null) return;
        HangarVersion.PlatformDownload pd = targetHangarVersion.getPaperDownload();
        if (pd == null || pd.downloadUrl == null) return;

        downloadBtn.setDisable(true);
        downloadBtn.setText(I18n.get("modrinth.btn_installing"));

        String fileName = pd.fileInfo != null ? pd.fileInfo.name : resolvedHangarProject.getSlug() + "-" + targetHangarVersion.getVersionNumber() + ".jar";
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path dest = pluginsDir.resolve(fileName);

        hangarService.downloadFile(pd.downloadUrl, dest, null)
                .thenAccept(path -> Platform.runLater(() -> {
                    try {
                        String sha512 = PluginManagerService.calculateSha512(path.toFile());
                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                resolvedHangarProject.getNamespaceString(),
                                String.valueOf(targetHangarVersion.getId()),
                                targetHangarVersion.getVersionNumber(),
                                fileName,
                                sha512
                        );
                        record.hostingPlatform = "hangar";
                        record.hangarNamespace = resolvedHangarProject.getNamespaceString();
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

    private void downloadModrinthPlugin() {
        if (targetModrinthVersion == null || targetModrinthVersion.getPrimaryFile() == null) return;

        downloadBtn.setDisable(true);
        downloadBtn.setText(I18n.get("modrinth.btn_installing"));

        ModrinthVersion.ModrinthFile primaryFile = targetModrinthVersion.getPrimaryFile();
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path dest = pluginsDir.resolve(primaryFile.getFilename());

        modrinthService.downloadFile(primaryFile.getUrl(), dest, null)
                .thenAccept(path -> Platform.runLater(() -> {
                    try {
                        String sha512 = primaryFile.getHashes() != null ? primaryFile.getHashes().get("sha512") : null;
                        if (sha512 == null) {
                            sha512 = PluginManagerService.calculateSha512(path.toFile());
                        }
                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                resolvedModrinthProject.getId(),
                                targetModrinthVersion.getId(),
                                targetModrinthVersion.getVersionNumber(),
                                primaryFile.getFilename(),
                                sha512
                        );
                        record.hostingPlatform = "modrinth";
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