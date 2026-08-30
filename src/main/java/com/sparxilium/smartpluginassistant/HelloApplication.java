package com.sparxilium.smartpluginassistant;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.prefs.Preferences;

public class HelloApplication extends Application {
    private static final String PREF_WINDOW_WIDTH = "window_width";
    private static final String PREF_WINDOW_HEIGHT = "window_height";
    private static final String PREF_WINDOW_MAXIMIZED = "window_maximized";
    private static final String PREF_WINDOW_X = "window_x";
    private static final String PREF_WINDOW_Y = "window_y";

    private final Preferences prefs = Preferences.userNodeForPackage(HelloApplication.class);

    @Override
    public void start(Stage stage) throws IOException {
        double savedWidth = prefs.getDouble(PREF_WINDOW_WIDTH, 1280);
        double savedHeight = prefs.getDouble(PREF_WINDOW_HEIGHT, 780);
        boolean isMaximized = prefs.getBoolean(PREF_WINDOW_MAXIMIZED, false);
        double savedX = prefs.getDouble(PREF_WINDOW_X, -1);
        double savedY = prefs.getDouble(PREF_WINDOW_Y, -1);

        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("/com/sparxilium/smartpluginassistant/main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), savedWidth, savedHeight);
        scene.getStylesheets().add(getClass().getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm());
        stage.setTitle("Smart Plugin Assistant - 伺服器插件管理器");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(640);

        if (savedX >= 0 && savedY >= 0) {
            stage.setX(savedX);
            stage.setY(savedY);
        }

        stage.show();

        if (isMaximized) {
            stage.setMaximized(true);
        }

        // Save window dimensions and state on close
        stage.setOnCloseRequest(e -> {
            prefs.putBoolean(PREF_WINDOW_MAXIMIZED, stage.isMaximized());
            if (!stage.isMaximized()) {
                prefs.putDouble(PREF_WINDOW_WIDTH, stage.getWidth());
                prefs.putDouble(PREF_WINDOW_HEIGHT, stage.getHeight());
                prefs.putDouble(PREF_WINDOW_X, stage.getX());
                prefs.putDouble(PREF_WINDOW_Y, stage.getY());
            }
        });
    }

    public static void main(String[] args) {
        launch();
    }
}
