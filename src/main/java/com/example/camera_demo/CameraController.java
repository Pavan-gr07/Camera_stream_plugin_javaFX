package com.example.camera_demo;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

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
import javax.imageio.ImageIO;
import javax.websocket.*;

@ClientEndpoint
public class CameraController implements Initializable {

    @FXML
    private ImageView videoImageView;

    @FXML
    private Label statusLabel;

    private String currentCameraUrl;
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

        // Initialize WebSocket connection
        initializeWebSocket();
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
            statusLabel.setText("Status: WebSocket connected - Waiting for camera URL...");
        });
        System.out.println("WebSocket connection opened");
    }

    @OnMessage
    public void onWebSocketMessage(String message) {
        System.out.println("Received camera URL: " + message);

        // Validate if the message is an RTSP URL
        if (message != null && message.trim().startsWith("rtsp://")) {
            currentCameraUrl = message.trim();

            Platform.runLater(() -> {
                statusLabel.setText("Status: Camera URL received - Connecting to stream...");
                // Stop current stream if running and start new one
                stopStreamInternal();
                startStreamInternal();
            });
        } else {
            Platform.runLater(() -> {
                statusLabel.setText("Status: Invalid camera URL received");
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

    private void startStreamInternal() {
        if (isStreaming || currentCameraUrl == null) return;

        isStreaming = true;
        statusLabel.setText("Status: Connecting to camera...");

        streamTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    streamFromCamera();
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Status: Camera connection failed - " + e.getMessage());
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
                if (currentCameraUrl != null && !isStreaming) {
                    Platform.runLater(this::startStreamInternal);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void streamFromCamera() {
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try {
            // Initialize FFmpeg frame grabber
            grabber = new FFmpegFrameGrabber(currentCameraUrl);

            // Set options for better streaming
            grabber.setOption("rtsp_transport", "tcp"); // Use TCP for more reliable connection
            grabber.setOption("buffer_size", "1024000");
            grabber.setOption("max_delay", "0");
            grabber.setOption("stimeout", "5000000"); // 5 second timeout
            grabber.setImageWidth(640);
            grabber.setImageHeight(480);
            grabber.setFrameRate(30);

            Platform.runLater(() -> statusLabel.setText("Status: Initializing camera stream..."));

            grabber.start();

            Platform.runLater(() -> statusLabel.setText("Status: Camera connected - Streaming"));

            int consecutiveErrors = 0;
            final int MAX_CONSECUTIVE_ERRORS = 5;

            while (isStreaming && !Thread.currentThread().isInterrupted()) {
                try {
                    Frame frame = grabber.grab();

                    if (frame != null && frame.image != null) {
                        // Convert frame to BufferedImage
                        BufferedImage bufferedImage = converter.convert(frame);

                        if (bufferedImage != null) {
                            // Convert BufferedImage to JavaFX Image
                            Image fxImage = convertToFXImage(bufferedImage);

                            Platform.runLater(() -> videoImageView.setImage(fxImage));

                            // Reset error counter on successful frame
                            consecutiveErrors = 0;
                        }
                    }

                    // Control frame rate (~30 FPS)
                    Thread.sleep(33);

                } catch (Exception e) {
                    consecutiveErrors++;

                    if (isStreaming) {
                        System.err.println("Frame grab error: " + e.getMessage());

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            Platform.runLater(() -> {
                                statusLabel.setText("Status: Too many errors - Reconnecting...");
                            });
                            break; // Exit loop to trigger reconnection
                        }

                        Thread.sleep(100); // Brief pause before retry
                    }
                }
            }

        } catch (Exception e) {
            Platform.runLater(() -> {
                statusLabel.setText("Status: Stream error - " + e.getMessage());
                scheduleStreamReconnect();
            });
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

        if (streamTask != null) {
            streamTask.cancel(true);
        }

        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
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
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                System.err.println("Cleanup error: " + e.getMessage());
            }
        }

        if (executorService != null) {
            executorService.shutdown();
        }
    }
}