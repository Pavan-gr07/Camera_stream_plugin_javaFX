module com.example.camera_demo {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.base;
    requires org.bytedeco.javacv;


    opens com.example.camera_demo to javafx.fxml;
    exports com.example.camera_demo;
}