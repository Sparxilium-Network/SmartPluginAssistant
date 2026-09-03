module com.sparxilium.smartpluginassistant {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires java.net.http;
    requires java.desktop;
    requires java.prefs;

    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires org.kordamp.bootstrapfx.core;

    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires org.apache.logging.log4j;
    requires org.slf4j;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    opens com.sparxilium.smartpluginassistant to javafx.fxml;
    opens com.sparxilium.smartpluginassistant.controller to javafx.fxml;
    opens com.sparxilium.smartpluginassistant.model to com.fasterxml.jackson.databind, javafx.base;
    opens com.sparxilium.smartpluginassistant.model.hangar to com.fasterxml.jackson.databind, javafx.base;
    opens com.sparxilium.smartpluginassistant.model.modrinth to com.fasterxml.jackson.databind, javafx.base;
    opens com.sparxilium.smartpluginassistant.model.voxel to com.fasterxml.jackson.databind, javafx.base;
    opens com.sparxilium.smartpluginassistant.model.spiget to com.fasterxml.jackson.databind, javafx.base;

    exports com.sparxilium.smartpluginassistant;
    exports com.sparxilium.smartpluginassistant.model;
    exports com.sparxilium.smartpluginassistant.model.hangar;
    exports com.sparxilium.smartpluginassistant.model.modrinth;
    exports com.sparxilium.smartpluginassistant.model.voxel;
    exports com.sparxilium.smartpluginassistant.model.spiget;
    exports com.sparxilium.smartpluginassistant.service;
    exports com.sparxilium.smartpluginassistant.controller;
}