package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserContext;
import com.sparxilium.smartpluginassistant.controller.module.PluginBrowserModule;
import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.model.VoxelProduct;
import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.ImageCacheService;
import com.sparxilium.smartpluginassistant.service.InstanceManager;
import com.sparxilium.smartpluginassistant.service.PluginMetadataStore;
import com.sparxilium.smartpluginassistant.service.VoxelService;
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

public class VoxelBrowserController implements PluginBrowserModule {
    private static final Logger logger = LoggerFactory.getLogger(VoxelBrowserController.class);

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
    @FXML private Label detailPlatformsLabel;
    @FXML private Label detailVersionsLabel;
    @FXML private Label detailDescLabel;
    @FXML private Button detailDownloadBtn;
    @FXML private Button openInBrowserBtn;

    private ServerInstance currentInstance;
    private VoxelService voxelService;
    private InstanceManager instanceManager;
    private Runnable onPluginInstalledCallback;

    private VoxelProduct selectedProduct;
    private final List<VoxelProduct> currentLoadedProducts = new ArrayList<>();

    @Override
    public String getProviderName() {
        return "Voxel.shop";
    }

    @Override
    public String getStyleClass() {
        return "voxel-btn";
    }

    @Override
    public String getIconString() {
        return "🛒";
    }

    @Override
    public Node getRootNode() {
        return rootPane;
    }

    @Override
    public void initializeModule(PluginBrowserContext context) {
        this.currentInstance = context.getCurrentInstance();
        this.voxelService = context.getVoxelService();
        this.instanceManager = context.getInstanceManager();
        this.onPluginInstalledCallback = context.getOnPluginInstalledCallback();

        applyI18n();
        setupPriceFilter();
        handleSearch();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("voxel.title"));
        searchBtn.setText(I18n.get("hangar.btn_search"));
        statusLabel.setText(I18n.get("app.status_ready"));
        searchField.setPromptText(I18n.get("voxel.search_prompt"));
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
        priceFilterCombo.valueProperty().addListener((obs, oldV, newV) -> filterAndDisplayProducts());
    }

    @FXML
    private void handleSearch() {
        String query = searchField != null && searchField.getText() != null ? searchField.getText().trim() : "";
        setLoading(true);
        statusLabel.setText(I18n.get("hangar.searching"));
        detailContainer.setVisible(false);

        voxelService.searchResources(query, 0, 40)
                .thenAccept(page -> Platform.runLater(() -> {
                    setLoading(false);
                    currentLoadedProducts.clear();
                    currentLoadedProducts.addAll(page.products());
                    filterAndDisplayProducts();
                    if (currentLoadedProducts.isEmpty()) {
                        statusLabel.setText(I18n.get("hangar.no_results"));
                    } else {
                        statusLabel.setText(I18n.get("hangar.found_results", currentLoadedProducts.size()));
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

    private void filterAndDisplayProducts() {
        resultsContainer.getChildren().clear();
        String filter = priceFilterCombo != null ? priceFilterCombo.getValue() : null;
        String freeStr = I18n.get("voxel.filter_free");
        String paidStr = I18n.get("voxel.filter_paid");

        for (VoxelProduct p : currentLoadedProducts) {
            if (freeStr.equals(filter) && !p.isFree()) continue;
            if (paidStr.equals(filter) && p.isFree()) continue;
            resultsContainer.getChildren().add(buildProductCard(p));
        }
    }

    private VBox buildProductCard(VoxelProduct product) {
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        ImageView iconView = new ImageView();
        iconView.setFitWidth(40);
        iconView.setFitHeight(40);
        iconView.setPreserveRatio(true);
        if (product.getThumbnailURL() != null && !product.getThumbnailURL().isBlank()) {
            ImageCacheService.loadImageAsync(product.getThumbnailURL(), 40, 40, iconView::setImage);
        }

        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label nameLabel = new Label(product.getTitle());
        nameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 14px;");
        String authorName = product.getOwner() != null && product.getOwner().getName() != null ? product.getOwner().getName() : "Unknown";
        Label authorLabel = new Label("by " + authorName);
        authorLabel.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 11px;");
        info.getChildren().addAll(nameLabel, authorLabel);

        Label priceBadge = new Label();
        if (product.isFree()) {
            priceBadge.setText(I18n.get("voxel.free"));
            priceBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");
        } else {
            priceBadge.setText("$" + product.getPrice());
            priceBadge.setStyle("-fx-background-color: #f39c12; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");
        }

        top.getChildren().addAll(iconView, info, priceBadge);

        Label desc = new Label(product.getSubtitle() != null ? product.getSubtitle() : "");
        desc.setStyle("-fx-text-fill: #8b8e96; -fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().addAll(top, desc);

        card.setOnMouseClicked(e -> showProductDetail(product));
        card.setOnMouseEntered(e -> card.setStyle("-fx-background-color: #383a3e; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));
        card.setOnMouseExited(e -> card.setStyle("-fx-background-color: #2b2d30; -fx-background-radius: 6; -fx-padding: 10; -fx-cursor: hand;"));

        return card;
    }

    private void showProductDetail(VoxelProduct product) {
        this.selectedProduct = product;
        detailContainer.setVisible(true);

        detailTitleLabel.setText(product.getTitle());
        String authorName = product.getOwner() != null && product.getOwner().getName() != null ? product.getOwner().getName() : "Unknown";
        detailAuthorLabel.setText("by " + authorName);

        if (product.isFree()) {
            detailPriceBadge.setText(I18n.get("voxel.free"));
            detailPriceBadge.setStyle("-fx-background-color: #1bd96a; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;");
            detailDownloadBtn.setText("⬇ " + I18n.get("voxel.btn_download_free"));
            detailDownloadBtn.setStyle("-fx-background-color: #3574f0; -fx-text-fill: white; -fx-font-weight: bold;");
        } else {
            detailPriceBadge.setText("$" + product.getPrice() + " " + (product.getCurrency() != null ? product.getCurrency() : "USD"));
            detailPriceBadge.setStyle("-fx-background-color: #f39c12; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4;");
            detailDownloadBtn.setText("🛒 " + I18n.get("voxel.btn_view_on_voxel"));
            detailDownloadBtn.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold;");
        }

        String sw = product.getSupportedServerSoftware() != null ? product.getSupportedServerSoftware() : "-";
        detailPlatformsLabel.setText(I18n.get("voxel.supported_loaders", sw));

        String mcVers = product.getSupportedMinecraftVersions() != null ? product.getSupportedMinecraftVersions() : "-";
        detailVersionsLabel.setText(I18n.get("voxel.supported_versions", mcVers));

        detailDescLabel.setText(product.getSubtitle() != null ? product.getSubtitle() : "");
    }

    @FXML
    private void handleDownloadOrBuy() {
        if (selectedProduct == null) return;
        if (!selectedProduct.isFree()) {
            handleOpenWebPage();
            return;
        }

        if (currentInstance == null) {
            statusLabel.setText(I18n.get("app.no_instance_selected"));
            return;
        }

        setLoading(true);
        statusLabel.setText(I18n.get("hangar.fetching_versions"));

        voxelService.getDownloadInfo(selectedProduct.getId())
                .thenAccept(dlInfo -> {
                    if (dlInfo == null || dlInfo.downloadUrl() == null) {
                        Platform.runLater(() -> {
                            setLoading(false);
                            statusLabel.setText(I18n.get("voxel.download_url_failed"));
                        });
                        return;
                    }

                    String sanitizedTitle = selectedProduct.getTitle().replaceAll("[^a-zA-Z0-9._-]", "-");
                    String ver = dlInfo.version() != null ? dlInfo.version() : "latest";
                    String fileName = sanitizedTitle + "-" + ver + ".jar";
                    Path pluginsDir = instanceManager.getPluginsDirectory(currentInstance);
                    Path targetPath = pluginsDir.resolve(fileName);

                    Platform.runLater(() -> statusLabel.setText(I18n.get("hangar.downloading")));

                    voxelService.downloadFile(dlInfo.downloadUrl(), targetPath)
                            .thenAccept(path -> {
                                String sha512 = "";
                                try {
                                    sha512 = com.sparxilium.smartpluginassistant.service.PluginManagerService.calculateSha512(path.toFile());
                                } catch (Exception ignored) {}

                                PluginMetadataStore.DownloadRecord record = new PluginMetadataStore.DownloadRecord(
                                        String.valueOf(selectedProduct.getId()),
                                        ver,
                                        ver,
                                        fileName,
                                        sha512
                                );
                                record.hostingPlatform = "voxel";
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
                                    logger.error("Download failed from Voxel", ex);
                                    statusLabel.setText(I18n.get("app.update_failed", ex.getMessage()));
                                });
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        logger.error("Failed to get download info from Voxel", ex);
                        statusLabel.setText(I18n.get("hangar.search_failed", ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleOpenWebPage() {
        if (selectedProduct == null || selectedProduct.getUrl() == null) return;
        openBrowserUrl(selectedProduct.getUrl());
    }

    @FXML
    private void handleOpenApiWebPage() {
        openBrowserUrl("https://voxel.shop/resources");
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
