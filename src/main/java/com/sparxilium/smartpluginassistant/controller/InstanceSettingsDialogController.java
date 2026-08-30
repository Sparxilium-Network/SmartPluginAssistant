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

        new com.sparxilium.smartpluginassistant.service.ModrinthService().fetchGameVersions()
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
            CheckBox cb = new CheckBox(loaderOption.toUpperCase() + " 插件相容支援");
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

            List<String> selectedLoaders = new ArrayList<>();
            for (Map.Entry<String, CheckBox> entry : loaderCheckBoxMap.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selectedLoaders.add(entry.getKey());
                }
            }
            instance.setExtraCompatibleLoaders(selectedLoaders);
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
