package com.chiliasmstudio.smartpluginassistant.controller;

import com.chiliasmstudio.smartpluginassistant.model.ServerInstance;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;

public class CreateInstanceDialogController {
    @FXML private TextField nameField;
    @FXML private ComboBox<String> loaderComboBox;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private TextField customDirField;
    @FXML private Button browseDirBtn;

    private ServerInstance createdInstance;

    @FXML
    public void initialize() {
        loaderComboBox.getItems().addAll("paper", "spigot", "purpur", "folia", "velocity", "bungeecord", "fabric", "sponge");
        loaderComboBox.setValue("paper");

        versionComboBox.getItems().addAll("1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2");
        versionComboBox.setValue("1.21.1");
    }

    @FXML
    private void handleBrowseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("選擇伺服器目錄 (可留空使用預設路徑)");
        File dir = chooser.showDialog(browseDirBtn.getScene().getWindow());
        if (dir != null) {
            customDirField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void handleCreate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "請輸入伺服器名稱！", ButtonType.OK);
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
