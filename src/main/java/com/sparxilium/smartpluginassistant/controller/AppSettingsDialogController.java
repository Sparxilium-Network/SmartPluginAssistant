package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.service.I18n;
import com.sparxilium.smartpluginassistant.service.ImageCacheService;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class AppSettingsDialogController {
    @FXML private Label titleLabel;
    @FXML private Label langLabel;
    @FXML private ComboBox<String> langComboBox;
    @FXML private Label cacheSectionLabel;
    @FXML private Label cacheSizeLabel;
    @FXML private Button clearCacheBtn;
    @FXML private Button closeBtn;

    private Runnable onLanguageChangedCallback;

    public void init(Runnable onLanguageChangedCallback) {
        this.onLanguageChangedCallback = onLanguageChangedCallback;

        applyI18n();

        langComboBox.getItems().clear();
        langComboBox.getItems().addAll("繁體中文 (zh-tw)", "English (en)");

        if ("en".equals(I18n.getCurrentLang())) {
            langComboBox.setValue("English (en)");
        } else {
            langComboBox.setValue("繁體中文 (zh-tw)");
        }

        langComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                if (newVal.contains("en")) {
                    I18n.loadLanguage("en");
                } else {
                    I18n.loadLanguage("zh-tw");
                }
                applyI18n();
                if (this.onLanguageChangedCallback != null) {
                    this.onLanguageChangedCallback.run();
                }
            }
        });

        updateCacheSizeDisplay();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("app_settings.title"));
        langLabel.setText(I18n.get("app_settings.language"));
        cacheSectionLabel.setText(I18n.get("app_settings.cache_section"));
        clearCacheBtn.setText(I18n.get("app_settings.btn_clear_cache"));
        closeBtn.setText(I18n.get("app_settings.btn_close"));
        updateCacheSizeDisplay();
    }

    private void updateCacheSizeDisplay() {
        long bytes = ImageCacheService.getCacheSizeBytes();
        String formatted = formatBytes(bytes);
        cacheSizeLabel.setText(I18n.get("app_settings.cache_size", formatted));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = ("KMGTPE").charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @FXML
    private void handleClearCache() {
        ImageCacheService.clearCache();
        updateCacheSizeDisplay();
        Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("app_settings.cache_cleared"), ButtonType.OK);
        alert.showAndWait();
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) closeBtn.getScene().getWindow();
        stage.close();
    }
}
