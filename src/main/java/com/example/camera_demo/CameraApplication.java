package com.example.camera_demo;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class CameraApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(CameraApplication.class.getResource("camera-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 800, 600);
        stage.setTitle("RTSP Camera Viewer - Pure JavaFX");
        stage.setScene(scene);
        stage.show();

        // Handle cleanup on close
        stage.setOnCloseRequest(e -> {
            CameraController controller = fxmlLoader.getController();
            if (controller != null) {
                controller.cleanup();
            }
        });
    }

    public static void main(String[] args) {
        launch();
    }
}