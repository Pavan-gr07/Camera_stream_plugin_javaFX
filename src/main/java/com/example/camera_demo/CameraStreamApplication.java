package com.example.camera_demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraStreamApplication extends Application {

    private static final String RTSP_URL = "rtsp://admin:G@nd66v@N@192.168.100.23:554/cam/realmonitor?channel=1&subtype=0";

    private ImageView imageView;
    private Label statusLabel;
    private Button playButton;
    private Button stopButton;
    private Button reconnectButton;

    private volatile boolean isStreaming = false;
    private ExecutorService executorService;
    private Task<Void> streamTask;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("RTSP Camera Stream Viewer - Pure JavaFX");

        initializeComponents();

        BorderPane root = new BorderPane();

        // Video display area
        root.setCenter(imageView);

        // Control panel
        HBox controlPanel = createControlPanel();
        root.setBottom(controlPanel);

        // Status bar
        VBox statusBar = new VBox(statusLabel);
        statusBar.setPadding(new Insets(5));
        root.setTop(statusBar);

        Scene scene = new Scene(root, 800, 600);
        primaryStage.setScene(scene);
        primaryStage.show();

        executorService = Executors.newSingleThreadExecutor();

        primaryStage.setOnCloseRequest(e -> {
            stopStreaming();
            if (executorService != null) {
                executorService.shutdown();
            }
            Platform.exit();
            System.exit(0);
        });
    }

    private void initializeComponents() {
        // Image view for video display
        imageView = new ImageView();
        imageView.setFitWidth(640);
        imageView.setFitHeight(480);
        imageView.setPreserveRatio(true);
        imageView.setStyle("-fx-background-color: black;");

        statusLabel = new Label("Status: Ready");
        playButton = new Button("Start Stream");
        stopButton = new Button("Stop Stream");
        reconnectButton = new Button("Reconnect");

        playButton.setOnAction(e -> startStreaming());
        stopButton.setOnAction(e -> stopStreaming());
        reconnectButton.setOnAction(e -> reconnectStream());

        stopButton.setDisable(true);
    }

    private HBox createControlPanel() {
        HBox controlPanel = new HBox(10);
        controlPanel.setPadding(new Insets(10));
        controlPanel.getChildren().addAll(playButton, stopButton, reconnectButton);
        return controlPanel;
    }

    private void startStreaming() {
        if (isStreaming) return;

        isStreaming = true;
        playButton.setDisable(true);
        stopButton.setDisable(false);
        statusLabel.setText("Status: Connecting...");

        // For RTSP streams, we'll use a different approach
        // Convert RTSP to HTTP stream URL (many IP cameras support this)
        String httpStreamUrl = convertRtspToHttp(RTSP_URL);

        streamTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    Platform.runLater(() -> statusLabel.setText("Status: Attempting to connect..."));

                    // Try HTTP stream first
                    streamFromHttp(httpStreamUrl);

                } catch (Exception e) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Status: Connection failed - " + e.getMessage());
                        resetButtons();
                    });
                }
                return null;
            }
        };

        executorService.submit(streamTask);
    }

    private void streamFromHttp(String streamUrl) {
        try {
            URL url = new URL(streamUrl);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // Add authentication if needed
            String auth = "admin:G@nd66v@N";
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);

            Platform.runLater(() -> statusLabel.setText("Status: Connected - Streaming"));

            try (InputStream inputStream = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                ByteArrayInputStream frameStream = new ByteArrayInputStream(new byte[0]);

                while (isStreaming) {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        // For MJPEG streams, we need to parse individual frames
                        // This is a simplified approach
                        try {
                            frameStream = new ByteArrayInputStream(buffer, 0, bytesRead);
                            Image image = new Image(frameStream);

                            if (!image.isError()) {
                                Platform.runLater(() -> imageView.setImage(image));
                            }
                        } catch (Exception e) {
                            // Continue trying to read frames
                        }

                        Thread.sleep(33); // ~30 FPS
                    }
                }
            }

        } catch (Exception e) {
            Platform.runLater(() -> {
                statusLabel.setText("Status: Stream error - " + e.getMessage());
                resetButtons();
            });
        }
    }

    private String convertRtspToHttp(String rtspUrl) {
        // Convert RTSP URL to HTTP MJPEG stream URL
        // This depends on your camera model - here are common patterns:

        // Extract IP and credentials
        String ip = "192.168.100.23";
        String user = "admin";
        String pass = "G@nd66v@N";

        // Common HTTP stream URLs for IP cameras:
        String[] possibleUrls = {
                "http://" + user + ":" + pass + "@" + ip + "/videostream.cgi",
                "http://" + user + ":" + pass + "@" + ip + "/video.cgi",
                "http://" + user + ":" + pass + "@" + ip + "/mjpeg.cgi",
                "http://" + user + ":" + pass + "@" + ip + "/video/mjpeg.cgi",
                "http://" + user + ":" + pass + "@" + ip + "/cgi-bin/video.cgi",
                "http://" + user + ":" + pass + "@" + ip + "/image.jpg" // For snapshot
        };

        return possibleUrls[0]; // Try the first one
    }

    private void stopStreaming() {
        isStreaming = false;

        if (streamTask != null) {
            streamTask.cancel();
        }

        resetButtons();
        Platform.runLater(() -> statusLabel.setText("Status: Stopped"));
    }

    private void reconnectStream() {
        stopStreaming();

        // Wait a moment before reconnecting
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                Platform.runLater(this::startStreaming);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void resetButtons() {
        Platform.runLater(() -> {
            playButton.setDisable(false);
            stopButton.setDisable(true);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}

