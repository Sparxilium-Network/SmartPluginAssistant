package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ModrinthProject;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResponse;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResult;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModrinthBrowserController {
    @FXML private Label titleLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> loaderFilterCombo;
    @FXML private ComboBox<String> versionFilterCombo;
    @FXML private CheckBox ignoreVersionCheckBox;
    @FXML private Button searchBtn;
    @FXML private VBox resultsContainer;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label statusLabel;

    // Right Detail View
    @FXML private VBox detailContainer;
    @FXML private Label detailTitleLabel;
    @FXML private Label detailAuthorLabel;
    @FXML private Label detailDescLabel;
    @FXML private ComboBox<String> detailVersionCombo;
    @FXML private Button detailInstallBtn;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ModrinthBrowserController.class);

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private ModrinthSearchResult selectedResult;
    private List<ModrinthVersion> selectedProjectVersions;
    private final Map<String, ModrinthVersion> versionMap = new HashMap<>();

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

        applyI18n();

        loaderFilterCombo.getItems().addAll(I18n.get("modrinth.all"), "folia", "purpur", "paper", "spigot", "velocity", "bungeecord", "fabric", "sponge");
        if (instance != null && instance.getLoader() != null) {
            loaderFilterCombo.setValue(instance.getLoader().toLowerCase());
        } else {
            loaderFilterCombo.setValue("paper");
        }

        ignoreVersionCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            versionFilterCombo.setDisable(newVal);
            performSearch();
        });

        versionFilterCombo.getItems().clear();
        versionFilterCombo.getItems().add(I18n.get("modrinth.all"));
        modrinthService.fetchGameVersions()
                .thenAccept(versions -> Platform.runLater(() -> {
                    versionFilterCombo.getItems().addAll(versions);
                    if (instance != null && instance.getMcVersion() != null) {
                        versionFilterCombo.setValue(instance.getMcVersion());
                    } else {
                        versionFilterCombo.setValue(I18n.get("modrinth.all"));
                    }
                    performSearch();
                }));
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("modrinth.title"));
        searchField.setPromptText(I18n.get("modrinth.search_prompt"));
        ignoreVersionCheckBox.setText(I18n.get("modrinth.ignore_version"));
        searchBtn.setText(I18n.get("modrinth.btn_search"));
        statusLabel.setText(I18n.get("app.status_ready"));
    }

    @FXML
    private void handleSearch() {
        performSearch();
    }

    private void performSearch() {
        if (modrinthService == null) return;

        String query = searchField.getText().trim();
        String selectedLoader = loaderFilterCombo.getValue();
        List<String> loadersToSearch;

        String allText = I18n.get("modrinth.all");
        if (allText.equals(selectedLoader) || "全部 (All)".equals(selectedLoader) || "All".equals(selectedLoader)) {
            loadersToSearch = Collections.emptyList();
        } else if (currentInstance != null && currentInstance.getLoader().equalsIgnoreCase(selectedLoader)) {
            loadersToSearch = currentInstance.getEffectiveLoaders();
        } else {
            loadersToSearch = List.of(selectedLoader);
        }

        String version = versionFilterCombo.getValue();
        if (ignoreVersionCheckBox.isSelected() || allText.equals(version) || "全部 (All)".equals(version) || "All".equals(version)) {
            version = null;
        }

        logger.debug("Performing Modrinth search: query='{}', loaders={}, version={}", query, loadersToSearch, version);

        loadingIndicator.setVisible(true);
        statusLabel.setText(I18n.get("modrinth.searching"));
        resultsContainer.getChildren().clear();
        detailContainer.setVisible(false);

        modrinthService.searchPlugins(query, loadersToSearch, version, 0, 20)
                .thenAccept(response -> Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    statusLabel.setText(I18n.get("modrinth.found_results", response.getTotalHits()));
                    renderResults(response.getHits());
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Modrinth search failed", ex);
                        loadingIndicator.setVisible(false);
                        statusLabel.setText(I18n.get("modrinth.search_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private void renderResults(List<ModrinthSearchResult> hits) {
        resultsContainer.getChildren().clear();
        if (hits == null || hits.isEmpty()) {
            Label emptyLabel = new Label(I18n.get("modrinth.no_results"));
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

        ImageView iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        if (hit.getIconUrl() != null && !hit.getIconUrl().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    hit.getIconUrl(), 48, 48, iconView::setImage);
        }

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

        // Clicking the card opens details on the right side
        card.setOnMouseClicked(e -> showDetails(hit));

        card.getChildren().addAll(iconView, infoBox);
        return card;
    }

    private void showDetails(ModrinthSearchResult hit) {
        this.selectedResult = hit;
        detailContainer.setVisible(true);
        detailTitleLabel.setText(hit.getTitle());
        detailAuthorLabel.setText("by " + hit.getAuthor());
        detailDescLabel.setText(hit.getDescription());

        detailVersionCombo.getItems().clear();
        versionMap.clear();
        detailInstallBtn.setDisable(true);
        detailInstallBtn.setText(I18n.get("modrinth.fetching_versions"));

        List<String> loaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : Collections.emptyList();
        String mcVersion = (ignoreVersionCheckBox.isSelected()) ? null : (currentInstance != null ? currentInstance.getMcVersion() : null);

        logger.debug("Fetching versions for project '{}' with loaders={} and mcVersion={}", hit.getProjectId(), loaders, mcVersion);

        modrinthService.getProjectVersions(hit.getProjectId(), loaders, mcVersion)
                .thenAccept(versions -> Platform.runLater(() -> {
                    this.selectedProjectVersions = versions;
                    if (versions.isEmpty()) {
                        detailInstallBtn.setText(I18n.get("modrinth.no_versions", loaders, (mcVersion == null ? "Any" : mcVersion)));
                        return;
                    }

                    for (ModrinthVersion version : versions) {
                        String displayStr = version.getVersionNumber() + " [" + version.getVersionType() + "]";
                        detailVersionCombo.getItems().add(displayStr);
                        versionMap.put(displayStr, version);
                    }

                    // Select the first version by default
                    detailVersionCombo.getSelectionModel().select(0);
                    detailInstallBtn.setDisable(false);
                    detailInstallBtn.setText(I18n.get("modrinth.btn_install"));
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Failed to fetch versions for " + hit.getProjectId(), ex);
                        detailInstallBtn.setText(I18n.get("modrinth.install_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleInstallSelectedVersion() {
        String selectedVersionStr = detailVersionCombo.getValue();
        if (selectedVersionStr == null || !versionMap.containsKey(selectedVersionStr)) return;

        ModrinthVersion targetVer = versionMap.get(selectedVersionStr);
        ModrinthVersion.ModrinthFile primaryFile = targetVer.getPrimaryFile();
        if (primaryFile == null || primaryFile.getUrl() == null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("modrinth.no_files"), ButtonType.OK);
            alert.showAndWait();
            return;
        }

        detailInstallBtn.setDisable(true);
        detailInstallBtn.setText(I18n.get("modrinth.btn_installing"));

        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path dest = pluginsDir.resolve(primaryFile.getFilename());

        modrinthService.downloadFile(primaryFile.getUrl(), dest, null)
                .thenAccept(path -> Platform.runLater(() -> {
                    detailInstallBtn.setText(I18n.get("modrinth.btn_installed"));
                    detailInstallBtn.setStyle("-fx-background-color: #2ecc71;");
                    if (onPluginInstalledCallback != null) {
                        onPluginInstalledCallback.run();
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        detailInstallBtn.setDisable(false);
                        detailInstallBtn.setText(I18n.get("modrinth.btn_install"));
                        Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("modrinth.install_failed", ex.getMessage()), ButtonType.OK);
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
