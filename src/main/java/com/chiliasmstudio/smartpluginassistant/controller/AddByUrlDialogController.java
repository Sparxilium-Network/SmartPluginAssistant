package com.chiliasmstudio.smartpluginassistant.controller;

import com.chiliasmstudio.smartpluginassistant.model.ModrinthProject;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthVersion;
import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;
import com.chiliasmstudio.smartpluginassistant.service.InstanceManager;
import com.chiliasmstudio.smartpluginassistant.service.ModrinthService;
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
    @FXML private TextField urlField;
    @FXML private Button resolveBtn;
    @FXML private VBox previewContainer;
    @FXML private ImageView iconView;
    @FXML private Label titleLabel;
    @FXML private Label authorLabel;
    @FXML private Label descLabel;
    @FXML private Label targetVersionLabel;
    @FXML private Button downloadBtn;
    @FXML private ProgressIndicator progressIndicator;

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private ModrinthProject resolvedProject;
    private ModrinthVersion targetVersion;

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;
    }

    @FXML
    private void handleResolve() {
        String input = urlField.getText().trim();
        String slug = ModrinthService.extractSlugOrId(input);
        if (slug == null || slug.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "請輸入有效的 Modrinth 網址或 Slug！");
            return;
        }

        progressIndicator.setVisible(true);
        previewContainer.setVisible(false);

        modrinthService.getProject(slug)
                .thenCompose(project -> {
                    this.resolvedProject = project;
                    String loader = currentInstance != null ? currentInstance.getLoader() : null;
                    String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;
                    return modrinthService.getProjectVersions(project.getId(), loader, mcVersion);
                })
                .thenAccept(versions -> Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    if (versions.isEmpty()) {
                        showAlert(Alert.AlertType.WARNING, "找到插件「" + resolvedProject.getTitle() + "」，但沒有適用於 " +
                                currentInstance.getLoader() + " " + currentInstance.getMcVersion() + " 的發布版本！");
                        return;
                    }
                    this.targetVersion = versions.get(0);
                    showPreview();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        progressIndicator.setVisible(false);
                        showAlert(Alert.AlertType.ERROR, "解析失敗: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void showPreview() {
        previewContainer.setVisible(true);
        titleLabel.setText(resolvedProject.getTitle());
        authorLabel.setText("專案 ID / Slug: " + resolvedProject.getSlug());
        descLabel.setText(resolvedProject.getDescription());

        ModrinthVersion.ModrinthFile file = targetVersion.getPrimaryFile();
        String fileName = file != null ? file.getFilename() : "未知檔案";
        targetVersionLabel.setText("相容版本: " + targetVersion.getVersionNumber() + " (" + fileName + ")");

        if (resolvedProject.getIconUrl() != null && !resolvedProject.getIconUrl().isBlank()) {
            try {
                iconView.setImage(new Image(resolvedProject.getIconUrl(), 48, 48, true, true, true));
            } catch (Exception ignored) {}
        }
    }

    @FXML
    private void handleDownload() {
        if (targetVersion == null || targetVersion.getPrimaryFile() == null) return;

        downloadBtn.setDisable(true);
        downloadBtn.setText("下載中...");

        ModrinthVersion.ModrinthFile primaryFile = targetVersion.getPrimaryFile();
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path dest = pluginsDir.resolve(primaryFile.getFilename());

        modrinthService.downloadFile(primaryFile.getUrl(), dest, null)
                .thenAccept(path -> Platform.runLater(() -> {
                    downloadBtn.setText("✓ 下載完成");
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
                        downloadBtn.setText("下載並安裝");
                        showAlert(Alert.AlertType.ERROR, "下載失敗: " + ex.getMessage());
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
