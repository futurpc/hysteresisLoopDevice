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
    private Label freqLabel;
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
    private int[] lastProcessedSamples = null;  // Skip duplicate frames

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
    private double triggerFraction = 0;  // Sub-sample interpolation offset
    private Button triggerButton;

    // AC coupling (DC offset removal)
    private boolean acCoupling = true;  // Enabled by default - center around 0
    private double dcOffset = 0.0;
    private Button acButton;

    // Mode: continuous vs interval
    private boolean intervalMode = false;
    private Button modeContButton;
    private Button modeIntervalButton;

    // Interval mode
    private static final int INTERVAL_SAMPLES = 2000;
    private volatile int intervalSeconds = 5;
    private volatile boolean intervalPaused = false;
    private Thread intervalThread;
    private HBox intervalRow;
    private HBox continuousRow;
    private Button[] intervalBtns;
    private Button pauseBtn;

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

        VBox root = new VBox(5);
        root.setPadding(new Insets(8));
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
        voltageLabel = new Label("-- Vpp");
        voltageLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 36));
        voltageLabel.setTextFill(Color.web("#00ff88"));

        // Frequency display
        freqLabel = new Label("-- Hz");
        freqLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        freqLabel.setTextFill(Color.web("#ffcc00"));

        // Waveform canvas
        waveformCanvas = new Canvas(760, 220);
        gc = waveformCanvas.getGraphicsContext2D();
        drawGrid();

        // Canvas container with border
        StackPane canvasContainer = new StackPane(waveformCanvas);
        canvasContainer.setStyle("-fx-border-color: #333355; -fx-border-width: 2; -fx-background-color: #0a0a15;");

        // Controls row 1: channel, start/stop, mode
        HBox controlsRow = new HBox(8);
        controlsRow.setAlignment(Pos.CENTER);

        Label chLabel = new Label("CH:");
        chLabel.setTextFill(Color.WHITE);
        chLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        channelSelect = new ComboBox<>();
        for (int i = 0; i < 8; i++) {
            channelSelect.getItems().add("CH" + i);
        }
        channelSelect.setValue("CH1");
        channelSelect.setStyle("-fx-font-size: 13px;");
        channelSelect.setPrefWidth(75);
        channelSelect.setOnAction(e -> {
            String selected = channelSelect.getValue();
            selectedChannel = Integer.parseInt(selected.substring(2));
            if (controller != null) {
                controller.setSamplerChannel(selectedChannel);
            }
            clearBuffer();
        });

        startButton = new Button("START");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8 20;");
        startButton.setOnAction(e -> startSampling());

        stopButton = new Button("STOP");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8 20;");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopSampling());

        modeContButton = new Button("CONT");
        modeContButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 10;");
        modeContButton.setOnAction(e -> setMode(false));

        modeIntervalButton = new Button("INTRVL");
        modeIntervalButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 10;");
        modeIntervalButton.setOnAction(e -> setMode(true));

        autoScaleButton = new Button("AUTO");
        autoScaleButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        autoScaleButton.setOnAction(e -> toggleAutoScale());

        acButton = new Button("AC");
        acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        acButton.setOnAction(e -> toggleAcCoupling());

        Label minLabel = new Label("Min:--");
        minLabel.setTextFill(Color.web("#888899"));
        minLabel.setId("minLabel");

        Label maxLabel = new Label("Max:--");
        maxLabel.setTextFill(Color.web("#888899"));
        maxLabel.setId("maxLabel");

        controlsRow.getChildren().addAll(chLabel, channelSelect, startButton, stopButton, modeContButton, modeIntervalButton, autoScaleButton, acButton, minLabel, maxLabel);

        // Continuous mode row (samples/frame, trigger, zoom)
        continuousRow = new HBox(15);
        continuousRow.setAlignment(Pos.CENTER);

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
            if (controller != null) {
                controller.setSamplerSamples(samplesPerFrame);
            }
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

        continuousRow.getChildren().addAll(sampleLabel, samplingSlider, samplingLabel, triggerButton, zoomLabel, zoomSlider);

        // Interval mode row (interval buttons + pause)
        intervalRow = new HBox(10);
        intervalRow.setAlignment(Pos.CENTER);

        Label intLabel = new Label("Interval:");
        intLabel.setTextFill(Color.WHITE);
        intLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        int[] intervals = {1, 5, 10, 20};
        intervalBtns = new Button[intervals.length];
        for (int i = 0; i < intervals.length; i++) {
            final int secs = intervals[i];
            intervalBtns[i] = new Button(secs + "s");
            intervalBtns[i].setStyle(secs == intervalSeconds
                ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 6 14;"
                : "-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 6 14;");
            intervalBtns[i].setOnAction(ev -> selectInterval(secs));
        }

        pauseBtn = new Button("PAUSE");
        pauseBtn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6 14;");
        pauseBtn.setOnAction(ev -> selectPause());

        intervalRow.getChildren().add(intLabel);
        for (Button b : intervalBtns) intervalRow.getChildren().add(b);
        intervalRow.getChildren().add(pauseBtn);
        intervalRow.setVisible(false);
        intervalRow.setManaged(false);

        root.getChildren().addAll(titleRow, voltageLabel, freqLabel, canvasContainer, controlsRow, continuousRow, intervalRow);

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
            controller = MCP3208Controller.getShared();
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
        startButton.setDisable(true);
        stopButton.setDisable(false);
        channelSelect.setDisable(true);
        modeContButton.setDisable(true);
        modeIntervalButton.setDisable(true);

        if (intervalMode) {
            statusLabel.setText("● Interval");
            statusLabel.setTextFill(Color.LIME);
            startIntervalSampling();
        } else {
            statusLabel.setText("● Sampling");
            statusLabel.setTextFill(Color.LIME);
            startContinuousSampling();
        }
    }

    private void startContinuousSampling() {
        controller.startContinuousSampling(selectedChannel, samplesPerFrame);

        timer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                // Update at ~30Hz
                if (now - lastUpdate >= 33_000_000) {  // ~30fps
                    lastUpdate = now;
                    int[] samples = controller.getLatestSamples();
                    if (samples.length == 0 || samples == lastProcessedSamples) return;
                    lastProcessedSamples = samples;

                    processAndDisplaySamples(samples);
                }
            }
        };
        timer.start();
    }

    private void startIntervalSampling() {
        intervalPaused = false;
        intervalThread = new Thread(() -> {
            while (isRunning) {
                try {
                    int[] samples = controller.sampleFast(selectedChannel, INTERVAL_SAMPLES);

                    if (samples.length > 0) {
                        Platform.runLater(() -> processAndDisplaySamples(samples));
                    }

                    // Wait for interval or pause
                    if (intervalPaused) {
                        Platform.runLater(() -> {
                            statusLabel.setText("● Paused");
                            statusLabel.setTextFill(Color.YELLOW);
                        });
                        while (intervalPaused && isRunning) {
                            Thread.sleep(100);
                        }
                        if (isRunning) {
                            Platform.runLater(() -> {
                                statusLabel.setText("● Interval");
                                statusLabel.setTextFill(Color.LIME);
                            });
                        }
                    } else {
                        Thread.sleep(intervalSeconds * 1000L);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "sa-interval-sampler");
        intervalThread.setDaemon(true);
        intervalThread.start();
    }

    /**
     * Shared processing for both modes: trigger, buffer fill, display update.
     */
    private void processAndDisplaySamples(int[] samples) {
        // Convert all raw samples to voltages
        double[] voltages = new double[samples.length];
        double sum = 0;
        for (int i = 0; i < samples.length; i++) {
            voltages[i] = (samples[i] * 3.3) / 4095.0;
            sum += voltages[i];
        }

        // Pre-compute DC offset
        double tempDc = acCoupling ? sum / voltages.length : 0;

        // In interval mode, extract one cycle first
        if (intervalMode) {
            double[] acVoltages = new double[voltages.length];
            for (int i = 0; i < voltages.length; i++) {
                acVoltages[i] = voltages[i] - tempDc;
            }
            int[] bounds = findCycleBounds(acVoltages);
            if (bounds != null) {
                int cycleLen = bounds[1] - bounds[0];
                int count = Math.min(BUFFER_SIZE, cycleLen);
                for (int i = 0; i < count; i++) {
                    buffer[i] = voltages[bounds[0] + i];
                }
                // Zero-fill rest of buffer so display is clean
                for (int i = count; i < BUFFER_SIZE; i++) {
                    buffer[i] = voltages[bounds[0]];
                }
                bufferIndex = count;
                // Estimate frequency from cycle length and sample duration
                double duration = controller.getLastSampleDurationSeconds();
                if (duration > 0) {
                    double sampleRate = samples.length / duration;
                    double freq = sampleRate / cycleLen;
                    smoothedFreq = freq;
                    freqLabel.setText(formatFreq(freq));
                }
                updateDisplay();
                return;
            }
            // Fallback: no cycle found, display all
        }

        // Find trigger in full sample array
        int trigStart = 0;
        triggerFraction = 0;
        if (triggerEnabled && voltages.length > BUFFER_SIZE) {
            int searchEnd = voltages.length - BUFFER_SIZE;
            for (int i = 1; i < searchEnd; i++) {
                double prev = voltages[i - 1] - tempDc;
                double curr = voltages[i] - tempDc;
                if (prev < triggerLevel && curr >= triggerLevel) {
                    trigStart = i - 1;
                    double denom = curr - prev;
                    triggerFraction = (denom != 0) ? (triggerLevel - prev) / denom : 0;
                    break;
                }
            }
        }

        // Fill display buffer starting from trigger point
        int count = Math.min(BUFFER_SIZE, voltages.length - trigStart);
        for (int i = 0; i < count; i++) {
            buffer[i] = voltages[trigStart + i];
        }
        bufferIndex = count % BUFFER_SIZE;

        // Update display
        updateDisplay();

        // Display frequency
        double measuredFreq = controller.getLatestFrequency();
        if (measuredFreq > 0) {
            smoothedFreq = smoothedFreq == 0 ? measuredFreq : smoothedFreq * 0.7 + measuredFreq * 0.3;
            freqLabel.setText(formatFreq(smoothedFreq));
        } else {
            freqLabel.setText("-- Hz");
        }
    }

    /**
     * Find one complete sine cycle using rising zero-crossings.
     * Uses median period from all crossings to reject noise.
     */
    private int[] findCycleBounds(double[] signal) {
        java.util.List<Integer> crossings = new java.util.ArrayList<>();
        for (int i = 1; i < signal.length; i++) {
            if (signal[i - 1] < 0 && signal[i] >= 0) {
                crossings.add(i);
            }
        }
        if (crossings.size() < 2) return null;

        java.util.List<Integer> spacings = new java.util.ArrayList<>();
        for (int i = 1; i < crossings.size(); i++) {
            spacings.add(crossings.get(i) - crossings.get(i - 1));
        }
        spacings.sort(Integer::compareTo);
        int medianPeriod = spacings.get(spacings.size() / 2);

        if (medianPeriod < 10) return null;

        int start = crossings.get(0);
        int targetEnd = start + medianPeriod;
        int bestEnd = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 1; i < crossings.size(); i++) {
            int dist = Math.abs(crossings.get(i) - targetEnd);
            if (dist < bestDist) {
                bestDist = dist;
                bestEnd = crossings.get(i);
            }
        }
        if (bestEnd <= start) return null;
        return new int[]{start, bestEnd};
    }

    private void stopSampling() {
        isRunning = false;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (intervalThread != null) {
            intervalThread.interrupt();
            intervalThread = null;
        }
        if (controller != null && !intervalMode) {
            controller.stopContinuousSampling();
        }
        statusLabel.setText("● Stopped");
        statusLabel.setTextFill(Color.RED);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        channelSelect.setDisable(false);
        modeContButton.setDisable(false);
        modeIntervalButton.setDisable(false);
    }

    private void addToBuffer(double voltage) {
        buffer[bufferIndex] = voltage;
        bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
    }

    private void updateDisplay() {
        // Compute Vpp from buffer (AC-coupled values)
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : buffer) {
            double adjusted = v - dcOffset;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }
        if (min != Double.MAX_VALUE && max != -Double.MAX_VALUE) {
            double vpp = max - min;
            voltageLabel.setText(String.format("%.3f Vpp", vpp));
        }

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

        // Buffer is pre-aligned to trigger in AnimationTimer — no re-search needed
        int startOffset = 0;

        // Calculate how many samples to display
        // In interval mode, use only the valid cycle samples (stored in bufferIndex)
        int displaySamples;
        if (intervalMode && bufferIndex > 0 && bufferIndex < BUFFER_SIZE) {
            displaySamples = bufferIndex;
        } else {
            displaySamples = (BUFFER_SIZE * zoomPercent) / 100;
        }

        // Sub-sample X offset from trigger interpolation (computed during pre-alignment)
        double pixelsPerSample = w / displaySamples;
        double xOffset = triggerEnabled ? -triggerFraction * pixelsPerSample : 0;

        // Draw waveform
        gc.setStroke(Color.web("#00ff88"));
        gc.setLineWidth(2);

        gc.beginPath();
        boolean first = true;

        for (int i = 0; i < displaySamples; i++) {
            int idx = startOffset + i;
            double x = (double) i / displaySamples * w + xOffset;
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
        // Calculate average (DC component) of valid buffer samples
        int validSamples = (intervalMode && bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;
        double sum = 0;
        for (int i = 0; i < validSamples; i++) {
            sum += buffer[i];
        }
        dcOffset = sum / validSamples;
    }

    private int findTriggerPoint() {
        // Find where signal crosses trigger level (rising edge)
        // Use AC-coupled values for trigger detection
        int searchRange = BUFFER_SIZE - (BUFFER_SIZE * zoomPercent / 100);  // Leave room for display
        triggerFraction = 0;
        for (int i = 1; i < searchRange; i++) {
            int prevIdx = (bufferIndex + i - 1) % BUFFER_SIZE;
            int currIdx = (bufferIndex + i) % BUFFER_SIZE;

            // Apply AC coupling offset to trigger detection
            double prevV = buffer[prevIdx] - dcOffset;
            double currV = buffer[currIdx] - dcOffset;

            if (triggerRising) {
                if (prevV < triggerLevel && currV >= triggerLevel) {
                    // Interpolate sub-sample position
                    double denom = currV - prevV;
                    triggerFraction = (denom != 0) ? (triggerLevel - prevV) / denom : 0;
                    return i;
                }
            } else {
                if (prevV > triggerLevel && currV <= triggerLevel) {
                    double denom = prevV - currV;
                    triggerFraction = (denom != 0) ? (prevV - triggerLevel) / denom : 0;
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

        int validSamples = (intervalMode && bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;
        for (int i = 0; i < validSamples; i++) {
            double adjusted = buffer[i] - dcOffset;
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

    private double smoothedFreq = 0;

    private String formatFreq(double f) {
        if (f >= 1e6) return String.format("%.2f MHz", f / 1e6);
        if (f >= 1e3) return String.format("%.2f kHz", f / 1e3);
        return String.format("%.1f Hz", f);
    }

    private void setMode(boolean interval) {
        if (isRunning) return;
        intervalMode = interval;
        if (intervalMode) {
            modeContButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 8;");
            modeIntervalButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 8;");
            continuousRow.setVisible(false);
            continuousRow.setManaged(false);
            intervalRow.setVisible(true);
            intervalRow.setManaged(true);
        } else {
            modeContButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 8;");
            modeIntervalButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 8;");
            continuousRow.setVisible(true);
            continuousRow.setManaged(true);
            intervalRow.setVisible(false);
            intervalRow.setManaged(false);
        }
    }

    private void selectInterval(int seconds) {
        intervalSeconds = seconds;
        intervalPaused = false;
        int[] intervals = {1, 5, 10, 20};
        for (int i = 0; i < intervalBtns.length; i++) {
            intervalBtns[i].setStyle(intervals[i] == seconds
                ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 6 14;"
                : "-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 6 14;");
        }
        pauseBtn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6 14;");
    }

    private void selectPause() {
        intervalPaused = true;
        for (Button btn : intervalBtns) {
            btn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 6 14;");
        }
        pauseBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 6 14;");
    }

    private void clearBuffer() {
        Arrays.fill(buffer, 0);
        bufferIndex = 0;
        drawGrid();
    }

    private void shutdown() {
        // Don't close the shared controller — other consumers may still use it
        controller = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
