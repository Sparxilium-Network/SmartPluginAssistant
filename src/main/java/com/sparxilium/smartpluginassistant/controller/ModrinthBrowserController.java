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
import java.util.ArrayList;
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
    @FXML private CheckBox ignoreCompatCheckBox;
    @FXML private javafx.scene.layout.HBox compatFilterBox;
    @FXML private Label compatTitleLabel;
    @FXML private javafx.scene.layout.HBox compatCheckboxesContainer;
    @FXML private Button searchBtn;
    @FXML private ScrollPane resultsScrollPane;
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

    // Map of projectId/title/hash -> installed version number / filename
    private final Map<String, String> installedPluginsMap = new HashMap<>();
    private final Map<String, CheckBox> customCompatCheckBoxMap = new HashMap<>();

    // Infinite scroll pagination state
    private static final int PAGE_SIZE = 20;
    private int currentOffset = 0;
    private int totalHits = 0;
    private boolean isLoadingMore = false;
    private boolean hasMore = true;

    public void init(ServerInstance instance, ModrinthService modrinthService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.modrinthService = modrinthService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;

        applyI18n();
        refreshInstalledMap();

        loaderFilterCombo.getItems().addAll(I18n.get("modrinth.all"), "folia", "purpur", "paper", "spigot", "velocity", "bungeecord", "fabric", "sponge");
        if (instance != null && instance.getLoader() != null) {
            loaderFilterCombo.setValue(instance.getLoader().toLowerCase());
        } else {
            loaderFilterCombo.setValue("paper");
        }

        setupCompatibilityCheckboxes();

        loaderFilterCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            setupCompatibilityCheckboxes();
            performSearch();
        });

        ignoreVersionCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            versionFilterCombo.setDisable(newVal);
            performSearch();
        });

        ignoreCompatCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            compatFilterBox.setVisible(newVal);
            compatFilterBox.setManaged(newVal);
            performSearch();
        });

        // Setup Infinite Scrolling on resultsScrollPane
        if (resultsScrollPane != null) {
            resultsScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
                // When scrolled past 75% of the list, automatically trigger loading next page
                if (newVal.doubleValue() >= 0.75 && !isLoadingMore && hasMore) {
                    loadNextPage();
                }
            });
        }

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

    private void refreshInstalledMap() {
        installedPluginsMap.clear();
        if (currentInstance == null) return;
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        if (!java.nio.file.Files.exists(pluginsDir)) return;
        try (var stream = java.nio.file.Files.list(pluginsDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jar") || n.endsWith(".jar.disabled");
            }).forEach(p -> {
                String fileName = p.getFileName().toString();
                String cleanName = fileName.replace(".jar.disabled", "").replace(".jar", "").toLowerCase();
                // Map full clean filename as well as simplified name
                installedPluginsMap.put(cleanName, fileName);
                // Also parse standard name-version formats (e.g. EssentialsX-2.20.1 -> essentialsx => 2.20.1)
                int dashIdx = cleanName.lastIndexOf('-');
                if (dashIdx > 0 && dashIdx < cleanName.length() - 1) {
                    String baseName = cleanName.substring(0, dashIdx);
                    String ver = cleanName.substring(dashIdx + 1);
                    installedPluginsMap.put(baseName, ver);
                }
            });
        } catch (Exception ignored) {}
    }

    private String getInstalledVersionFor(ModrinthSearchResult hit) {
        if (hit == null) return null;
        if (hit.getProjectId() != null && installedPluginsMap.containsKey(hit.getProjectId().toLowerCase())) {
            return installedPluginsMap.get(hit.getProjectId().toLowerCase());
        }
        if (hit.getSlug() != null && installedPluginsMap.containsKey(hit.getSlug().toLowerCase())) {
            return installedPluginsMap.get(hit.getSlug().toLowerCase());
        }
        if (hit.getTitle() != null) {
            String titleKey = hit.getTitle().toLowerCase().replaceAll("[^a-z0-9]", "");
            for (Map.Entry<String, String> entry : installedPluginsMap.entrySet()) {
                String installedKey = entry.getKey().replaceAll("[^a-z0-9]", "");
                if (installedKey.equals(titleKey) || installedKey.startsWith(titleKey)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private void setupCompatibilityCheckboxes() {
        if (compatCheckboxesContainer == null) return;
        compatCheckboxesContainer.getChildren().clear();
        customCompatCheckBoxMap.clear();

        String selectedLoader = loaderFilterCombo.getValue();
        if (selectedLoader == null || selectedLoader.equalsIgnoreCase("all") || selectedLoader.contains("全部")) {
            Label allLabel = new Label(I18n.get("modrinth.all_loaders_selected"));
            allLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px; -fx-font-style: italic;");
            compatCheckboxesContainer.getChildren().add(allLabel);
            return;
        }

        List<String> available = ServerInstance.getAvailableCompatibleLoadersFor(selectedLoader);
        if (available.isEmpty()) {
            // If current loader has no standard downward compatible loader (e.g. spigot/sponge/fabric), provide other common server loaders to choose
            List<String> allOtherLoaders = List.of("folia", "purpur", "paper", "spigot", "bukkit", "velocity", "bungeecord", "fabric", "sponge");
            available = allOtherLoaders.stream().filter(l -> !l.equalsIgnoreCase(selectedLoader)).toList();
        }

        for (String loaderOption : available) {
            CheckBox cb = new CheckBox(loaderOption.toUpperCase());
            cb.setStyle("-fx-text-fill: #dfe1e5; -fx-font-weight: bold; -fx-font-size: 11px;");
            cb.setSelected(true); // Default checked
            cb.selectedProperty().addListener((obs, oldV, newV) -> performSearch());
            customCompatCheckBoxMap.put(loaderOption.toLowerCase(), cb);
            compatCheckboxesContainer.getChildren().add(cb);
        }
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("modrinth.title"));
        searchField.setPromptText(I18n.get("modrinth.search_prompt"));
        ignoreVersionCheckBox.setText(I18n.get("modrinth.ignore_version"));
        ignoreCompatCheckBox.setText(I18n.get("modrinth.ignore_compat"));
        if (compatTitleLabel != null) {
            compatTitleLabel.setText(I18n.get("modrinth.include_loaders"));
        }
        searchBtn.setText(I18n.get("modrinth.btn_search"));
        statusLabel.setText(I18n.get("app.status_ready"));
    }

    @FXML
    private void handleSearch() {
        performSearch();
    }

    private void performSearch() {
        if (modrinthService == null) return;

        currentOffset = 0;
        hasMore = true;
        isLoadingMore = false;

        String query = searchField.getText().trim();
        List<String> loadersToSearch = getSelectedLoaders();
        String version = getSelectedVersion();

        logger.debug("Performing Modrinth search: query='{}', loaders={}, version={}", query, loadersToSearch, version);

        loadingIndicator.setVisible(true);
        statusLabel.setText(I18n.get("modrinth.searching"));
        resultsContainer.getChildren().clear();
        detailContainer.setVisible(false);

        modrinthService.searchPlugins(query, loadersToSearch, version, 0, PAGE_SIZE)
                .thenAccept(response -> Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    this.totalHits = response.getTotalHits();
                    this.currentOffset = response.getHits().size();
                    this.hasMore = currentOffset < totalHits;
                    statusLabel.setText(I18n.get("modrinth.found_results", totalHits));
                    renderResults(response.getHits(), false);
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

    private void loadNextPage() {
        if (modrinthService == null || isLoadingMore || !hasMore) return;

        isLoadingMore = true;
        loadingIndicator.setVisible(true);

        String query = searchField.getText().trim();
        List<String> loadersToSearch = getSelectedLoaders();
        String version = getSelectedVersion();

        logger.debug("Loading next page offset={} query='{}'", currentOffset, query);

        modrinthService.searchPlugins(query, loadersToSearch, version, currentOffset, PAGE_SIZE)
                .thenAccept(response -> Platform.runLater(() -> {
                    isLoadingMore = false;
                    loadingIndicator.setVisible(false);
                    List<ModrinthSearchResult> hits = response.getHits();
                    if (hits != null && !hits.isEmpty()) {
                        this.currentOffset += hits.size();
                        this.hasMore = currentOffset < totalHits;
                        renderResults(hits, true);
                    } else {
                        this.hasMore = false;
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        isLoadingMore = false;
                        loadingIndicator.setVisible(false);
                        logger.warn("Failed to fetch next page", ex);
                    });
                    return null;
                });
    }

    private List<String> getSelectedLoaders() {
        String selectedLoader = loaderFilterCombo.getValue();
        String allText = I18n.get("modrinth.all");
        if (allText.equals(selectedLoader) || "全部 (All)".equals(selectedLoader) || "All".equals(selectedLoader) || selectedLoader == null) {
            return Collections.emptyList();
        }

        List<String> loaders = new ArrayList<>();
        loaders.add(selectedLoader.toLowerCase());

        if (ignoreCompatCheckBox != null && ignoreCompatCheckBox.isSelected()) {
            for (Map.Entry<String, CheckBox> entry : customCompatCheckBoxMap.entrySet()) {
                if (entry.getValue().isSelected() && !loaders.contains(entry.getKey())) {
                    loaders.add(entry.getKey());
                }
            }
        }
        return loaders;
    }

    private String getSelectedVersion() {
        String version = versionFilterCombo.getValue();
        String allText = I18n.get("modrinth.all");
        if (ignoreVersionCheckBox.isSelected() || allText.equals(version) || "全部 (All)".equals(version) || "All".equals(version) || version == null) {
            return null;
        }
        return version;
    }

    private void renderResults(List<ModrinthSearchResult> hits, boolean append) {
        if (!append) {
            resultsContainer.getChildren().clear();
        }
        if (hits == null || hits.isEmpty()) {
            if (!append) {
                Label emptyLabel = new Label(I18n.get("modrinth.no_results"));
                emptyLabel.setStyle("-fx-text-fill: #8b8e96; -fx-padding: 20;");
                resultsContainer.getChildren().add(emptyLabel);
            }
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

        // Check if installed in current instance
        String installedVer = getInstalledVersionFor(hit);
        if (installedVer != null) {
            Label installedBadge = new Label(I18n.get("modrinth.installed_badge", installedVer));
            installedBadge.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 4; -fx-font-size: 10px;");
            titleBox.getChildren().add(installedBadge);
        }

        // Top right architecture incompatibility badge when searching with extra compatibility
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleBox.getChildren().add(spacer);

        boolean isCompatSearchActive = (ignoreCompatCheckBox != null && ignoreCompatCheckBox.isSelected())
                || (currentInstance != null && !currentInstance.getExtraCompatibleLoaders().isEmpty());

        if (isCompatSearchActive) {
            String primaryLoader = loaderFilterCombo.getValue();
            if (primaryLoader == null || primaryLoader.equalsIgnoreCase("all") || primaryLoader.contains("全部")) {
                primaryLoader = currentInstance != null ? currentInstance.getLoader().toLowerCase() : "paper";
            } else {
                primaryLoader = primaryLoader.toLowerCase();
            }

            List<String> categories = hit.getCategories() != null ? hit.getCategories() : Collections.emptyList();
            List<String> supportedLoaders = categories.stream()
                    .map(String::toLowerCase)
                    .filter(c -> List.of("folia", "paper", "spigot", "bukkit", "purpur", "velocity", "bungeecord", "fabric", "sponge").contains(c))
                    .toList();

            boolean supportsPrimary = supportedLoaders.contains(primaryLoader);
            if (!supportsPrimary && !supportedLoaders.isEmpty()) {
                Label archBadge = new Label("⚠️ " + String.join(", ", supportedLoaders));
                archBadge.setStyle("-fx-background-color: #d35400; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 4; -fx-font-size: 10px;");
                Tooltip.install(archBadge, new Tooltip("目前伺服器核心為 " + primaryLoader + "，此插件僅標註支援: " + String.join(", ", supportedLoaders)));
                titleBox.getChildren().add(archBadge);
            }
        }

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
                    refreshInstalledMap();
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
