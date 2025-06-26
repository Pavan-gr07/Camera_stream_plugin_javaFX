package com.example.camera_demo;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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
import java.util.Map;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.websocket.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

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

    // PTZ Control Elements
    @FXML
    private VBox ptzControlsBox;

    @FXML
    private Button upButton;

    @FXML
    private Button downButton;

    @FXML
    private Button leftButton;

    @FXML
    private Button rightButton;

    @FXML
    private Button homeButton;

    @FXML
    private Button zoomInButton;

    @FXML
    private Button zoomOutButton;

    @FXML
    private Slider speedSlider;

    @FXML
    private Label speedLabel;

    private List<String> cameraUrls = new ArrayList<>();
    private List<CameraInfo> cameraInfoList = new ArrayList<>();
    private int currentCameraIndex = 0;
    private String activeStreamUrl = null;

    private volatile boolean isStreaming = false;
    private ExecutorService executorService;
    private Task<Void> streamTask;
    private FFmpegFrameGrabber grabber;
    private Session webSocketSession;
    private WebSocketContainer container;
    private HttpClient httpClient;

    // WebSocket connection URL
    private static final String WEBSOCKET_URL = "ws://localhost:8080";

    // Camera info class to store camera details
    private static class CameraInfo {
        String url;
        String ip;
        String username;
        String password;
        int port;
        boolean isPtz;

        CameraInfo(String url) {
            this.url = url;
            parseRtspUrl(url);
        }

        private void parseRtspUrl(String rtspUrl) {
            try {
                // Parse RTSP URL: rtsp://username:password@ip:port/path
                if (rtspUrl.startsWith("rtsp://")) {
                    String urlPart = rtspUrl.substring(7); // Remove "rtsp://"

                    if (urlPart.contains("@")) {
                        String[] parts = urlPart.split("@", 2);
                        String credentials = parts[0];
                        String hostPart = parts[1];

                        if (credentials.contains(":")) {
                            String[] credParts = credentials.split(":", 2);
                            this.username = credParts[0];
                            this.password = credParts[1];
                        }

                        if (hostPart.contains(":")) {
                            String[] hostParts = hostPart.split(":", 2);
                            this.ip = hostParts[0];
                            String portAndPath = hostParts[1];
                            if (portAndPath.contains("/")) {
                                this.port = Integer.parseInt(portAndPath.substring(0, portAndPath.indexOf("/")));
                            } else {
                                this.port = Integer.parseInt(portAndPath);
                            }
                        } else {
                            this.ip = hostPart.split("/")[0];
                            this.port = 554; // Default RTSP port
                        }
                    }

                    // Determine if it's a PTZ camera based on IP or other criteria
                    // You can customize this logic based on your camera setup
                    this.isPtz = rtspUrl.contains("192.168.100.23"); // First camera is PTZ
                }
            } catch (Exception e) {
                System.err.println("Error parsing RTSP URL: " + e.getMessage());
                this.isPtz = false;
            }
        }
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        executorService = Executors.newCachedThreadPool();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Configure image view
        videoImageView.setFitWidth(640);
        videoImageView.setFitHeight(480);
        videoImageView.setPreserveRatio(true);

        // Initialize carousel controls
        setupCarouselControls();

        // Initialize PTZ controls
        setupPtzControls();

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

    private void setupPtzControls() {
        // Initially hide PTZ controls
        ptzControlsBox.setVisible(false);

        // Setup PTZ button actions
        upButton.setOnAction(e -> sendPtzCommand("up"));
        downButton.setOnAction(e -> sendPtzCommand("down"));
        leftButton.setOnAction(e -> sendPtzCommand("left"));
        rightButton.setOnAction(e -> sendPtzCommand("right"));
        homeButton.setOnAction(e -> sendPtzCommand("home"));
        zoomInButton.setOnAction(e -> sendPtzCommand("zoomIn"));
        zoomOutButton.setOnAction(e -> sendPtzCommand("zoomOut"));

        // Setup speed slider
        speedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            speedLabel.setText(String.format("Speed: %.1f", newVal.doubleValue()));
        });

        // Mouse press and release for continuous movement
        setupContinuousMovement();
    }

    private void setupContinuousMovement() {
        // For continuous movement while button is pressed
        upButton.setOnMousePressed(e -> startContinuousMovement("up"));
        upButton.setOnMouseReleased(e -> stopContinuousMovement());

        downButton.setOnMousePressed(e -> startContinuousMovement("down"));
        downButton.setOnMouseReleased(e -> stopContinuousMovement());

        leftButton.setOnMousePressed(e -> startContinuousMovement("left"));
        leftButton.setOnMouseReleased(e -> stopContinuousMovement());

        rightButton.setOnMousePressed(e -> startContinuousMovement("right"));
        rightButton.setOnMouseReleased(e -> stopContinuousMovement());

        zoomInButton.setOnMousePressed(e -> startContinuousMovement("zoomIn"));
        zoomInButton.setOnMouseReleased(e -> stopContinuousMovement());

        zoomOutButton.setOnMousePressed(e -> startContinuousMovement("zoomOut"));
        zoomOutButton.setOnMouseReleased(e -> stopContinuousMovement());
    }

    private Task<Void> continuousMovementTask;
    private volatile boolean isContinuousMovement = false;

    private void startContinuousMovement(String direction) {
        if (continuousMovementTask != null) {
            continuousMovementTask.cancel(true);
        }

        isContinuousMovement = true;
        continuousMovementTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                while (isContinuousMovement && !isCancelled()) {
                    sendPtzCommandInternal(direction);
                    Thread.sleep(100); // Send command every 100ms
                }
                return null;
            }
        };

        executorService.submit(continuousMovementTask);
    }

    private void stopContinuousMovement() {
        isContinuousMovement = false;
        if (continuousMovementTask != null) {
            continuousMovementTask.cancel(true);
        }
        // Send stop command
        sendPtzCommandInternal("stop");
    }

    private void sendPtzCommand(String command) {
        if (getCurrentCameraInfo() == null || !getCurrentCameraInfo().isPtz) {
            System.out.println("Current camera is not PTZ enabled");
            return;
        }

        executorService.submit(() -> sendPtzCommandInternal(command));
    }

    private void sendPtzCommandInternal(String command) {
        CameraInfo currentCamera = getCurrentCameraInfo();
        if (currentCamera == null || !currentCamera.isPtz) {
            return;
        }

        try {
            String ptzUrl = buildPtzCommandUrl(currentCamera, command);
            if (ptzUrl == null) {
                return;
            }

            String auth = Base64.getEncoder().encodeToString(
                    (currentCamera.username + ":" + currentCamera.password).getBytes()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ptzUrl))
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("PTZ command sent successfully: " + command);
            } else {
                System.out.println("PTZ command failed: " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("Error sending PTZ command: " + e.getMessage());
        }
    }

    private String buildPtzCommandUrl(CameraInfo camera, String command) {
        double speed = speedSlider.getValue();
        int speedValue = (int) (speed * 8); // Convert to camera speed range (0-8)

        String baseUrl = String.format("http://%s/cgi-bin/ptz.cgi", camera.ip);

        // These URLs might need to be adjusted based on your specific camera model
        // Common formats for different camera brands:

        switch (command) {
            case "up":
                return baseUrl + "?action=start&channel=0&code=Up&arg1=" + speedValue + "&arg2=" + speedValue;
            case "down":
                return baseUrl + "?action=start&channel=0&code=Down&arg1=" + speedValue + "&arg2=" + speedValue;
            case "left":
                return baseUrl + "?action=start&channel=0&code=Left&arg1=" + speedValue + "&arg2=" + speedValue;
            case "right":
                return baseUrl + "?action=start&channel=0&code=Right&arg1=" + speedValue + "&arg2=" + speedValue;
            case "zoomIn":
                return baseUrl + "?action=start&channel=0&code=ZoomTele&arg1=" + speedValue + "&arg2=" + speedValue;
            case "zoomOut":
                return baseUrl + "?action=start&channel=0&code=ZoomWide&arg1=" + speedValue + "&arg2=" + speedValue;
            case "home":
                return baseUrl + "?action=start&channel=0&code=GotoPreset&arg1=1&arg2=1";
            case "stop":
                return baseUrl + "?action=stop&channel=0&code=Up"; // Stop current movement
            default:
                return null;
        }
    }

    private CameraInfo getCurrentCameraInfo() {
        if (currentCameraIndex >= 0 && currentCameraIndex < cameraInfoList.size()) {
            return cameraInfoList.get(currentCameraIndex);
        }
        return null;
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

        // Update camera list and create camera info objects
        cameraUrls = new ArrayList<>(newUrls);
        cameraInfoList.clear();
        for (String url : newUrls) {
            cameraInfoList.add(new CameraInfo(url));
        }
        currentCameraIndex = 0;

        // Update UI
        updateCarouselDisplay();
        updatePtzControls();

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
        CameraInfo currentCamera = getCurrentCameraInfo();
        String cameraType = (currentCamera != null && currentCamera.isPtz) ? " (PTZ)" : " (Fixed)";
        cameraInfoLabel.setText("Camera " + (currentCameraIndex + 1) + " of " + cameraUrls.size() + cameraType);
        cameraCountLabel.setText(cameraUrls.size() + " camera(s) available");

        // Enable/disable navigation buttons
        prevButton.setDisable(cameraUrls.size() <= 1);
        nextButton.setDisable(cameraUrls.size() <= 1);
    }

    private void updatePtzControls() {
        CameraInfo currentCamera = getCurrentCameraInfo();
        boolean showPtzControls = (currentCamera != null && currentCamera.isPtz);

        ptzControlsBox.setVisible(showPtzControls);

        if (showPtzControls) {
            System.out.println("PTZ controls enabled for camera: " + currentCamera.ip);
        }
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
        updatePtzControls();

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

        // Stop continuous movement if active
        stopContinuousMovement();

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

        // Stop continuous movement
        stopContinuousMovement();

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