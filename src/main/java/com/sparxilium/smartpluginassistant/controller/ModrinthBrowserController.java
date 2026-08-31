package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ModrinthProject;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResponse;
import com.sparxilium.smartpluginassistant.model.ModrinthSearchResult;
import com.sparxilium.smartpluginassistant.model.ModrinthVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.ModrinthService;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

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
    @FXML private CheckBox allowPrereleaseCheckBox;
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

    // Bottom Action Bar
    @FXML private HBox bottomActionBar;
    @FXML private Label selectedQueueLabel;
    @FXML private Button reviewAndDownloadBtn;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ModrinthBrowserController.class);

    private ServerInstance currentInstance;
    private ModrinthService modrinthService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private ModrinthSearchResult selectedResult;
    private List<ModrinthVersion> selectedProjectVersions;
    // Track loaded hits in memory
    private final List<ModrinthSearchResult> currentLoadedHits = new ArrayList<>();
    private final Map<String, ModrinthVersion> versionMap = new HashMap<>();

    // Shopping cart of selected items for batch download (projectId -> SelectedCartItem)
    public static class SelectedCartItem {
        public final ModrinthSearchResult project;
        public final ModrinthVersion version;

        public SelectedCartItem(ModrinthSearchResult project, ModrinthVersion version) {
            this.project = project;
            this.version = version;
        }
    }
    private final Map<String, SelectedCartItem> selectedCart = new HashMap<>();

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

        if (allowPrereleaseCheckBox != null) {
            allowPrereleaseCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (selectedResult != null) {
                    showDetails(selectedResult);
                }
            });
        }

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
        if (allowPrereleaseCheckBox != null) {
            allowPrereleaseCheckBox.setText(I18n.get("modrinth.allow_prerelease"));
        }
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
            currentLoadedHits.clear();
        }
        if (hits != null) {
            currentLoadedHits.addAll(hits);
        }
        if (currentLoadedHits.isEmpty()) {
            Label emptyLabel = new Label(I18n.get("modrinth.no_results"));
            emptyLabel.setStyle("-fx-text-fill: #8b8e96; -fx-padding: 20;");
            resultsContainer.getChildren().add(emptyLabel);
            return;
        }

        if (!append) {
            for (ModrinthSearchResult hit : currentLoadedHits) {
                resultsContainer.getChildren().add(createPluginCard(hit));
            }
        } else if (hits != null) {
            for (ModrinthSearchResult hit : hits) {
                resultsContainer.getChildren().add(createPluginCard(hit));
            }
        }
    }

    private void rerenderResults() {
        resultsContainer.getChildren().clear();
        for (ModrinthSearchResult hit : currentLoadedHits) {
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

        // Check if in shopping cart
        if (selectedCart.containsKey(hit.getProjectId())) {
            SelectedCartItem item = selectedCart.get(hit.getProjectId());
            Label cartBadge = new Label(I18n.get("modrinth.btn_selected") + " (" + item.version.getVersionNumber() + ")");
            cartBadge.setStyle("-fx-background-color: #3574f0; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 4; -fx-font-size: 10px;");
            titleBox.getChildren().add(cartBadge);
            card.setStyle("-fx-border-color: #3574f0; -fx-border-width: 1px; -fx-border-radius: 6;");
        } else {
            // Check if installed in current instance
            String installedVer = getInstalledVersionFor(hit);
            if (installedVer != null) {
                Label installedBadge = new Label(I18n.get("modrinth.installed_badge", installedVer));
                installedBadge.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 4; -fx-font-size: 10px;");
                titleBox.getChildren().add(installedBadge);
            }
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
                    boolean allowPrerelease = allowPrereleaseCheckBox != null && allowPrereleaseCheckBox.isSelected();
                    List<ModrinthVersion> filteredVersions = versions;
                    if (!allowPrerelease) {
                        filteredVersions = versions.stream()
                                .filter(v -> "release".equalsIgnoreCase(v.getVersionType()))
                                .toList();
                    }

                    this.selectedProjectVersions = filteredVersions;
                    if (filteredVersions.isEmpty()) {
                        detailInstallBtn.setText(I18n.get("modrinth.no_versions", loaders, (mcVersion == null ? "Any" : mcVersion)));
                        return;
                    }

                    for (ModrinthVersion version : filteredVersions) {
                        String displayStr = version.getVersionNumber() + " [" + version.getVersionType() + "]";
                        detailVersionCombo.getItems().add(displayStr);
                        versionMap.put(displayStr, version);
                    }

                    // Select the first version by default or the currently queued version
                    if (selectedCart.containsKey(hit.getProjectId())) {
                        SelectedCartItem cartItem = selectedCart.get(hit.getProjectId());
                        for (Map.Entry<String, ModrinthVersion> entry : versionMap.entrySet()) {
                            if (entry.getValue().getId().equals(cartItem.version.getId())) {
                                detailVersionCombo.getSelectionModel().select(entry.getKey());
                                break;
                            }
                        }
                    } else {
                        detailVersionCombo.getSelectionModel().select(0);
                    }

                    detailInstallBtn.setDisable(false);
                    updateDetailButtonState();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        logger.error("Failed to fetch versions for " + hit.getProjectId(), ex);
                        detailInstallBtn.setText(I18n.get("modrinth.install_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private void updateDetailButtonState() {
        if (selectedResult == null) return;
        if (selectedCart.containsKey(selectedResult.getProjectId())) {
            detailInstallBtn.setText(I18n.get("modrinth.btn_cancel_selection"));
            detailInstallBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;");
        } else {
            detailInstallBtn.setText(I18n.get("modrinth.btn_select"));
            detailInstallBtn.setStyle("-fx-background-color: #3574f0; -fx-text-fill: white; -fx-font-weight: bold;");
        }
    }

    public static class DownloadItem {
        private final String projectId;
        private final String versionId;
        private final String title;
        private final String versionNumber;
        private final String fileName;
        private final String url;
        private final String depType; // null for primary, "required", "optional"
        private boolean selected = true;

        public DownloadItem(String projectId, String versionId, String title, String versionNumber, String fileName, String url, String depType) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.title = title;
            this.versionNumber = versionNumber;
            this.fileName = fileName;
            this.url = url;
            this.depType = depType;
        }

        public String getProjectId() { return projectId; }
        public String getVersionId() { return versionId; }
        public String getTitle() { return title; }
        public String getVersionNumber() { return versionNumber; }
        public String getFileName() { return fileName; }
        public String getUrl() { return url; }
        public String getDepType() { return depType; }
        public boolean isSelected() { return selected; }
        public void setSelected(boolean selected) { this.selected = selected; }
    }

    @FXML
    private void handleSelectVersionForDownload() {
        if (selectedResult == null) return;
        String projectId = selectedResult.getProjectId();

        if (selectedCart.containsKey(projectId)) {
            // Already selected, deselect
            selectedCart.remove(projectId);
        } else {
            String selectedVersionStr = detailVersionCombo.getValue();
            if (selectedVersionStr == null || !versionMap.containsKey(selectedVersionStr)) return;
            ModrinthVersion targetVer = versionMap.get(selectedVersionStr);
            selectedCart.put(projectId, new SelectedCartItem(selectedResult, targetVer));
        }

        updateDetailButtonState();
        updateBottomActionBar();
        rerenderResults();
    }

    private void updateBottomActionBar() {
        int count = selectedCart.size();
        reviewAndDownloadBtn.setText(I18n.get("modrinth.btn_review_and_download", count));
        reviewAndDownloadBtn.setDisable(count == 0);
        if (count > 0) {
            selectedQueueLabel.setText(I18n.get("modrinth.selected_version_tag") + " " + count + " 個項目");
        } else {
            selectedQueueLabel.setText("");
        }
    }

    @FXML
    private void handleReviewAndDownload() {
        if (selectedCart.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("modrinth.no_plugins_selected"), ButtonType.OK);
            alert.showAndWait();
            return;
        }

        reviewAndDownloadBtn.setDisable(true);
        reviewAndDownloadBtn.setText(I18n.get("modrinth.resolving_deps"));

        List<DownloadItem> downloadList = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<Void>> depFutures = new ArrayList<>();

        for (SelectedCartItem cartItem : selectedCart.values()) {
            ModrinthVersion targetVer = cartItem.version;
            ModrinthVersion.ModrinthFile primaryFile = targetVer.getPrimaryFile();
            if (primaryFile == null || primaryFile.getUrl() == null) continue;

            downloadList.add(new DownloadItem(
                    cartItem.project.getProjectId(),
                    targetVer.getId(),
                    cartItem.project.getTitle() != null ? cartItem.project.getTitle() : targetVer.getName(),
                    targetVer.getVersionNumber(),
                    primaryFile.getFilename(),
                    primaryFile.getUrl(),
                    null
            ));

            List<ModrinthVersion.ModrinthDependency> deps = targetVer.getDependencies();
            if (deps != null && !deps.isEmpty()) {
                for (ModrinthVersion.ModrinthDependency dep : deps) {
                    if ("incompatible".equalsIgnoreCase(dep.getDependencyType()) || "embedded".equalsIgnoreCase(dep.getDependencyType())) {
                        continue;
                    }
                    if (dep.getVersionId() != null) {
                        depFutures.add(modrinthService.getVersion(dep.getVersionId()).thenAccept(depVer -> {
                            ModrinthVersion.ModrinthFile depFile = depVer.getPrimaryFile();
                            if (depFile != null && depFile.getUrl() != null) {
                                synchronized (downloadList) {
                                    boolean exists = downloadList.stream().anyMatch(d -> d.getFileName().equalsIgnoreCase(depFile.getFilename()));
                                    if (!exists) {
                                        downloadList.add(new DownloadItem(
                                                depVer.getProjectId(),
                                                depVer.getId(),
                                                depVer.getName() != null ? depVer.getName() : depFile.getFilename(),
                                                depVer.getVersionNumber(),
                                                depFile.getFilename(),
                                                depFile.getUrl(),
                                                dep.getDependencyType()
                                        ));
                                    }
                                }
                            }
                        }).exceptionally(ex -> {
                            logger.debug("Failed to resolve dependency version {}: {}", dep.getVersionId(), ex.getMessage());
                            return null;
                        }));
                    } else if (dep.getProjectId() != null) {
                        List<String> loaders = currentInstance != null ? currentInstance.getEffectiveLoaders() : Collections.emptyList();
                        String mcVersion = (ignoreVersionCheckBox.isSelected()) ? null : (currentInstance != null ? currentInstance.getMcVersion() : null);
                        depFutures.add(modrinthService.getProjectVersions(dep.getProjectId(), loaders, mcVersion).thenAccept(pVers -> {
                            if (!pVers.isEmpty()) {
                                ModrinthVersion depVer = pVers.get(0);
                                ModrinthVersion.ModrinthFile depFile = depVer.getPrimaryFile();
                                if (depFile != null && depFile.getUrl() != null) {
                                    synchronized (downloadList) {
                                        boolean exists = downloadList.stream().anyMatch(d -> d.getFileName().equalsIgnoreCase(depFile.getFilename()));
                                        if (!exists) {
                                            downloadList.add(new DownloadItem(
                                                    depVer.getProjectId(),
                                                    depVer.getId(),
                                                    depVer.getName() != null ? depVer.getName() : depFile.getFilename(),
                                                    depVer.getVersionNumber(),
                                                    depFile.getFilename(),
                                                    depFile.getUrl(),
                                                    dep.getDependencyType()
                                            ));
                                        }
                                    }
                                }
                            }
                        }).exceptionally(ex -> {
                            logger.debug("Failed to resolve dependency project {}: {}", dep.getProjectId(), ex.getMessage());
                            return null;
                        }));
                    }
                }
            }
        }

        if (!depFutures.isEmpty()) {
            java.util.concurrent.CompletableFuture.allOf(depFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .whenComplete((v, t) -> Platform.runLater(() -> promptConfirmationAndDownload(downloadList)));
        } else {
            promptConfirmationAndDownload(downloadList);
        }
    }

    private void promptConfirmationAndDownload(List<DownloadItem> items) {
        updateBottomActionBar();

        Stage confirmStage = new Stage();
        confirmStage.setTitle(I18n.get("modrinth.confirm_title"));
        confirmStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        VBox rootBox = new VBox(14);
        rootBox.setStyle("-fx-background-color: #1e1f22; -fx-padding: 16;");

        Label headerLabel = new Label(I18n.get("modrinth.confirm_header"));
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #18191c; -fx-background-color: #18191c; -fx-background-radius: 6; -fx-border-color: #393b40; -fx-border-radius: 6;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox listBox = new VBox(8);
        listBox.setStyle("-fx-background-color: #18191c; -fx-padding: 12;");

        for (DownloadItem item : items) {
            CheckBox cb = new CheckBox();
            cb.setSelected(true);
            cb.setOnAction(e -> item.setSelected(cb.isSelected()));

            HBox itemRow = new HBox(8);
            itemRow.setAlignment(Pos.CENTER_LEFT);

            Label titleLbl = new Label(item.getTitle());
            titleLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff; -fx-font-size: 13px;");

            Label verLbl = new Label("(" + item.getVersionNumber() + ")");
            verLbl.setStyle("-fx-text-fill: #9a9da3; -fx-font-size: 12px;");

            itemRow.getChildren().addAll(cb, titleLbl, verLbl);

            if (item.getDepType() != null) {
                Label depBadge = new Label("required".equalsIgnoreCase(item.getDepType()) ? I18n.get("modrinth.dep_required") : I18n.get("modrinth.dep_optional"));
                depBadge.setStyle("required".equalsIgnoreCase(item.getDepType())
                        ? "-fx-text-fill: #e67e22; -fx-font-size: 11px; -fx-font-weight: bold;"
                        : "-fx-text-fill: #3498db; -fx-font-size: 11px;");
                itemRow.getChildren().add(depBadge);
            }

            listBox.getChildren().add(itemRow);
        }
        scrollPane.setContent(listBox);

        Label footerLabel = new Label(I18n.get("modrinth.confirm_footer"));
        footerLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #8b8e96;");

        HBox btnBox = new HBox(12);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        Button okBtn = new Button(I18n.get("common.ok"));
        okBtn.getStyleClass().add("btn-success");
        okBtn.setStyle("-fx-font-weight: bold; -fx-padding: 6 16;");

        Button cancelBtn = new Button(I18n.get("common.cancel"));
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setStyle("-fx-padding: 6 16;");

        btnBox.getChildren().addAll(okBtn, cancelBtn);

        rootBox.getChildren().addAll(headerLabel, scrollPane, footerLabel, btnBox);

        Scene scene = new Scene(rootBox, 500, 380);
        scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
        confirmStage.setScene(scene);
        confirmStage.setMinWidth(420);
        confirmStage.setMinHeight(300);

        final boolean[] confirmed = {false};
        okBtn.setOnAction(e -> {
            confirmed[0] = true;
            confirmStage.close();
        });
        cancelBtn.setOnAction(e -> confirmStage.close());

        com.sparxilium.smartpluginassistant.util.WindowsTitleBarTheme.applyDarkTitleBar(confirmStage);
        confirmStage.showAndWait();

        if (confirmed[0]) {
            executeDownload(items.stream().filter(DownloadItem::isSelected).toList());
        }
    }

    private void executeDownload(List<DownloadItem> toDownload) {
        if (toDownload.isEmpty()) return;

        reviewAndDownloadBtn.setDisable(true);
        reviewAndDownloadBtn.setText(I18n.get("modrinth.btn_installing"));

        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);

        List<java.util.concurrent.CompletableFuture<Path>> futures = new ArrayList<>();
        for (DownloadItem item : toDownload) {
            Path dest = pluginsDir.resolve(item.getFileName());
            futures.add(modrinthService.downloadFile(item.getUrl(), dest, null).thenApply(downloadedPath -> {
                String sha1 = PluginManagerService.calculateSha1(downloadedPath.toFile());
                PluginMetadataStore.saveRecord(instanceManager, currentInstance,
                        new PluginMetadataStore.DownloadRecord(
                                item.getProjectId(),
                                item.getVersionId(),
                                item.getVersionNumber(),
                                item.getFileName(),
                                sha1
                        ));
                return downloadedPath;
            }));
        }

        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenAccept(v -> Platform.runLater(() -> {
                    selectedCart.clear();
                    updateBottomActionBar();
                    updateDetailButtonState();
                    refreshInstalledMap();
                    rerenderResults();
                    if (onPluginInstalledCallback != null) {
                        onPluginInstalledCallback.run();
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        updateBottomActionBar();
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
