package com.chiliasmstudio.smartpluginassistant.controller;

import com.chiliasmstudio.smartpluginassistant.model.ModrinthProject;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthSearchResponse;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthSearchResult;
import com.chiliasmstudio.smartpluginassistant.model.ModrinthVersion;
import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;
import com.chiliasmstudio.smartpluginassistant.service.InstanceManager;
import com.chiliasmstudio.smartpluginassistant.service.ModrinthService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;

public class ModrinthBrowserController {
    @FXML private TextField searchField;
    @FXML private ComboBox<String> loaderFilterCombo;
    @FXML private ComboBox<String> versionFilterCombo;
    @FXML private VBox resultsContainer;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label statusLabel;

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

        loaderFilterCombo.getItems().addAll("全部 (All)", "paper", "spigot", "purpur", "folia", "velocity", "bungeecord", "sponge");
        if (instance != null && instance.getLoader() != null) {
            loaderFilterCombo.setValue(instance.getLoader().toLowerCase());
        } else {
            loaderFilterCombo.setValue("paper");
        }

        versionFilterCombo.getItems().addAll("全部 (All)", "1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2");
        if (instance != null && instance.getMcVersion() != null) {
            versionFilterCombo.setValue(instance.getMcVersion());
        } else {
            versionFilterCombo.setValue("1.21.1");
        }

        // Trigger initial popular search
        performSearch();
    }

    @FXML
    private void handleSearch() {
        performSearch();
    }

    private void performSearch() {
        if (modrinthService == null) return;

        String query = searchField.getText().trim();
        String loader = loaderFilterCombo.getValue();
        if ("全部 (All)".equals(loader)) loader = null;

        String version = versionFilterCombo.getValue();
        if ("全部 (All)".equals(version)) version = null;

        loadingIndicator.setVisible(true);
        statusLabel.setText("搜尋中...");
        resultsContainer.getChildren().clear();

        modrinthService.searchPlugins(query, loader, version, 0, 20)
                .thenAccept(response -> Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    statusLabel.setText("找到 " + response.getTotalHits() + " 個插件結果");
                    renderResults(response.getHits());
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        loadingIndicator.setVisible(false);
                        statusLabel.setText("搜尋失敗: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void renderResults(List<ModrinthSearchResult> hits) {
        resultsContainer.getChildren().clear();
        if (hits == null || hits.isEmpty()) {
            Label emptyLabel = new Label("沒有找到符合條件的插件");
            emptyLabel.setStyle("-fx-text-fill: #8b8e96; -fx-padding: 20;");
            resultsContainer.getChildren().add(emptyLabel);
            return;
        }

        for (ModrinthSearchResult hit : hits) {
            resultsContainer.getChildren().add(createPluginCard(hit));
        }
    }

    private HBox createPluginCard(ModrinthSearchResult hit) {
        HBox card = new HBox(12);
        card.getStyleClass().add("instance-card");
        card.setAlignment(Pos.CENTER_LEFT);

        // Icon
        ImageView iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        if (hit.getIconUrl() != null && !hit.getIconUrl().isBlank()) {
            try {
                iconView.setImage(new Image(hit.getIconUrl(), 48, 48, true, true, true));
            } catch (Exception ignored) {}
        }

        // Info VBox
        VBox infoBox = new VBox(4);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        HBox titleBox = new HBox(8);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label(hit.getTitle());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #ffffff;");
        Label authorLabel = new Label("by " + hit.getAuthor());
        authorLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px;");
        titleBox.getChildren().addAll(titleLabel, authorLabel);

        Label descLabel = new Label(hit.getDescription());
        descLabel.setStyle("-fx-text-fill: #bcbec4; -fx-font-size: 12px;");
        descLabel.setWrapText(true);
        descLabel.setMaxHeight(38);

        HBox badgesBox = new HBox(6);
        Label dlBadge = new Label("⬇ " + formatDownloads(hit.getDownloads()));
        dlBadge.getStyleClass().add("badge-version");
        badgesBox.getChildren().add(dlBadge);

        if (hit.getCategories() != null) {
            for (int i = 0; i < Math.min(3, hit.getCategories().size()); i++) {
                Label catBadge = new Label(hit.getCategories().get(i));
                catBadge.getStyleClass().add("badge-loader");
                badgesBox.getChildren().add(catBadge);
            }
        }

        infoBox.getChildren().addAll(titleBox, descLabel, badgesBox);

        // Install Button
        Button installBtn = new Button("安裝");
        installBtn.getStyleClass().add("btn-primary");
        installBtn.setOnAction(e -> handleInstallPlugin(hit, installBtn));

        card.getChildren().addAll(iconView, infoBox, installBtn);
        return card;
    }

    private void handleInstallPlugin(ModrinthSearchResult hit, Button installBtn) {
        installBtn.setDisable(true);
        installBtn.setText("取得版本中...");

        String loader = currentInstance != null ? currentInstance.getLoader() : null;
        String mcVersion = currentInstance != null ? currentInstance.getMcVersion() : null;

        modrinthService.getProjectVersions(hit.getProjectId(), loader, mcVersion)
                .thenCompose(versions -> {
                    if (versions.isEmpty()) {
                        throw new RuntimeException("找不到適用於 " + loader + " " + mcVersion + " 的版本！");
                    }
                    ModrinthVersion targetVer = versions.get(0);
                    ModrinthVersion.ModrinthFile primaryFile = targetVer.getPrimaryFile();
                    if (primaryFile == null || primaryFile.getUrl() == null) {
                        throw new RuntimeException("找不到可供下載的檔案！");
                    }

                    Platform.runLater(() -> installBtn.setText("下載中..."));

                    Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
                    Path dest = pluginsDir.resolve(primaryFile.getFilename());
                    return modrinthService.downloadFile(primaryFile.getUrl(), dest, null);
                })
                .thenAccept(path -> Platform.runLater(() -> {
                    installBtn.setText("✓ 已安裝");
                    installBtn.setStyle("-fx-background-color: #2ecc71;");
                    if (onPluginInstalledCallback != null) {
                        onPluginInstalledCallback.run();
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        installBtn.setDisable(false);
                        installBtn.setText("安裝");
                        Alert alert = new Alert(Alert.AlertType.ERROR, "安裝失敗: " + ex.getMessage(), ButtonType.OK);
                        alert.showAndWait();
                    });
                    return null;
                });
    }

    private String formatDownloads(int downloads) {
        if (downloads < 1000) return String.valueOf(downloads);
        if (downloads < 1000000) return String.format("%.1fk", downloads / 1000.0);
        return String.format("%.1fM", downloads / 1000000.0);
    }
}
