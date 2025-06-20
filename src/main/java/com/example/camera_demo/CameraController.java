package com.example.camera_demo;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.websocket.*;

@ClientEndpoint
public class CameraController implements Initializable {

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
    private String activeStreamUrl = null;

    private volatile boolean isStreaming = false;
    private ExecutorService executorService;
    private Task<Void> streamTask;
    private FFmpegFrameGrabber grabber;
    private Session webSocketSession;
    private WebSocketContainer container;

    // WebSocket connection URL
    private static final String WEBSOCKET_URL = "ws://localhost:8080";

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        executorService = Executors.newSingleThreadExecutor();

        // Configure image view
        videoImageView.setFitWidth(640);
        videoImageView.setFitHeight(480);
        videoImageView.setPreserveRatio(true);

        // Initialize carousel controls
        setupCarouselControls();

        // Initialize WebSocket connection
        initializeWebSocket();
    }

    private void setupCarouselControls() {
        // Initially hide carousel controls until we have multiple cameras
        carouselControls.setVisible(false);
        carouselControls.setAlignment(Pos.CENTER);

        prevButton.setOnAction(e -> switchToPreviousCamera());
        nextButton.setOnAction(e -> switchToNextCamera());

        updateCarouselDisplay();
    }

    private void initializeWebSocket() {
        try {
            container = ContainerProvider.getWebSocketContainer();
            statusLabel.setText("Status: Connecting to WebSocket...");

            URI serverEndpointUri = new URI(WEBSOCKET_URL);
            webSocketSession = container.connectToServer(this, serverEndpointUri);

        } catch (Exception e) {
            Platform.runLater(() -> {
                statusLabel.setText("Status: WebSocket connection failed - " + e.getMessage());
            });

            // Retry connection after 5 seconds
            scheduleWebSocketReconnect();
        }
    }

    private void scheduleWebSocketReconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait 5 seconds before retry
                Platform.runLater(this::initializeWebSocket);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    @OnOpen
    public void onWebSocketOpen(Session session) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: WebSocket connected - Waiting for camera URLs...");
        });
        System.out.println("WebSocket connection opened");
    }

    @OnMessage
    public void onWebSocketMessage(String message) {
        System.out.println("Received message: " + message);

        if (message == null || message.trim().isEmpty()) {
            return;
        }

        // Handle multiple URLs separated by comma or semicolon
        String[] urls = message.trim().split("[,;]");
        List<String> newUrls = new ArrayList<>();

        for (String url : urls) {
            String trimmedUrl = url.trim();
            if (trimmedUrl.startsWith("rtsp://")) {
                newUrls.add(trimmedUrl);
            }
        }

        if (!newUrls.isEmpty()) {
            Platform.runLater(() -> {
                updateCameraUrls(newUrls);
            });
        } else {
            Platform.runLater(() -> {
                statusLabel.setText("Status: No valid RTSP URLs received");
            });
        }
    }

    @OnClose
    public void onWebSocketClose(Session session, CloseReason closeReason) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: WebSocket disconnected - " + closeReason.getReasonPhrase());
        });
        System.out.println("WebSocket connection closed: " + closeReason.getReasonPhrase());

        // Attempt to reconnect after 3 seconds
        scheduleWebSocketReconnect();
    }

    @OnError
    public void onWebSocketError(Session session, Throwable throwable) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: WebSocket error - " + throwable.getMessage());
        });
        System.err.println("WebSocket error: " + throwable.getMessage());

        // Attempt to reconnect after 3 seconds
        scheduleWebSocketReconnect();
    }

    private void updateCameraUrls(List<String> newUrls) {
        // Check if URLs have actually changed
        if (cameraUrls.equals(newUrls)) {
            System.out.println("Camera URLs unchanged, skipping update");
            return;
        }

        System.out.println("Updating camera URLs: " + newUrls.size() + " cameras received");

        // Stop current stream
        stopStreamInternal();

        // Update camera list
        cameraUrls = new ArrayList<>(newUrls);
        currentCameraIndex = 0;

        // Update UI
        updateCarouselDisplay();

        // Start streaming the first camera
        if (!cameraUrls.isEmpty()) {
            startStreamInternal();
        }
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

        // Only switch if it's a different camera
        String newUrl = cameraUrls.get(currentCameraIndex);
        if (!newUrl.equals(activeStreamUrl)) {
            stopStreamInternal();
            startStreamInternal();
        }
    }

    private void startStreamInternal() {
        if (isStreaming || cameraUrls.isEmpty() || currentCameraIndex >= cameraUrls.size()) {
            return;
        }

        String cameraUrl = cameraUrls.get(currentCameraIndex);

        // Don't restart if same URL is already streaming
        if (cameraUrl.equals(activeStreamUrl) && isStreaming) {
            System.out.println("Camera already streaming: " + cameraUrl);
            return;
        }

        isStreaming = true;
        activeStreamUrl = cameraUrl;
        statusLabel.setText("Status: Connecting to camera " + (currentCameraIndex + 1) + "...");

        streamTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    streamFromCamera(cameraUrl);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Status: Camera " + (currentCameraIndex + 1) + " failed - " + e.getMessage());
                        // Auto-retry connection after 10 seconds
                        scheduleStreamReconnect();
                    });
                }
                return null;
            }
        };

        executorService.submit(streamTask);
    }

    private void scheduleStreamReconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(10000); // Wait 10 seconds before retry
                if (!cameraUrls.isEmpty() && !isStreaming) {
                    Platform.runLater(this::startStreamInternal);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void streamFromCamera(String cameraUrl) {
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try {
            // Initialize FFmpeg frame grabber
            grabber = new FFmpegFrameGrabber(cameraUrl);

            // Set options for better streaming
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("buffer_size", "1024000");
            grabber.setOption("max_delay", "0");
            grabber.setOption("stimeout", "5000000"); // 5 second timeout
            grabber.setImageWidth(640);
            grabber.setImageHeight(480);
            grabber.setFrameRate(30);

            Platform.runLater(() -> statusLabel.setText("Status: Initializing camera " + (currentCameraIndex + 1) + " stream..."));

            grabber.start();

            Platform.runLater(() -> statusLabel.setText("Status: Camera " + (currentCameraIndex + 1) + " connected - Streaming"));

            int consecutiveErrors = 0;
            final int MAX_CONSECUTIVE_ERRORS = 5;

            while (isStreaming && !Thread.currentThread().isInterrupted() &&
                    cameraUrl.equals(activeStreamUrl)) { // Check if this is still the active stream
                try {
                    Frame frame = grabber.grab();

                    if (frame != null && frame.image != null) {
                        BufferedImage bufferedImage = converter.convert(frame);

                        if (bufferedImage != null) {
                            Image fxImage = convertToFXImage(bufferedImage);
                            Platform.runLater(() -> videoImageView.setImage(fxImage));
                            consecutiveErrors = 0;
                        }
                    }

                    // Control frame rate (~30 FPS)
                    Thread.sleep(33);

                } catch (Exception e) {
                    consecutiveErrors++;

                    if (isStreaming && cameraUrl.equals(activeStreamUrl)) {
                        System.err.println("Frame grab error: " + e.getMessage());

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Status: Camera " + (currentCameraIndex + 1) + " - Too many errors, reconnecting...");
                            });
                            break;
                        }

                        Thread.sleep(100);
                    }
                }
            }

        } catch (Exception e) {
            if (cameraUrl.equals(activeStreamUrl)) { // Only show error if this is still the active stream
                Platform.runLater(() -> {
                    statusLabel.setText("Status: Camera " + (currentCameraIndex + 1) + " error - " + e.getMessage());
                    scheduleStreamReconnect();
                });
            }
        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    System.err.println("Error stopping grabber: " + e.getMessage());
                }
            }
        }
    }

    private Image convertToFXImage(BufferedImage bufferedImage) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "jpg", baos);
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            return new Image(bais);
        } catch (Exception e) {
            System.err.println("Error converting image: " + e.getMessage());
            return null;
        }
    }

    private void stopStreamInternal() {
        isStreaming = false;
        activeStreamUrl = null;

        if (streamTask != null) {
            streamTask.cancel(true);
        }

        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
                grabber = null;
            } catch (Exception e) {
                System.err.println("Error stopping stream: " + e.getMessage());
            }
        }
    }

    public void cleanup() {
        isStreaming = false;

        // Close WebSocket connection
        if (webSocketSession != null) {
            try {
                webSocketSession.close();
            } catch (Exception e) {
                System.err.println("Error closing WebSocket: " + e.getMessage());
            }
        }

        // Stop camera stream
        stopStreamInternal();

        if (executorService != null) {
            executorService.shutdown();
        }
    }
}