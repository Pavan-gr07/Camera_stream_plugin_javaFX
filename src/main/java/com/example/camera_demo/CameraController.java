package com.example.camera_demo;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.List;
import java.util.ArrayList;

public class CameraController implements Initializable, CameraStreamManager.CameraStreamListener {

    @FXML
    private ImageView videoImageView;

    @FXML
    private Label statusLabel;

    @FXML
    private Label cameraInfoLabel;

    @FXML
    private HBox carouselControls;

    @FXML
    private Button prevButton;

    @FXML
    private Button nextButton;

    @FXML
    private Label cameraCountLabel;

    private List<String> cameraUrls = new ArrayList<>();
    private int currentCameraIndex = 0;
    private CameraStreamManager streamManager;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // Configure image view
        videoImageView.setFitWidth(640);
        videoImageView.setFitHeight(480);
        videoImageView.setPreserveRatio(true);

        // Initialize carousel controls
        setupCarouselControls();

        // Initialize stream manager
        streamManager = new CameraStreamManager(this);
        streamManager.initialize();
    }

    private void setupCarouselControls() {
        // Initially hide carousel controls until we have multiple cameras
        carouselControls.setVisible(false);
        carouselControls.setAlignment(Pos.CENTER);

        prevButton.setOnAction(e -> switchToPreviousCamera());
        nextButton.setOnAction(e -> switchToNextCamera());

        updateCarouselDisplay();
    }

    private void updateCarouselDisplay() {
        if (cameraUrls.isEmpty()) {
            carouselControls.setVisible(false);
            cameraInfoLabel.setText("No cameras available");
            cameraCountLabel.setText("");
            return;
        }

        // Show carousel controls only if multiple cameras
        carouselControls.setVisible(cameraUrls.size() > 1);

        // Update camera info
        cameraInfoLabel.setText("Camera " + (currentCameraIndex + 1) + " of " + cameraUrls.size());
        cameraCountLabel.setText(cameraUrls.size() + " camera(s) available");

        // Enable/disable navigation buttons
        prevButton.setDisable(cameraUrls.size() <= 1);
        nextButton.setDisable(cameraUrls.size() <= 1);
    }

    private void switchToPreviousCamera() {
        if (cameraUrls.size() <= 1) return;

        currentCameraIndex = (currentCameraIndex - 1 + cameraUrls.size()) % cameraUrls.size();
        switchCamera();
    }

    private void switchToNextCamera() {
        if (cameraUrls.size() <= 1) return;

        currentCameraIndex = (currentCameraIndex + 1) % cameraUrls.size();
        switchCamera();
    }

    private void switchCamera() {
        updateCarouselDisplay();

        if (!cameraUrls.isEmpty() && currentCameraIndex < cameraUrls.size()) {
            String newUrl = cameraUrls.get(currentCameraIndex);
            streamManager.switchToCamera(newUrl, currentCameraIndex);
        }
    }

    public void startStreaming() {
        if (!cameraUrls.isEmpty() && currentCameraIndex < cameraUrls.size()) {
            String cameraUrl = cameraUrls.get(currentCameraIndex);
            System.out.println("Starting stream for camera " + (currentCameraIndex + 1) + ": " + cameraUrl);
            streamManager.startStream(cameraUrl, currentCameraIndex);
        } else {
            System.out.println("Cannot start streaming: cameraUrls.isEmpty()=" + cameraUrls.isEmpty() +
                    ", currentCameraIndex=" + currentCameraIndex +
                    ", cameraUrls.size()=" + cameraUrls.size());
            statusLabel.setText("Status: No cameras available to stream");
        }
    }

    public void stopStreaming() {
        streamManager.stopStream();
    }

    public void addCameraUrl(String url) {
        cameraUrls.add(url);
        updateCarouselDisplay();
    }

    public void setCameraUrls(List<String> urls) {
        cameraUrls.clear();
        cameraUrls.addAll(urls);
        currentCameraIndex = 0;
        updateCarouselDisplay();
    }

    // CameraStreamListener interface implementations
    @Override
    public void onFrameReceived(Image frame) {
        Platform.runLater(() -> videoImageView.setImage(frame));
    }

    @Override
    public void onStatusUpdate(String status) {
        Platform.runLater(() -> statusLabel.setText(status));
    }

    @Override
    public void onWebSocketStatusUpdate(String status) {
        Platform.runLater(() -> statusLabel.setText(status));
    }

    @Override
    public void onCameraListReceived(List<String> cameras) {
        Platform.runLater(() -> {
            System.out.println("Received " + cameras.size() + " camera URLs: " + cameras);
            setCameraUrls(cameras);
            if (!cameras.isEmpty()) {
                // Start streaming the first camera
                currentCameraIndex = 0;
                updateCarouselDisplay();
                startStreaming();
                statusLabel.setText("Status: Starting stream with " + cameras.size() + " camera(s)...");
            } else {
                statusLabel.setText("Status: No cameras available");
            }
        });
    }

    @Override
    public void onError(String error, int cameraIndex) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: Camera " + (cameraIndex + 1) + " error - " + error);
        });
    }

    public void cleanup() {
        if (streamManager != null) {
            streamManager.cleanup();
        }
    }
}