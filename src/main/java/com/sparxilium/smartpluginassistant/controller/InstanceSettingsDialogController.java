package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InstanceSettingsDialogController {
    @FXML private Label titleLabel;
    @FXML private Label instanceNameLabel;
    @FXML private Label loaderLabel;
    @FXML private ComboBox<String> loaderComboBox;
    @FXML private Label versionLabel;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private Label compatSectionLabel;
    @FXML private VBox checkBoxesContainer;
    @FXML private Label warningLabel;
    @FXML private CheckBox allowPrereleaseCheckBox;
    @FXML private Label allowPrereleaseHintLabel;
    @FXML private CheckBox allowHigherMcVersionsCheckBox;
    @FXML private Label allowHigherMcVersionsHintLabel;

    @FXML private Label apiTokensSectionLabel;
    @FXML private Label apiTokensHintLabel;
    @FXML private Label tokenModrinthLabel;
    @FXML private PasswordField tokenModrinthField;
    @FXML private Label tokenHangarLabel;
    @FXML private PasswordField tokenHangarField;
    @FXML private Label tokenVoxelLabel;
    @FXML private PasswordField tokenVoxelField;
    @FXML private Label tokenSpigetLabel;
    @FXML private PasswordField tokenSpigetField;

    @FXML private Button cancelBtn;
    @FXML private Button saveBtn;

    private ServerInstance instance;
    private boolean saved = false;
    private final Map<String, CheckBox> loaderCheckBoxMap = new HashMap<>();

    public void init(ServerInstance instance) {
        this.instance = instance;

        applyI18n();

        instanceNameLabel.setText(instance.getName() + " (" + instance.getId() + ")");

        loaderComboBox.getItems().addAll("folia", "purpur", "paper", "spigot", "velocity", "bungeecord", "fabric", "sponge");
        loaderComboBox.setValue(instance.getLoader());

        allowPrereleaseCheckBox.setSelected(instance.isAllowPrereleases());
        if (allowHigherMcVersionsCheckBox != null) {
            allowHigherMcVersionsCheckBox.setSelected(instance.isAllowHigherMcVersions());
        }

        if (tokenModrinthField != null) tokenModrinthField.setText(instance.getApiToken("modrinth"));
        if (tokenHangarField != null) tokenHangarField.setText(instance.getApiToken("hangar"));
        if (tokenVoxelField != null) tokenVoxelField.setText(instance.getApiToken("voxel"));
        if (tokenSpigetField != null) tokenSpigetField.setText(instance.getApiToken("spiget"));

        new com.sparxilium.smartpluginassistant.service.ModrinthService(new com.sparxilium.smartpluginassistant.service.HttpDownloadService(java.net.http.HttpClient.newHttpClient())).fetchGameVersions()
                .thenAccept(versions -> javafx.application.Platform.runLater(() -> {
                    versionComboBox.getItems().setAll(versions);
                    versionComboBox.setValue(instance.getMcVersion());
                }));

        loaderComboBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshCompatibilityOptions(newVal));

        refreshCompatibilityOptions(instance.getLoader());
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("settings.title"));
        loaderLabel.setText(I18n.get("settings.loader"));
        versionLabel.setText(I18n.get("settings.version"));
        compatSectionLabel.setText(I18n.get("settings.compat_section"));
        warningLabel.setText(I18n.get("settings.warning"));
        allowPrereleaseCheckBox.setText(I18n.get("settings.allow_prerelease"));
        allowPrereleaseHintLabel.setText(I18n.get("settings.allow_prerelease_hint"));
        if (allowHigherMcVersionsCheckBox != null) {
            allowHigherMcVersionsCheckBox.setText(I18n.get("settings.allow_higher_mc_versions"));
        }
        if (allowHigherMcVersionsHintLabel != null) {
            allowHigherMcVersionsHintLabel.setText(I18n.get("settings.allow_higher_mc_versions_hint"));
        }
        if (apiTokensSectionLabel != null) apiTokensSectionLabel.setText(I18n.get("settings.api_tokens_section"));
        if (apiTokensHintLabel != null) apiTokensHintLabel.setText(I18n.get("settings.api_tokens_hint"));
        if (tokenModrinthLabel != null) tokenModrinthLabel.setText(I18n.get("settings.token_modrinth"));
        if (tokenHangarLabel != null) tokenHangarLabel.setText(I18n.get("settings.token_hangar"));
        if (tokenVoxelLabel != null) tokenVoxelLabel.setText(I18n.get("settings.token_voxel"));
        if (tokenSpigetLabel != null) tokenSpigetLabel.setText(I18n.get("settings.token_spiget"));
        cancelBtn.setText(I18n.get("settings.btn_cancel"));
        saveBtn.setText(I18n.get("settings.btn_save"));
    }

    private void refreshCompatibilityOptions(String currentLoader) {
        checkBoxesContainer.getChildren().clear();
        loaderCheckBoxMap.clear();

        List<String> available = ServerInstance.getAvailableCompatibleLoadersFor(currentLoader);

        if (available.isEmpty()) {
            Label noCompatLabel = new Label(I18n.get("settings.no_compat_available"));
            noCompatLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-style: italic; -fx-font-size: 12px;");
            checkBoxesContainer.getChildren().add(noCompatLabel);
            warningLabel.setVisible(false);
            warningLabel.setManaged(false);
            return;
        }

        List<String> currentSelected = instance != null ? instance.getExtraCompatibleLoaders() : List.of();

        for (String loaderOption : available) {
            CheckBox cb = new CheckBox(I18n.get("settings.compat_item", loaderOption.toUpperCase()));
            cb.setStyle("-fx-text-fill: #dfe1e5; -fx-font-weight: bold;");
            if (currentSelected.contains(loaderOption.toLowerCase())) {
                cb.setSelected(true);
            }

            cb.selectedProperty().addListener((obs, oldV, newV) -> updateWarning());
            loaderCheckBoxMap.put(loaderOption.toLowerCase(), cb);
            checkBoxesContainer.getChildren().add(cb);
        }

        updateWarning();
    }

    private void updateWarning() {
        boolean anySelected = loaderCheckBoxMap.values().stream().anyMatch(CheckBox::isSelected);
        warningLabel.setVisible(anySelected);
        warningLabel.setManaged(anySelected);
    }

    @FXML
    private void handleSave() {
        if (instance != null) {
            instance.setLoader(loaderComboBox.getValue());
            instance.setMcVersion(versionComboBox.getValue());
            instance.setAllowPrereleases(allowPrereleaseCheckBox.isSelected());
            if (allowHigherMcVersionsCheckBox != null) {
                instance.setAllowHigherMcVersions(allowHigherMcVersionsCheckBox.isSelected());
            }

            List<String> selectedLoaders = new ArrayList<>();
            for (Map.Entry<String, CheckBox> entry : loaderCheckBoxMap.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selectedLoaders.add(entry.getKey());
                }
            }
            instance.setExtraCompatibleLoaders(selectedLoaders);

            Map<String, String> tokens = instance.getApiTokens();
            if (tokenModrinthField != null) tokens.put("modrinth", tokenModrinthField.getText() != null ? tokenModrinthField.getText().trim() : "");
            if (tokenHangarField != null) tokens.put("hangar", tokenHangarField.getText() != null ? tokenHangarField.getText().trim() : "");
            if (tokenVoxelField != null) tokens.put("voxel", tokenVoxelField.getText() != null ? tokenVoxelField.getText().trim() : "");
            if (tokenSpigetField != null) tokens.put("spiget", tokenSpigetField.getText() != null ? tokenSpigetField.getText().trim() : "");
            instance.setApiTokens(tokens);

            saved = true;
        }
        closeDialog();
    }

    @FXML
    private void handleCancel() {
        saved = false;
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) instanceNameLabel.getScene().getWindow();
        stage.close();
    }

    public boolean isSaved() {
        return saved;
    }
}
