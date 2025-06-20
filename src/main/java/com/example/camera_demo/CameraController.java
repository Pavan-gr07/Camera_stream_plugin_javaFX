package com.example.camera_demo;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

public class CameraController implements Initializable {

    @FXML
    private ImageView videoImageView;

    @FXML
    private Button playBtn;

    @FXML
    private Button stopBtn;

    @FXML
    private Button reconnectBtn;

    @FXML
    private Label statusLabel;

    // Camera configuration matching your backend
    private static final String[] CAMERA_URLS = {
            "rtsp://admin:G@nd66v@N@192.168.100.23:554/cam/realmonitor?channel=1&subtype=0",
            "rtsp://admin:G@nd66v@N@192.168.100.20/feed",
            "rtsp://admin:G@nd66v@N@192.168.100.21/feed"
    };

    private static final String CURRENT_CAMERA_URL = CAMERA_URLS[0]; // Use camera 1

    private volatile boolean isStreaming = false;
    private ExecutorService executorService;
    private Task<Void> streamTask;
    private FFmpegFrameGrabber grabber;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        executorService = Executors.newSingleThreadExecutor();
        setupEventHandlers();

        // Configure image view
        videoImageView.setFitWidth(640);
        videoImageView.setFitHeight(480);
        videoImageView.setPreserveRatio(true);
    }

    private void setupEventHandlers() {
        playBtn.setOnAction(e -> startStream());
        stopBtn.setOnAction(e -> stopStream());
        reconnectBtn.setOnAction(e -> reconnectStream());

        stopBtn.setDisable(true);
    }

    @FXML
    private void startStream() {
        if (isStreaming) return;

        isStreaming = true;
        playBtn.setDisable(true);
        stopBtn.setDisable(false);
        statusLabel.setText("Status: Connecting...");

        streamTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    streamFromCamera();
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

    private void streamFromCamera() {
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try {
            // Initialize FFmpeg frame grabber
            grabber = new FFmpegFrameGrabber(CURRENT_CAMERA_URL);

            // Set options for better streaming
            grabber.setOption("rtsp_transport", "tcp"); // Use TCP for more reliable connection
            grabber.setOption("buffer_size", "1024000");
            grabber.setOption("max_delay", "0");
            grabber.setImageWidth(640);
            grabber.setImageHeight(480);
            grabber.setFrameRate(30);

            Platform.runLater(() -> statusLabel.setText("Status: Initializing stream..."));

            grabber.start();

            Platform.runLater(() -> statusLabel.setText("Status: Connected - Streaming"));

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
                        }
                    }

                    // Control frame rate (~30 FPS)
                    Thread.sleep(33);

                } catch (Exception e) {
                    if (isStreaming) {
                        System.err.println("Frame grab error: " + e.getMessage());
                        Thread.sleep(100); // Brief pause before retry
                    }
                }
            }

        } catch (Exception e) {
            Platform.runLater(() -> {
                statusLabel.setText("Status: Stream error - " + e.getMessage());
                resetButtons();
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

    @FXML
    private void stopStream() {
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

        resetButtons();
        statusLabel.setText("Status: Stopped");
    }

    @FXML
    private void reconnectStream() {
        stopStream();

        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait 2 seconds before reconnecting
                Platform.runLater(this::startStream);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void resetButtons() {
        Platform.runLater(() -> {
            playBtn.setDisable(false);
            stopBtn.setDisable(true);
        });
    }

    public void cleanup() {
        isStreaming = false;

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