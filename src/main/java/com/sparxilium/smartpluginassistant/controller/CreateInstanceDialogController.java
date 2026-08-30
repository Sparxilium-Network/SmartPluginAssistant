package com.sparxilium.smartpluginassistant.controller;

import com.sparxilium.smartpluginassistant.model.ServerInstance;
import com.sparxilium.smartpluginassistant.service.I18n;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;

public class CreateInstanceDialogController {
    @FXML private Label titleLabel;
    @FXML private Label hintLabel;
    @FXML private Label nameLabel;
    @FXML private TextField nameField;
    @FXML private Label loaderLabel;
    @FXML private ComboBox<String> loaderComboBox;
    @FXML private Label versionLabel;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private Label customDirLabel;
    @FXML private TextField customDirField;
    @FXML private Button browseDirBtn;
    @FXML private Button cancelBtn;
    @FXML private Button createBtn;

    private ServerInstance createdInstance;

    @FXML
    public void initialize() {
        applyI18n();

        loaderComboBox.getItems().addAll("paper", "spigot", "purpur", "folia", "velocity", "bungeecord", "fabric", "sponge");
        loaderComboBox.setValue("paper");

        // Load game versions dynamically from Modrinth API
        new com.sparxilium.smartpluginassistant.service.ModrinthService().fetchGameVersions()
                .thenAccept(versions -> javafx.application.Platform.runLater(() -> {
                    versionComboBox.getItems().setAll(versions);
                    if (!versions.isEmpty()) {
                        versionComboBox.setValue(versions.get(0));
                    }
                }));

        nameField.setTextFormatter(new TextFormatter<String>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches(".*[\\\\/:*?\"<>|].*")) {
                return null;
            }
            return change;
        }));
    }

    private void applyI18n() {
        titleLabel.setText(I18n.get("create.title"));
        hintLabel.setText(I18n.get("create.hint"));
        nameLabel.setText(I18n.get("create.name"));
        nameField.setPromptText(I18n.get("create.name_prompt"));
        loaderLabel.setText(I18n.get("create.loader"));
        versionLabel.setText(I18n.get("create.version"));
        customDirLabel.setText(I18n.get("create.custom_dir"));
        customDirField.setPromptText(I18n.get("create.custom_dir_prompt"));
        browseDirBtn.setText(I18n.get("create.browse"));
        cancelBtn.setText(I18n.get("create.btn_cancel"));
        createBtn.setText(I18n.get("create.btn_create"));
    }

    @FXML
    private void handleBrowseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("create.custom_dir"));
        File dir = chooser.showDialog(browseDirBtn.getScene().getWindow());
        if (dir != null) {
            customDirField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void handleCreate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("create.err_empty_name"), ButtonType.OK);
            alert.showAndWait();
            return;
        }

        if (!ServerInstance.isValidFileName(name)) {
            Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("create.err_invalid_name"), ButtonType.OK);
            alert.showAndWait();
            return;
        }

        String loader = loaderComboBox.getValue();
        String mcVersion = versionComboBox.getValue();
        String customDir = customDirField.getText().trim();

        createdInstance = new ServerInstance(name, loader, mcVersion);
        if (!customDir.isEmpty()) {
            createdInstance.setCustomDirectory(customDir);
        }

        closeDialog();
    }

    @FXML
    private void handleCancel() {
        createdInstance = null;
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) nameField.getScene().getWindow();
        stage.close();
    }

    public ServerInstance getCreatedInstance() {
        return createdInstance;
    }
}
