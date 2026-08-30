module com.sparxilium.smartpluginassistant {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires java.net.http;
    requires java.desktop;

    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires org.kordamp.bootstrapfx.core;

    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires org.apache.logging.log4j;
    requires org.slf4j;

    opens com.sparxilium.smartpluginassistant to javafx.fxml;
    opens com.sparxilium.smartpluginassistant.controller to javafx.fxml;
    opens com.sparxilium.smartpluginassistant.model to com.fasterxml.jackson.databind, javafx.base;

    exports com.sparxilium.smartpluginassistant;
    exports com.sparxilium.smartpluginassistant.model;
    exports com.sparxilium.smartpluginassistant.service;
    exports com.sparxilium.smartpluginassistant.controller;
}