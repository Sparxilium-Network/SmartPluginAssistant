package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.HangarProject;
import com.sparxilium.smartpluginassistant.model.HangarVersion;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.HangarService;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HangarBrowserController {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HangarBrowserController.class);

    @FXML private Label titleLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> platformFilterCombo;
    @FXML private ComboBox<String> versionFilterCombo;
    @FXML private CheckBox allowPrereleaseCheckBox;
    @FXML private Button searchBtn;
    @FXML private ScrollPane resultsScrollPane;
    @FXML private VBox resultsContainer;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label statusLabel;

    @FXML private VBox detailContainer;
    @FXML private Label detailTitleLabel;
    @FXML private Label detailAuthorLabel;
    @FXML private Label detailDescLabel;
    @FXML private ComboBox<String> detailVersionCombo;
    @FXML private Label channelLabel;
    @FXML private Button detailInstallBtn;
    @FXML private Button openInBrowserBtn;

    @FXML private HBox bottomActionBar;
    @FXML private Label selectedQueueLabel;
    @FXML private Button reviewAndDownloadBtn;

    private ServerInstance currentInstance;
    private HangarService hangarService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private HangarProject selectedProject;
    private List<HangarVersion> selectedProjectVersions;
    private final List<HangarProject> currentLoadedProjects = new ArrayList<>();
    private final Map<String, HangarVersion> versionMap = new LinkedHashMap<>();

    // Shopping cart: author/slug -> CartItem
    public static class CartItem {
        public final HangarProject project;
        public final HangarVersion version;
        public CartItem(HangarProject project, HangarVersion version) {
            this.project = project;
            this.version = version;
        }
    }
    private final Map<String, CartItem> cartMap = new LinkedHashMap<>();

    public void init(ServerInstance instance, HangarService hangarService, InstanceManager instanceManager, Runnable onPluginInstalledCallback) {
        this.currentInstance = instance;
        this.hangarService = hangarService;
        this.instanceManager = instanceManager;
        this.onPluginInstalledCallback = onPluginInstalledCallback;
        applyI18n();
        setupPlatformFilter();
        setupVersionFilter();
        handleSearch();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("hangar.title"));
        searchBtn.setText(I18n.get("hangar.btn_search"));
        statusLabel.setText(I18n.get("hangar.ready"));
        if (allowPrereleaseCheckBox != null)
            allowPrereleaseCheckBox.setText(I18n.get("modrinth.allow_prerelease"));
        if (openInBrowserBtn != null) {
            openInBrowserBtn.setText(I18n.get("modrinth.btn_open_web"));
            Tooltip.install(openInBrowserBtn, new Tooltip(I18n.get("modrinth.btn_open_web")));
        }
    }

    private void setupPlatformFilter() {
        if (platformFilterCombo == null) return;
        String loader = currentInstance != null ? currentInstance.getLoader() : "paper";
        String platform = HangarService.toPlatformKey(loader);
        platformFilterCombo.getItems().setAll("PAPER", "WATERFALL", "VELOCITY");
        platformFilterCombo.setValue(platform);
    }

    private void setupVersionFilter() {
        if (versionFilterCombo == null) return;
        versionFilterCombo.getItems().setAll(
            I18n.get("modrinth.all"),
            "26.2", "26.1", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21",
            "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.17.1", "1.16.5", "1.12.2"
        );
        String mcVersion = currentInstance != null && currentInstance.getMcVersion() != null
                ? currentInstance.getMcVersion() : I18n.get("modrinth.all");
        if (versionFilterCombo.getItems().contains(mcVersion)) {
            versionFilterCombo.setValue(mcVersion);
        } else {
            versionFilterCombo.getSelectionModel().selectFirst();
        }
    }

    @FXML
    private void handleSearch() {
        String query = searchField != null ? searchField.getText().trim() : "";
        String platform = platformFilterCombo != null ? platformFilterCombo.getValue() : "PAPER";
        String version = versionFilterCombo != null ? versionFilterCombo.getValue() : null;
        if (I18n.get("modrinth.all").equals(version)) version = null;

        setLoading(true);
        statusLabel.setText(I18n.get("hangar.searching"));
        resultsContainer.getChildren().clear();
        currentLoadedProjects.clear();

        final String mcVer = version;
        hangarService.searchProjects(query, platform, mcVer, 0, 20)
                .thenAccept(page -> Platform.runLater(() -> {
                    setLoading(false);
                    currentLoadedProjects.addAll(page.projects());
                    if (page.projects().isEmpty()) {
                        statusLabel.setText(I18n.get("hangar.no_results"));
                    } else {
                        statusLabel.setText(I18n.get("hangar.found_results", page.totalCount()));
                        for (HangarProject project : page.projects()) {
                            resultsContainer.getChildren().add(buildProjectCard(project));
                        }
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        statusLabel.setText(I18n.get("hangar.search_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    private VBox buildProjectCard(HangarProject project) {
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        // Icon
        ImageView iconView = new ImageView();
        iconView.setFitWidth(40);
        iconView.setFitHeight(40);
        iconView.setPreserveRatio(true);
        if (project.getAvatarUrl() != null && !project.getAvatarUrl().isBlank()) {
            com.sparxilium.smartpluginassistant.service.ImageCacheService.loadImageAsync(
                    project.getAvatarUrl(), 40, 40, iconView::setImage);
        }

        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label nameLabel = new Label(project.getName());
        nameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label authorLabel = new Label("by " + project.getAuthor());
        authorLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px;");
        info.getChildren().addAll(nameLabel, authorLabel);

        Label dlLabel = new Label("⬇ " + project.getDownloads());
        dlLabel.setStyle("-fx-text-fill: #6e7073; -fx-font-size: 11px;");

        // Cart badge
        Label cartBadge = new Label();
        String nsKey = project.getNamespaceString();
        if (cartMap.containsKey(nsKey)) {
            cartBadge.setText("✓ " + I18n.get("modrinth.btn_selected"));
            cartBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 10; -fx-font-size: 11px;");
        }

        top.getChildren().addAll(iconView, info, dlLabel, cartBadge);

        Label desc = new Label(project.getDescription() != null ? project.getDescription() : "");
        desc.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().addAll(top, desc);

        card.setOnMouseClicked(e -> showProjectDetail(project));
        card.setOnMouseEntered(e -> card.setStyle("-fx-background-color: #383a3e; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));
        card.setOnMouseExited(e -> card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));

        return card;
    }

    private void showProjectDetail(HangarProject project) {
        this.selectedProject = project;
        this.selectedProjectVersions = null;
        versionMap.clear();

        detailContainer.setVisible(true);
        detailTitleLabel.setText(project.getName());
        detailAuthorLabel.setText("by " + project.getAuthor() + " • " + project.getCategory());
        detailDescLabel.setText(project.getDescription() != null ? project.getDescription() : "");
        detailInstallBtn.setText(I18n.get("hangar.btn_select_version"));
        detailInstallBtn.setDisable(true);
        if (channelLabel != null) channelLabel.setText("");
        detailVersionCombo.getItems().clear();
        detailVersionCombo.setPromptText(I18n.get("hangar.fetching_versions"));

        String platform = platformFilterCombo != null ? platformFilterCombo.getValue() : "PAPER";
        String mcVer = versionFilterCombo != null && !I18n.get("modrinth.all").equals(versionFilterCombo.getValue())
                ? versionFilterCombo.getValue() : null;

        hangarService.getVersions(project.getAuthor(), project.getSlug(), platform, mcVer, 0, 25)
                .thenAccept(page -> Platform.runLater(() -> {
                    boolean allowPre = allowPrereleaseCheckBox != null && allowPrereleaseCheckBox.isSelected();
                    List<HangarVersion> vers = new ArrayList<>();
                    for (HangarVersion v : page.versions()) {
                        if (!allowPre && v.isUnstable()) continue;
                        vers.add(v);
                    }
                    if (vers.isEmpty()) vers.addAll(page.versions()); // show all if filtered out completely

                    this.selectedProjectVersions = vers;
                    detailVersionCombo.getItems().clear();
                    versionMap.clear();

                    for (HangarVersion v : vers) {
                        String label = v.getVersionNumber() + (v.isUnstable() ? " (" + v.getVersionType() + ")" : "");
                        detailVersionCombo.getItems().add(label);
                        versionMap.put(label, v);
                    }

                    if (!vers.isEmpty()) {
                        detailVersionCombo.getSelectionModel().selectFirst();
                        updateChannelLabel();
                        detailInstallBtn.setDisable(false);
                    } else {
                        detailVersionCombo.setPromptText(I18n.get("hangar.no_versions"));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> detailVersionCombo.setPromptText(I18n.get("hangar.no_versions")));
                    return null;
                });

        detailVersionCombo.setOnAction(e -> updateChannelLabel());
    }

    private void updateChannelLabel() {
        if (channelLabel == null) return;
        String sel = detailVersionCombo.getValue();
        if (sel == null) { channelLabel.setText(""); return; }
        HangarVersion v = versionMap.get(sel);
        if (v == null || v.getChannel() == null) { channelLabel.setText(""); return; }
        HangarVersion.Channel ch = v.getChannel();
        String color = ch.getColor() != null ? ch.getColor() : "#4e5157";
        channelLabel.setText("Channel: " + ch.getName());
        channelLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-font-size: 11px;");
    }

    @FXML
    private void handleSelectVersionForDownload() {
        if (selectedProject == null) return;
        String sel = detailVersionCombo.getValue();
        if (sel == null) return;
        HangarVersion version = versionMap.get(sel);
        if (version == null) return;

        String key = selectedProject.getNamespaceString();
        if (cartMap.containsKey(key)) {
            cartMap.remove(key);
        } else {
            cartMap.put(key, new CartItem(selectedProject, version));
        }
        refreshCart();
        // Refresh card list
        rebuildResultCards();
    }

    @FXML
    private void handleOpenWebPage() {
        if (selectedProject == null) return;
        String ns = selectedProject.getNamespaceString();
        if (ns == null || ns.isBlank()) return;
        String url = "https://hangar.papermc.io/" + ns;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            } else {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to open Hangar web page in external browser: {}", url, ex);
        }
    }

    private void rebuildResultCards() {
        resultsContainer.getChildren().clear();
        for (HangarProject p : currentLoadedProjects) {
            resultsContainer.getChildren().add(buildProjectCard(p));
        }
    }

    private void refreshCart() {
        int size = cartMap.size();
        reviewAndDownloadBtn.setDisable(size == 0);
        reviewAndDownloadBtn.setText(I18n.get("hangar.btn_review_download", size));
        selectedQueueLabel.setText(size > 0 ? I18n.get("hangar.cart_selected", size) : "");
    }

    @FXML
    private void handleReviewAndDownload() {
        if (cartMap.isEmpty()) return;

        Stage stage = (Stage) reviewAndDownloadBtn.getScene().getWindow();
        setLoading(true);
        statusLabel.setText(I18n.get("hangar.downloading"));

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> successNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();

        for (CartItem item : cartMap.values()) {
            HangarVersion.PlatformDownload pd = item.version.getPaperDownload();
            if (pd == null || pd.downloadUrl == null) {
                failedNames.add(item.project.getName() + " (no download URL)");
                continue;
            }

            String platform = platformFilterCombo != null ? platformFilterCombo.getValue() : "PAPER";
            String fileName = pd.fileInfo != null ? pd.fileInfo.name : item.project.getSlug() + "-" + item.version.getVersionNumber() + ".jar";
            if (!item.version.isUnstable() == false && !fileName.endsWith(".jar")) fileName += ".jar";

            Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
            Path dest = pluginsDir.resolve(fileName);

            String finalFileName = fileName;
            var f = hangarService.downloadFile(pd.downloadUrl, dest, null)
                    .thenAccept(path -> {
                        String sha512 = PluginManagerService.calculateSha512(path.toFile());
                        PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                item.project.getNamespaceString(), // use namespace as projectId
                                String.valueOf(item.version.getId()),
                                item.version.getVersionNumber(),
                                finalFileName,
                                sha512
                        );
                        record.hostingPlatform = "hangar";
                        record.hangarNamespace = item.project.getNamespaceString();
                        PluginMetadataStore.saveRecord(instanceManager, currentInstance, record);
                        successNames.add(item.project.getName());
                    })
                    .exceptionally(ex -> {
                        failedNames.add(item.project.getName() + ": " + ex.getMessage());
                        return null;
                    });
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> Platform.runLater(() -> {
                    setLoading(false);
                    cartMap.clear();
                    refreshCart();
                    rebuildResultCards();
                    String msg = "✓ " + I18n.get("hangar.download_complete", successNames.size());
                    if (!failedNames.isEmpty()) msg += "\n✗ " + I18n.get("hangar.download_failed_count", failedNames.size());
                    statusLabel.setText(msg);
                    if (onPluginInstalledCallback != null) onPluginInstalledCallback.run();
                }));
    }

    private void setLoading(boolean loading) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisible(loading);
            loadingIndicator.setManaged(loading);
        }
        if (searchBtn != null) searchBtn.setDisable(loading);
    }

}
