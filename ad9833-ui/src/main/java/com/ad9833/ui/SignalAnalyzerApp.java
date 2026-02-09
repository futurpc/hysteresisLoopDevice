package com.ad9833.ui;

import com.ad9833.MCP3208Controller;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.Arrays;

/**
 * Signal Analyzer UI for MCP3208 ADC
 * Displays real-time waveform visualization
 */
public class SignalAnalyzerApp extends Application {

    private MCP3208Controller controller;
    private Canvas waveformCanvas;
    private GraphicsContext gc;
    private Label voltageLabel;
    private Label statusLabel;
    private ComboBox<String> channelSelect;
    private Button startButton;
    private Button stopButton;
    private Runnable onBackAction;

    private AnimationTimer timer;
    private boolean isRunning = false;
    private int selectedChannel = 1;  // Default to CH1 (CH0 may have connection issues)

    // Waveform buffer
    private static final int BUFFER_SIZE = 400;
    private int samplesPerFrame = 1000;  // Batch sample count - max by default
    private double[] buffer = new double[BUFFER_SIZE];
    private int bufferIndex = 0;

    // Auto-scale
    private boolean autoScale = true;  // Enabled by default
    private double scaleMin = 0.0;
    private double scaleMax = 3.3;

    // Zoom (display percentage of buffer)
    private int zoomPercent = 75;  // Default 75% of buffer
    private Button autoScaleButton;
    private Label samplingLabel;

    // Trigger (rising edge)
    private boolean triggerEnabled = true;  // Enabled by default
    private double triggerLevel = 0.1;  // Slightly above 0 for stable trigger
    private boolean triggerRising = true;  // Rising edge trigger
    private Button triggerButton;

    // AC coupling (DC offset removal)
    private boolean acCoupling = true;  // Enabled by default - center around 0
    private double dcOffset = 0.0;
    private Button acButton;

    /**
     * Called from MainMenuApp to start with a back button
     */
    public void startWithBackButton(Stage stage, Runnable backAction) {
        this.onBackAction = backAction;
        startInternal(stage);
    }

    @Override
    public void start(Stage primaryStage) {
        startInternal(primaryStage);
    }

    private void startInternal(Stage primaryStage) {
        primaryStage.setTitle("Signal Analyzer");

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Title row
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER);

        if (onBackAction != null) {
            Button backBtn = new Button("< MENU");
            backBtn.setStyle("-fx-background-color: #555555; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;");
            backBtn.setOnAction(e -> {
                stopSampling();
                shutdown();
                onBackAction.run();
            });
            titleRow.getChildren().add(backBtn);
        }

        Label titleLabel = new Label("SIGNAL ANALYZER");
        titleLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.web("#00aaff"));

        statusLabel = new Label("● Stopped");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        statusLabel.setTextFill(Color.RED);

        titleRow.getChildren().addAll(titleLabel, statusLabel);

        // Voltage display
        voltageLabel = new Label("0.000 V");
        voltageLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 36));
        voltageLabel.setTextFill(Color.web("#00ff88"));

        // Waveform canvas
        waveformCanvas = new Canvas(760, 250);
        gc = waveformCanvas.getGraphicsContext2D();
        drawGrid();

        // Canvas container with border
        StackPane canvasContainer = new StackPane(waveformCanvas);
        canvasContainer.setStyle("-fx-border-color: #333355; -fx-border-width: 2; -fx-background-color: #0a0a15;");

        // Controls row
        HBox controlsRow = new HBox(20);
        controlsRow.setAlignment(Pos.CENTER);

        // Channel selector
        Label chLabel = new Label("Channel:");
        chLabel.setTextFill(Color.WHITE);
        chLabel.setFont(Font.font("System", 14));

        channelSelect = new ComboBox<>();
        for (int i = 0; i < 8; i++) {
            channelSelect.getItems().add("CH" + i);
        }
        channelSelect.setValue("CH1");
        channelSelect.setStyle("-fx-font-size: 14px;");
        channelSelect.setOnAction(e -> {
            String selected = channelSelect.getValue();
            selectedChannel = Integer.parseInt(selected.substring(2));
            clearBuffer();
        });

        // Start/Stop buttons
        startButton = new Button("START");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 30;");
        startButton.setOnAction(e -> startSampling());

        stopButton = new Button("STOP");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 30;");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopSampling());

        // Auto-scale button (enabled by default)
        autoScaleButton = new Button("AUTO");
        autoScaleButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        autoScaleButton.setOnAction(e -> toggleAutoScale());

        // AC coupling button (DC offset removal)
        acButton = new Button("AC");
        acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        acButton.setOnAction(e -> toggleAcCoupling());

        // Stats labels
        Label minLabel = new Label("Min: --");
        minLabel.setTextFill(Color.web("#888899"));
        minLabel.setId("minLabel");

        Label maxLabel = new Label("Max: --");
        maxLabel.setTextFill(Color.web("#888899"));
        maxLabel.setId("maxLabel");

        controlsRow.getChildren().addAll(chLabel, channelSelect, startButton, stopButton, autoScaleButton, acButton, minLabel, maxLabel);

        // Sampling controls row
        HBox samplingRow = new HBox(15);
        samplingRow.setAlignment(Pos.CENTER);

        Label sampleLabel = new Label("Samples/frame:");
        sampleLabel.setTextFill(Color.WHITE);
        sampleLabel.setFont(Font.font("System", 14));

        Slider samplingSlider = new Slider(10, 1000, 1000);
        samplingSlider.setShowTickLabels(false);
        samplingSlider.setShowTickMarks(true);
        samplingSlider.setMajorTickUnit(500);
        samplingSlider.setPrefWidth(150);

        samplingLabel = new Label("1000");
        samplingLabel.setTextFill(Color.web("#00aaff"));
        samplingLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 14));

        samplingSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            samplesPerFrame = newVal.intValue();
            samplingLabel.setText(String.valueOf(samplesPerFrame));
        });

        // Trigger button (rising edge)
        triggerButton = new Button("TRIG");
        triggerButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        triggerButton.setOnAction(e -> toggleTrigger());

        // Zoom slider (how much of buffer to show)
        Label zoomLabel = new Label("Zoom:");
        zoomLabel.setTextFill(Color.WHITE);
        zoomLabel.setFont(Font.font("System", 12));

        Slider zoomSlider = new Slider(10, 90, 75);
        zoomSlider.setPrefWidth(100);
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            zoomPercent = newVal.intValue();
        });

        samplingRow.getChildren().addAll(sampleLabel, samplingSlider, samplingLabel, triggerButton, zoomLabel, zoomSlider);

        root.getChildren().addAll(titleRow, voltageLabel, canvasContainer, controlsRow, samplingRow);

        Scene scene = new Scene(root, 800, 480);
        scene.setCursor(Cursor.NONE);
        scene.addEventFilter(javafx.scene.input.MouseEvent.ANY, e -> {
            scene.setCursor(Cursor.NONE);
            if (e.getTarget() instanceof javafx.scene.Node) {
                ((javafx.scene.Node) e.getTarget()).setCursor(Cursor.NONE);
            }
        });
        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
        primaryStage.setOnCloseRequest(e -> {
            stopSampling();
            shutdown();
        });
        primaryStage.show();

        // Initialize controller
        initController();
    }

    private void initController() {
        try {
            controller = new MCP3208Controller();
            statusLabel.setText("● Ready");
            statusLabel.setTextFill(Color.YELLOW);
        } catch (Exception e) {
            statusLabel.setText("● Error: " + e.getMessage());
            statusLabel.setTextFill(Color.RED);
            startButton.setDisable(true);
        }
    }

    private void startSampling() {
        if (controller == null) return;

        isRunning = true;
        statusLabel.setText("● Sampling");
        statusLabel.setTextFill(Color.LIME);
        startButton.setDisable(true);
        stopButton.setDisable(false);
        channelSelect.setDisable(true);

        timer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                // Update at ~30Hz, but collect multiple samples per frame
                if (now - lastUpdate >= 33_000_000) {  // ~30fps
                    lastUpdate = now;
                    try {
                        // Batch sample for better waveform resolution
                        int[] samples = controller.sampleFast(selectedChannel, samplesPerFrame);
                        for (int raw : samples) {
                            double voltage = (raw * 3.3) / 4095.0;
                            addToBuffer(voltage);
                        }
                        // Update display with last sample
                        if (samples.length > 0) {
                            double lastVoltage = (samples[samples.length - 1] * 3.3) / 4095.0;
                            updateDisplay(lastVoltage);
                        }
                    } catch (Exception e) {
                        // Ignore read errors during sampling
                    }
                }
            }
        };
        timer.start();
    }

    private void stopSampling() {
        isRunning = false;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        statusLabel.setText("● Stopped");
        statusLabel.setTextFill(Color.RED);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        channelSelect.setDisable(false);
    }

    private void addToBuffer(double voltage) {
        buffer[bufferIndex] = voltage;
        bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
    }

    private void updateDisplay(double voltage) {
        // Update voltage label (show AC-coupled value if enabled)
        double displayVoltage = acCoupling ? (voltage - dcOffset) : voltage;
        voltageLabel.setText(String.format("%+.3f V", displayVoltage));

        // Redraw waveform
        drawWaveform();

        // Update min/max
        updateStats();
    }

    private void drawGrid() {
        double w = waveformCanvas.getWidth();
        double h = waveformCanvas.getHeight();

        gc.setFill(Color.web("#0a0a15"));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web("#222244"));
        gc.setLineWidth(1);

        // Vertical grid lines
        for (int i = 0; i <= 10; i++) {
            double x = i * w / 10;
            gc.strokeLine(x, 0, x, h);
        }

        // Horizontal grid lines (voltage levels)
        for (int i = 0; i <= 6; i++) {
            double y = i * h / 6;
            gc.strokeLine(0, y, w, y);
        }

        // Voltage labels based on scale
        gc.setFill(Color.web("#666688"));
        gc.setFont(Font.font("Monospace", 10));
        double mid = (scaleMax + scaleMin) / 2;
        gc.fillText(String.format("%.2fV", scaleMax), 5, 12);
        gc.fillText(String.format("%.2fV", mid), 5, h / 2 + 4);
        gc.fillText(String.format("%.2fV", scaleMin), 5, h - 4);
    }

    private void drawWaveform() {
        double w = waveformCanvas.getWidth();
        double h = waveformCanvas.getHeight();

        // Update DC offset for AC coupling
        updateDcOffset();

        // Update auto-scale if enabled
        updateAutoScale();

        // Redraw grid
        drawGrid();

        double range = scaleMax - scaleMin;
        if (range <= 0) range = 3.3;  // Fallback

        // Draw trigger level line if enabled
        if (triggerEnabled) {
            gc.setStroke(Color.web("#ff9800"));
            gc.setLineWidth(1);
            gc.setLineDashes(5, 5);
            double trigY = h - ((triggerLevel - scaleMin) / range * h);
            trigY = Math.max(0, Math.min(h, trigY));
            gc.strokeLine(0, trigY, w, trigY);
            gc.setLineDashes(null);
        }

        // Draw zero line if AC coupling enabled
        if (acCoupling) {
            gc.setStroke(Color.web("#444466"));
            gc.setLineWidth(1);
            double zeroY = h - ((0 - scaleMin) / range * h);
            zeroY = Math.max(0, Math.min(h, zeroY));
            gc.strokeLine(0, zeroY, w, zeroY);
        }

        // Find trigger point
        int startOffset = triggerEnabled ? findTriggerPoint() : 0;

        // Calculate how many samples to display based on zoom
        int displaySamples = (BUFFER_SIZE * zoomPercent) / 100;

        // Draw waveform
        gc.setStroke(Color.web("#00ff88"));
        gc.setLineWidth(2);

        gc.beginPath();
        boolean first = true;

        for (int i = 0; i < displaySamples; i++) {
            int idx = (bufferIndex + startOffset + i) % BUFFER_SIZE;
            double x = (double) i / displaySamples * w;
            // Apply AC coupling (subtract DC offset)
            double voltage = buffer[idx] - dcOffset;
            // Scale voltage to screen coordinates
            double normalizedV = (voltage - scaleMin) / range;
            double y = h - (normalizedV * h);
            // Clamp to canvas bounds
            y = Math.max(0, Math.min(h, y));

            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void updateStats() {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        for (double v : buffer) {
            double adjusted = acCoupling ? (v - dcOffset) : v;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }

        // Find the labels in the scene and update them
        Scene scene = waveformCanvas.getScene();
        if (scene != null) {
            Label minLabel = (Label) scene.lookup("#minLabel");
            Label maxLabel = (Label) scene.lookup("#maxLabel");
            if (minLabel != null) {
                minLabel.setText(String.format("Min:%+.2fV", min == Double.MAX_VALUE ? 0 : min));
            }
            if (maxLabel != null) {
                maxLabel.setText(String.format("Max:%+.2fV", max == Double.MIN_VALUE ? 0 : max));
            }
        }
    }

    private void toggleAutoScale() {
        autoScale = !autoScale;
        if (autoScale) {
            autoScaleButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        } else {
            autoScaleButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
            scaleMin = 0.0;
            scaleMax = 3.3;
        }
    }

    private void toggleTrigger() {
        triggerEnabled = !triggerEnabled;
        if (triggerEnabled) {
            triggerButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
            triggerButton.setText("TRIG");
        } else {
            triggerButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
            triggerButton.setText("TRIG");
        }
    }

    private void toggleAcCoupling() {
        acCoupling = !acCoupling;
        if (acCoupling) {
            acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        } else {
            acButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        }
    }

    private void updateDcOffset() {
        if (!acCoupling) {
            dcOffset = 0.0;
            return;
        }
        // Calculate average (DC component) of buffer
        double sum = 0;
        for (double v : buffer) {
            sum += v;
        }
        dcOffset = sum / BUFFER_SIZE;
    }

    private int findTriggerPoint() {
        // Find where signal crosses trigger level (rising edge)
        // Use AC-coupled values for trigger detection
        int searchRange = BUFFER_SIZE - (BUFFER_SIZE * zoomPercent / 100);  // Leave room for display
        for (int i = 1; i < searchRange; i++) {
            int prevIdx = (bufferIndex + i - 1) % BUFFER_SIZE;
            int currIdx = (bufferIndex + i) % BUFFER_SIZE;

            // Apply AC coupling offset to trigger detection
            double prevV = buffer[prevIdx] - dcOffset;
            double currV = buffer[currIdx] - dcOffset;

            if (triggerRising) {
                // Rising edge: previous below trigger, current above trigger
                if (prevV < triggerLevel && currV >= triggerLevel) {
                    return i;
                }
            } else {
                // Falling edge: previous above trigger, current below trigger
                if (prevV > triggerLevel && currV <= triggerLevel) {
                    return i;
                }
            }
        }
        return 0;  // No trigger found, start from beginning
    }

    private void updateAutoScale() {
        if (!autoScale) {
            if (acCoupling) {
                // Default symmetric scale for AC coupling
                scaleMin = -1.65;
                scaleMax = 1.65;
            } else {
                scaleMin = 0.0;
                scaleMax = 3.3;
            }
            return;
        }

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        for (double v : buffer) {
            double adjusted = v - dcOffset;  // Apply AC coupling offset
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }

        if (min != Double.MAX_VALUE && max != Double.MIN_VALUE && max > min) {
            // Add 10% margin
            double range = max - min;
            double margin = range * 0.1;

            if (acCoupling) {
                // Make scale symmetric around 0
                double maxAbs = Math.max(Math.abs(min - margin), Math.abs(max + margin));
                scaleMin = -maxAbs;
                scaleMax = maxAbs;
            } else {
                scaleMin = Math.max(0, min - margin);
                scaleMax = Math.min(3.3, max + margin);
            }
        }
    }

    private void clearBuffer() {
        Arrays.fill(buffer, 0);
        bufferIndex = 0;
        drawGrid();
    }

    private void shutdown() {
        if (controller != null) {
            controller.close();
            controller = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
