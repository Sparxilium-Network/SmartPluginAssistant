package com.chiliasmstudio.smartpluginassistant;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("/com/chiliasmstudio/smartpluginassistant/main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/com/chiliasmstudio/smartpluginassistant/style.css").toExternalForm());
        stage.setTitle("Smart Plugin Assistant - 伺服器插件管理器");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
