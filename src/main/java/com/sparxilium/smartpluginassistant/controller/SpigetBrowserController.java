package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserContext;
import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserModule;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.model.spiget.SpigetResource;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.ImageCacheService;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.PluginManagerService;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import com.sparxilium.smartpluginassistant.service.SpigetService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SpigetBrowserController implements PluginBrowserModule {
    private static final Logger logger = LoggerFactory.getLogger(SpigetBrowserController.class);

    @FXML private VBox rootPane;
    @FXML private Label titleLabel;
    @FXML private Hyperlink apiByLink;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> priceFilterCombo;
    @FXML private Button searchBtn;
    @FXML private ScrollPane resultsScrollPane;
    @FXML private VBox resultsContainer;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label statusLabel;

    @FXML private VBox detailContainer;
    @FXML private Label detailTitleLabel;
    @FXML private Label detailAuthorLabel;
    @FXML private Label detailPriceBadge;
    @FXML private Label detailDownloadsLabel;
    @FXML private Label detailVersionsLabel;
    @FXML private Label detailDescLabel;
    @FXML private Button detailDownloadBtn;
    @FXML private Button openInBrowserBtn;

    private ServerInstance currentInstance;
    private SpigetService spigetService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private SpigetResource selectedResource;
    private final List<SpigetResource> currentLoadedResources = new ArrayList<>();

    @Override
    public String getProviderName() {
        return "SpigotMC [實驗性]";
    }

    @Override
    public String getStyleClass() {
        return "spiget-btn";
    }

    @Override
    public String getIconString() {
        return "🔶";
    }

    @Override
    public Node getRootNode() {
        return rootPane;
    }

    @Override
    public void initializeModule(PluginBrowserContext context) {
        this.currentInstance = context.getCurrentInstance();
        this.spigetService = context.getSpigetService();
        this.instanceManager = context.getInstanceManager();
        this.onPluginInstalledCallback = context.getOnPluginInstalledCallback();

        applyI18n();
        setupPriceFilter();
        handleSearch();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("spiget.title"));
        searchBtn.setText(I18n.get("hangar.btn_search"));
        statusLabel.setText(I18n.get("app.status_ready"));
        searchField.setPromptText(I18n.get("spiget.search_prompt"));
        if (openInBrowserBtn != null) {
            openInBrowserBtn.setText(I18n.get("modrinth.btn_open_web"));
        }
    }

    private void setupPriceFilter() {
        if (priceFilterCombo == null) return;
        priceFilterCombo.getItems().setAll(
                I18n.get("voxel.filter_all"),
                I18n.get("voxel.filter_free"),
                I18n.get("voxel.filter_paid")
        );
        priceFilterCombo.setValue(I18n.get("voxel.filter_all"));
        priceFilterCombo.valueProperty().addListener((obs, oldV, newV) -> filterAndDisplayResources());
    }

    @FXML
    private void handleSearch() {
        String query = searchField != null && searchField.getText() != null ? searchField.getText().trim() : "";
        setLoading(true);
        statusLabel.setText(I18n.get("hangar.searching"));
        detailContainer.setVisible(false);

        spigetService.searchResources(query, 1, 40)
                .thenAccept(list -> Platform.runLater(() -> {
                    setLoading(false);
                    currentLoadedResources.clear();
                    currentLoadedResources.addAll(list);
                    filterAndDisplayResources();
                    if (currentLoadedResources.isEmpty()) {
                        statusLabel.setText(I18n.get("hangar.no_results"));
                    } else {
                        statusLabel.setText(I18n.get("hangar.found_results", currentLoadedResources.size()));
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

    private void filterAndDisplayResources() {
        resultsContainer.getChildren().clear();
        String filter = priceFilterCombo != null ? priceFilterCombo.getValue() : null;
        String freeStr = I18n.get("voxel.filter_free");
        String paidStr = I18n.get("voxel.filter_paid");

        for (SpigetResource res : currentLoadedResources) {
            boolean isFree = !res.isPremium();
            if (freeStr.equals(filter) && !isFree) continue;
            if (paidStr.equals(filter) && isFree) continue;
            resultsContainer.getChildren().add(buildResourceCard(res));
        }
    }

    private VBox buildResourceCard(SpigetResource res) {
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        ImageView iconView = new ImageView();
        iconView.setFitWidth(40);
        iconView.setFitHeight(40);
        iconView.setPreserveRatio(true);
        if (res.getIconUrl() != null && !res.getIconUrl().isBlank()) {
            ImageCacheService.loadImageAsync(res.getIconUrl(), 40, 40, iconView::setImage);
        }

        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label nameLabel = new Label(res.getName());
        nameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label authorLabel = new Label(res.getAuthor() != null && res.getAuthor().getName() != null 
                ? "by " + res.getAuthor().getName() : "by Author #" + (res.getAuthor() != null ? res.getAuthor().getId() : ""));
        authorLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px;");
        info.getChildren().addAll(nameLabel, authorLabel);

        Label priceBadge = new Label();
        if (!res.isPremium()) {
            priceBadge.setText(I18n.get("voxel.free"));
            priceBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");
        } else {
            String pr = res.getPrice() != null ? String.format("%.2f", res.getPrice()) : "Premium";
            priceBadge.setText("$" + pr);
            priceBadge.setStyle("-fx-background-color: #f39c12; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");
        }

        top.getChildren().addAll(iconView, info, priceBadge);

        Label desc = new Label(res.getTag() != null ? res.getTag() : "");
        desc.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().addAll(top, desc);

        card.setOnMouseClicked(e -> showResourceDetail(res));
        card.setOnMouseEntered(e -> card.setStyle("-fx-background-color: #383a3e; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));
        card.setOnMouseExited(e -> card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));

        return card;
    }

    private void showResourceDetail(SpigetResource res) {
        this.selectedResource = res;
        detailContainer.setVisible(true);

        detailTitleLabel.setText(res.getName());
        detailAuthorLabel.setText(res.getAuthor() != null ? "Author ID: " + res.getAuthor().getId() : "");

        if (res.getAuthor() != null && res.getAuthor().getId() > 0) {
            spigetService.getAuthorName(res.getAuthor().getId()).thenAccept(authorName -> {
                Platform.runLater(() -> detailAuthorLabel.setText("by " + authorName));
            });
        }

        if (!res.isPremium()) {
            detailPriceBadge.setText(I18n.get("voxel.free"));
            detailPriceBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;");
            detailDownloadBtn.setText("⬇ " + I18n.get("voxel.btn_download_free"));
            detailDownloadBtn.setStyle("-fx-background-color: #3574f0; -fx-text-fill: white; -fx-font-weight: bold;");
        } else {
            String pr = res.getPrice() != null ? String.format("%.2f", res.getPrice()) : "Premium";
            detailPriceBadge.setText("$" + pr + " " + (res.getCurrency() != null ? res.getCurrency() : "USD"));
            detailPriceBadge.setStyle("-fx-background-color: #f39c12; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;");
            detailDownloadBtn.setText("🛒 " + I18n.get("spiget.btn_view_on_spigot"));
            detailDownloadBtn.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold;");
        }

        detailDownloadsLabel.setText("⬇ " + res.getDownloads() + " " + I18n.get("hangar.detail_downloads"));

        if (res.getTestedVersions() != null && !res.getTestedVersions().isEmpty()) {
            detailVersionsLabel.setText(I18n.get("voxel.supported_versions", String.join(", ", res.getTestedVersions())));
        } else {
            detailVersionsLabel.setText(I18n.get("voxel.supported_versions", "-"));
        }

        detailDescLabel.setText(res.getTag() != null ? res.getTag() : "");
    }

    @FXML
    private void handleDownloadOrBuy() {
        if (selectedResource == null) return;
        if (selectedResource.isPremium() || selectedResource.isExternal()) {
            handleOpenWebPage();
            return;
        }

        if (currentInstance == null) {
            statusLabel.setText(I18n.get("app.no_instance_selected"));
            return;
        }

        setLoading(true);
        statusLabel.setText(I18n.get("hangar.downloading"));

        String sanitizedTitle = selectedResource.getName().replaceAll("[^a-zA-Z0-9._-]", "-");
        String ver = selectedResource.getVersion() != null ? String.valueOf(selectedResource.getVersion().getId()) : "latest";
        String fileName = sanitizedTitle + "-spigot-" + ver + ".jar";
        Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
        Path targetPath = pluginsDir.resolve(fileName);

        spigetService.downloadUpdate(currentInstance, spigetService.getDownloadUrl(selectedResource.getId()), targetPath, null)
                .thenAccept(path -> {
                    String sha512 = "";
                    try {
                        sha512 = PluginManagerService.calculateSha512(path.toFile());
                    } catch (Exception ignored) {}

                    PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                            String.valueOf(selectedResource.getId()),
                            ver,
                            ver,
                            fileName,
                            sha512
                    );
                    record.hostingPlatform = "spiget";
                    PluginMetadataStore.saveRecord(instanceManager, currentInstance, record);

                    Platform.runLater(() -> {
                        setLoading(false);
                        statusLabel.setText("✓ " + I18n.get("hangar.download_complete", 1));
                        if (onPluginInstalledCallback != null) onPluginInstalledCallback.run();
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        logger.error("Download failed from Spiget", ex);
                        statusLabel.setText(I18n.get("app.update_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleOpenWebPage() {
        if (selectedResource == null) return;
        openBrowserUrl(selectedResource.getSpigotUrl());
    }

    @FXML
    private void handleOpenApiWebPage() {
        openBrowserUrl("https://spiget.org");
    }

    private void openBrowserUrl(String url) {
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
            logger.warn("Failed to open web page in external browser: {}", url, ex);
        }
    }

    private void setLoading(boolean loading) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisible(loading);
            loadingIndicator.setManaged(loading);
        }
        if (searchBtn != null) searchBtn.setDisable(loading);
    }
}
