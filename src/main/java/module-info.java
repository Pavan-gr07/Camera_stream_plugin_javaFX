module com.example.camera_demo {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.base;
    requires javafx.media;
    requires org.bytedeco.javacv;
    requires javax.websocket.client.api;
    requires java.net.http;


    opens com.example.camera_demo to javafx.fxml;
    exports com.example.camera_demo;
}