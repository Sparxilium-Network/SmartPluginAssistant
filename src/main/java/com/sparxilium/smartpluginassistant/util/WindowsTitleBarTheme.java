package com.sparxilium.smartpluginassistant.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.ptr.IntByReference;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class WindowsTitleBarTheme {
    private static final Logger logger = LogManager.getLogger(WindowsTitleBarTheme.class);

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        WinDef.HWND FindWindowW(com.sun.jna.WString lpClassName, com.sun.jna.WString lpWindowName);
    }

    public interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class);
        int DwmSetWindowAttribute(WinDef.HWND hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }

    public static void applyDarkTitleBar(javafx.scene.control.Dialog<?> dialog) {
        if (dialog == null) return;
        try {
            dialog.getDialogPane().getStylesheets().add(
                    WindowsTitleBarTheme.class.getResource("/com/sparxilium/smartpluginassistant/style.css").toExternalForm()
            );
        } catch (Exception ignored) {}

        javafx.stage.Stage stage = (javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow();
        applyDarkTitleBar(stage);
    }

    public static void applyDarkTitleBar(Stage stage) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return;
        }

        stage.showingProperty().addListener((obs, oldV, newV) -> {
            if (newV) {
                javafx.application.Platform.runLater(() -> setDarkTheme(stage));
            }
        });

        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> {
            javafx.application.Platform.runLater(() -> setDarkTheme(stage));
        });

        if (stage.isShowing()) {
            javafx.application.Platform.runLater(() -> setDarkTheme(stage));
        }
    }

    private static void setDarkTheme(Stage stage) {
        try {
            String title = stage.getTitle();
            if (title == null || title.isBlank()) {
                return;
            }

            WinDef.HWND hwnd = User32.INSTANCE.FindWindowW(null, new com.sun.jna.WString(title));
            if (hwnd != null) {
                IntByReference darkMode = new IntByReference(1);
                // DWMWA_USE_IMMERSIVE_DARK_MODE = 20 (Win10 20H1+ / Win11)
                int res20 = Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, darkMode.getPointer(), 4);
                if (res20 != 0) {
                    // Fallback to 19 (older Win10)
                    Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, darkMode.getPointer(), 4);
                }
                logger.info("Windows dark title bar applied for window: '{}'", title);
            }
        } catch (Throwable t) {
            logger.debug("Failed to set dark title bar: {}", t.getMessage());
        }
    }
}
