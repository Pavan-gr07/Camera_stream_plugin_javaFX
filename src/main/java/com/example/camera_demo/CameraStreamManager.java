package com.example.camera_demo;

import javafx.concurrent.Task;
import javafx.scene.image.Image;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import javax.imageio.ImageIO;
import javax.websocket.*;

@ClientEndpoint
public class CameraStreamManager {

    public interface CameraStreamListener {
        void onFrameReceived(Image frame);
        void onStatusUpdate(String status);
        void onWebSocketStatusUpdate(String status);
        void onCameraListReceived(List<String> cameras);
        void onError(String error, int cameraIndex);
    }

    private final CameraStreamListener listener;
    private volatile boolean isStreaming = false;
    private ExecutorService executorService;
    private Task<Void> streamTask;
    private FFmpegFrameGrabber grabber;
    private Session webSocketSession;
    private WebSocketContainer container;
    private String activeStreamUrl = null;

    // WebSocket connection URL
    private static final String WEBSOCKET_URL = "ws://localhost:8080";

    public CameraStreamManager(CameraStreamListener listener) {
        this.listener = listener;
    }

    public void initialize() {
        executorService = Executors.newSingleThreadExecutor();
        initializeWebSocket();
    }

    private void initializeWebSocket() {
        try {
            container = ContainerProvider.getWebSocketContainer();
            listener.onWebSocketStatusUpdate("Status: Connecting to WebSocket...");

            URI serverEndpointUri = new URI(WEBSOCKET_URL);
            webSocketSession = container.connectToServer(this, serverEndpointUri);

        } catch (Exception e) {
            listener.onWebSocketStatusUpdate("Status: WebSocket connection failed - " + e.getMessage());
            // Retry connection after 5 seconds
            scheduleWebSocketReconnect();
        }
    }

    private void scheduleWebSocketReconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait 5 seconds before retry
                initializeWebSocket();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public void startStream(String cameraUrl, int cameraIndex) {
        if (isStreaming || cameraUrl == null || cameraUrl.isEmpty()) {
            return;
        }

        // Don't restart if same URL is already streaming
        if (cameraUrl.equals(activeStreamUrl) && isStreaming) {
            System.out.println("Camera already streaming: " + cameraUrl);
            return;
        }

        isStreaming = true;
        activeStreamUrl = cameraUrl;
        listener.onStatusUpdate("Status: Connecting to camera " + (cameraIndex + 1) + "...");

        streamTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    streamFromCamera(cameraUrl, cameraIndex);
                } catch (Exception e) {
                    listener.onError(e.getMessage(), cameraIndex);
                    // Auto-retry connection after 10 seconds
                    scheduleStreamReconnect(cameraUrl, cameraIndex);
                }
                return null;
            }
        };

        executorService.submit(streamTask);
    }

    public void switchToCamera(String newUrl, int cameraIndex) {
        // Only switch if it's a different camera
        if (!newUrl.equals(activeStreamUrl)) {
            stopStream();
            startStream(newUrl, cameraIndex);
        }
    }

    private void scheduleStreamReconnect(String cameraUrl, int cameraIndex) {
        new Thread(() -> {
            try {
                Thread.sleep(10000); // Wait 10 seconds before retry
                if (!isStreaming && cameraUrl.equals(activeStreamUrl)) {
                    startStream(cameraUrl, cameraIndex);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void streamFromCamera(String cameraUrl, int cameraIndex) {
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

            listener.onStatusUpdate("Status: Initializing camera " + (cameraIndex + 1) + " stream...");

            grabber.start();

            listener.onStatusUpdate("Status: Camera " + (cameraIndex + 1) + " connected - Streaming");

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
                            if (fxImage != null) {
                                listener.onFrameReceived(fxImage);
                            }
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
                            listener.onStatusUpdate("Status: Camera " + (cameraIndex + 1) + " - Too many errors, reconnecting...");
                            break;
                        }

                        Thread.sleep(100);
                    }
                }
            }

        } catch (Exception e) {
            if (cameraUrl.equals(activeStreamUrl)) { // Only show error if this is still the active stream
                listener.onError(e.getMessage(), cameraIndex);
                scheduleStreamReconnect(cameraUrl, cameraIndex);
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

    public void stopStream() {
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
        stopStream();

        if (executorService != null) {
            executorService.shutdown();
        }
    }

    // WebSocket event handlers
    @OnOpen
    public void onOpen(Session session) {
        listener.onWebSocketStatusUpdate("Status: WebSocket connected");
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println("WebSocket message received: " + message);

        try {
            // Parse the message - assuming it contains camera URLs
            // Handle different message formats
            if (message.trim().startsWith("[") && message.trim().endsWith("]")) {
                // JSON array format: ["url1", "url2", "url3"]
                parseCameraUrlsFromJson(message);
            } else if (message.contains(",")) {
                // Comma-separated format: "url1,url2,url3"
                parseCameraUrlsFromCsv(message);
            } else {
                // Single URL format
                java.util.List<String> singleUrl = java.util.Arrays.asList(message.trim());
                listener.onCameraListReceived(singleUrl);
            }
        } catch (Exception e) {
            System.err.println("Error parsing WebSocket message: " + e.getMessage());
            listener.onWebSocketStatusUpdate("Status: Error parsing camera URLs - " + e.getMessage());
        }
    }

    private void parseCameraUrlsFromJson(String jsonMessage) {
        try {
            // Simple JSON parsing for array of strings
            String cleaned = jsonMessage.trim().substring(1, jsonMessage.trim().length() - 1); // Remove [ ]
            String[] urls = cleaned.split(",");
            java.util.List<String> cameraUrls = new java.util.ArrayList<>();

            for (String url : urls) {
                String cleanUrl = url.trim().replaceAll("\"", ""); // Remove quotes
                if (!cleanUrl.isEmpty()) {
                    cameraUrls.add(cleanUrl);
                }
            }

            if (!cameraUrls.isEmpty()) {
                listener.onCameraListReceived(cameraUrls);
            }
        } catch (Exception e) {
            System.err.println("Error parsing JSON camera URLs: " + e.getMessage());
        }
    }

    private void parseCameraUrlsFromCsv(String csvMessage) {
        try {
            String[] urls = csvMessage.split(",");
            java.util.List<String> cameraUrls = new java.util.ArrayList<>();

            for (String url : urls) {
                String cleanUrl = url.trim();
                if (!cleanUrl.isEmpty()) {
                    cameraUrls.add(cleanUrl);
                }
            }

            if (!cameraUrls.isEmpty()) {
                listener.onCameraListReceived(cameraUrls);
            }
        } catch (Exception e) {
            System.err.println("Error parsing CSV camera URLs: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        listener.onWebSocketStatusUpdate("Status: WebSocket disconnected - " + closeReason.getReasonPhrase());
        // Attempt to reconnect
        scheduleWebSocketReconnect();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        listener.onWebSocketStatusUpdate("Status: WebSocket error - " + throwable.getMessage());
    }
}