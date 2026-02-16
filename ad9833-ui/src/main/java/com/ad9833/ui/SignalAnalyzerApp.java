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

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private Label voltageLabel2;
    private Label freqLabel;
    private Label statusLabel;
    private ComboBox<String> channelSelect;
    private ComboBox<String> channelSelect2;
    private Button startButton;
    private Button stopButton;
    private Runnable onBackAction;

    private AnimationTimer timer;
    private boolean isRunning = false;
    private int selectedChannel = 3;  // Default to CH3
    private int selectedChannel2 = 2; // Default to CH2
    private boolean dualChannel = true; // false when CH2 is OFF

    // Waveform buffer (6000 = 3 periods of 2000 coherent points)
    private static final int BUFFER_SIZE = 6000;
    private int samplesPerFrame = 1000;  // Batch sample count - max by default
    private double[] buffer = new double[BUFFER_SIZE];
    private double[] buffer2 = new double[BUFFER_SIZE];
    private int bufferIndex = 0;
    private int bufferIndex2 = 0;
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
    private double dcOffset2 = 0.0;
    private Button acButton;

    // Mode: continuous vs interval
    private boolean intervalMode = false;
    private Button modeContButton;
    private Button modeIntervalButton;

    // Interval mode (coherent averaging)
    private int intervalRawSamples = 10000;  // raw samples to capture
    private static final int COHERENT_TARGET_POINTS = 2000;  // output points per period
    private volatile int intervalSeconds = 5;
    private volatile boolean intervalPaused = false;
    private Thread intervalThread;
    private HBox intervalRow;
    private HBox continuousRow;
    private Button[] intervalBtns;
    private Button pauseBtn;
    private Button exportBtn;
    private volatile int[] lastRawSamples;
    private volatile int[] lastRawSamples2;

    // Continuous coherent mode
    private Thread contCoherentThread;
    private volatile MCP3208Controller.CoherentResult latestCoherentSingle;
    private volatile MCP3208Controller.CoherentResult[] latestCoherentDual;

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

        // Voltage display — two side-by-side labels
        voltageLabel = new Label("CH1: -- Vpp");
        voltageLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        voltageLabel.setTextFill(Color.web("#00ff88"));

        voltageLabel2 = new Label("CH2: -- Vpp");
        voltageLabel2.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        voltageLabel2.setTextFill(Color.web("#00aaff"));

        HBox voltageRow = new HBox(20);
        voltageRow.setAlignment(Pos.CENTER);
        voltageRow.getChildren().addAll(voltageLabel, voltageLabel2);

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

        Label ch1Label = new Label("1:");
        ch1Label.setTextFill(Color.web("#00ff88"));
        ch1Label.setFont(Font.font("System", FontWeight.BOLD, 12));

        channelSelect = new ComboBox<>();
        for (int i = 0; i < 8; i++) {
            channelSelect.getItems().add("CH" + i);
        }
        channelSelect.setValue("CH3");
        channelSelect.setStyle("-fx-font-size: 11px;");
        channelSelect.setPrefWidth(70);
        channelSelect.setOnAction(e -> {
            String selected = channelSelect.getValue();
            selectedChannel = Integer.parseInt(selected.substring(2));
            if (controller != null) {
                controller.setSamplerChannels(selectedChannel, selectedChannel2);
            }
            clearBuffer();
            saveConfig();
        });

        Label ch2Label = new Label("2:");
        ch2Label.setTextFill(Color.web("#00aaff"));
        ch2Label.setFont(Font.font("System", FontWeight.BOLD, 12));

        channelSelect2 = new ComboBox<>();
        channelSelect2.getItems().add("OFF");
        for (int i = 0; i < 8; i++) {
            channelSelect2.getItems().add("CH" + i);
        }
        channelSelect2.setValue("CH2");
        channelSelect2.setStyle("-fx-font-size: 11px;");
        channelSelect2.setPrefWidth(70);
        channelSelect2.setOnAction(e -> {
            String selected = channelSelect2.getValue();
            if ("OFF".equals(selected)) {
                dualChannel = false;
            } else {
                dualChannel = true;
                selectedChannel2 = Integer.parseInt(selected.substring(2));
                if (controller != null) {
                    controller.setSamplerChannels(selectedChannel, selectedChannel2);
                }
            }
            clearBuffer();
            saveConfig();
        });

        // Stack channel selectors vertically
        HBox ch1Row = new HBox(2, ch1Label, channelSelect);
        ch1Row.setAlignment(Pos.CENTER_LEFT);
        HBox ch2Row = new HBox(2, ch2Label, channelSelect2);
        ch2Row.setAlignment(Pos.CENTER_LEFT);
        VBox channelColumn = new VBox(2, ch1Row, ch2Row);
        channelColumn.setAlignment(Pos.CENTER);

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

        controlsRow.getChildren().addAll(channelColumn, startButton, stopButton, modeContButton, modeIntervalButton, autoScaleButton, acButton, minLabel, maxLabel);

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

        Label intSamplesLabel = new Label("Raw:");
        intSamplesLabel.setTextFill(Color.WHITE);
        intSamplesLabel.setFont(Font.font("System", 12));

        Label intSamplesValue = new Label("10000");
        intSamplesValue.setTextFill(Color.web("#00aaff"));
        intSamplesValue.setFont(Font.font("Monospace", FontWeight.BOLD, 12));

        Slider intSamplesSlider = new Slider(5000, 10000, 10000);
        intSamplesSlider.setPrefWidth(100);
        intSamplesSlider.setMajorTickUnit(2500);
        intSamplesSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            intervalRawSamples = newVal.intValue();
            intSamplesValue.setText(String.valueOf(intervalRawSamples));
        });

        exportBtn = new Button("CSV");
        exportBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 10;");
        exportBtn.setOnAction(ev -> exportCsv());

        intervalRow.getChildren().add(intLabel);
        for (Button b : intervalBtns) intervalRow.getChildren().add(b);
        intervalRow.getChildren().addAll(pauseBtn, intSamplesLabel, intSamplesSlider, intSamplesValue, exportBtn);
        intervalRow.setVisible(false);
        intervalRow.setManaged(false);

        root.getChildren().addAll(titleRow, voltageRow, freqLabel, canvasContainer, controlsRow, continuousRow, intervalRow);

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

        // Initialize controller and load saved settings
        initController();
        loadConfig();
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
        channelSelect2.setDisable(true);

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
        latestCoherentSingle = null;
        latestCoherentDual = null;

        // Background thread: continuously calls sampleCoherent (~200-500ms per call)
        contCoherentThread = new Thread(() -> {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    if (dualChannel) {
                        MCP3208Controller.CoherentResult[] cr = controller.sampleCoherentDual(
                                selectedChannel, selectedChannel2,
                                COHERENT_TARGET_POINTS, intervalRawSamples);
                        latestCoherentDual = cr;
                    } else {
                        MCP3208Controller.CoherentResult cr = controller.sampleCoherent(
                                selectedChannel, COHERENT_TARGET_POINTS, intervalRawSamples);
                        latestCoherentSingle = cr;
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        }, "cont-coherent-sampler");
        contCoherentThread.setDaemon(true);
        contCoherentThread.start();

        // AnimationTimer polls for new results
        timer = new AnimationTimer() {
            private Object lastDisplayedRef = null;

            @Override
            public void handle(long now) {
                if (dualChannel) {
                    MCP3208Controller.CoherentResult[] cr = latestCoherentDual;
                    if (cr == null || cr == lastDisplayedRef) return;
                    lastDisplayedRef = cr;
                    if (cr[0].cyclesAveraged > 0) {
                        processAndDisplayCoherent(
                                cr[0].averagedValues, cr[1].averagedValues,
                                cr[0].frequencyHz, cr[0].cyclesAveraged);
                    }
                } else {
                    MCP3208Controller.CoherentResult cr = latestCoherentSingle;
                    if (cr == null || cr == lastDisplayedRef) return;
                    lastDisplayedRef = cr;
                    if (cr.cyclesAveraged > 0) {
                        processAndDisplayCoherent(
                                cr.averagedValues, null,
                                cr.frequencyHz, cr.cyclesAveraged);
                    }
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
                    // Try coherent averaging first, fall back to legacy
                    boolean usedCoherent = false;
                    if (dualChannel) {
                        try {
                            MCP3208Controller.CoherentResult[] cr = controller.sampleCoherentDual(
                                    selectedChannel, selectedChannel2,
                                    COHERENT_TARGET_POINTS, intervalRawSamples);
                            if (cr != null && cr[0].cyclesAveraged > 0) {
                                usedCoherent = true;
                                Platform.runLater(() -> processAndDisplayCoherent(
                                        cr[0].averagedValues, cr[1].averagedValues,
                                        cr[0].frequencyHz, cr[0].cyclesAveraged));
                            }
                        } catch (IllegalStateException e) {
                            // No native helper — fall through to legacy
                        }
                        if (!usedCoherent) {
                            int[][] xy = controller.sampleFastDualChannel(selectedChannel, selectedChannel2, intervalRawSamples);
                            Platform.runLater(() -> processAndDisplaySamples(xy[0], xy[1]));
                        }
                    } else {
                        try {
                            MCP3208Controller.CoherentResult cr = controller.sampleCoherent(
                                    selectedChannel, COHERENT_TARGET_POINTS, intervalRawSamples);
                            if (cr != null && cr.cyclesAveraged > 0) {
                                usedCoherent = true;
                                Platform.runLater(() -> processAndDisplayCoherent(
                                        cr.averagedValues, null,
                                        cr.frequencyHz, cr.cyclesAveraged));
                            }
                        } catch (IllegalStateException e) {
                            // No native helper — fall through to legacy
                        }
                        if (!usedCoherent) {
                            int[] raw = controller.sampleFast(selectedChannel, intervalRawSamples);
                            Platform.runLater(() -> processAndDisplaySamples(raw, null));
                        }
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
     * Uses channel 1 for trigger/cycle detection; applies same offset to both channels.
     */
    private void processAndDisplaySamples(int[] samples, int[] samples2) {
        // Store raw samples for CSV export
        lastRawSamples = samples;
        lastRawSamples2 = samples2;

        boolean hasCh2 = dualChannel && samples2 != null && samples2.length > 0;

        // Convert all raw samples to voltages
        double[] voltages = new double[samples.length];
        double[] voltages2 = hasCh2 ? new double[samples2.length] : null;
        double sum = 0;
        for (int i = 0; i < samples.length; i++) {
            voltages[i] = (samples[i] * 3.3) / 4095.0;
            sum += voltages[i];
        }
        if (hasCh2) {
            for (int i = 0; i < samples2.length; i++) {
                voltages2[i] = (samples2[i] * 3.3) / 4095.0;
            }
        }

        // Pre-compute DC offset (from channel 1 for trigger detection)
        double tempDc = acCoupling ? sum / voltages.length : 0;

        // In interval mode, extract one cycle first (using channel 1 for detection)
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
                    if (hasCh2 && bounds[0] + i < voltages2.length) {
                        buffer2[i] = voltages2[bounds[0] + i];
                    }
                }
                for (int i = count; i < BUFFER_SIZE; i++) {
                    buffer[i] = voltages[bounds[0]];
                    if (hasCh2) {
                        buffer2[i] = (bounds[0] < voltages2.length) ? voltages2[bounds[0]] : 0;
                    }
                }
                bufferIndex = count;
                bufferIndex2 = hasCh2 ? count : 0;
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

        // Find trigger in full sample array (using channel 1)
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

        // Fill display buffers starting from trigger point
        int count = Math.min(BUFFER_SIZE, voltages.length - trigStart);
        for (int i = 0; i < count; i++) {
            buffer[i] = voltages[trigStart + i];
        }
        bufferIndex = count % BUFFER_SIZE;

        if (hasCh2) {
            int count2 = Math.min(BUFFER_SIZE, voltages2.length - trigStart);
            for (int i = 0; i < count2; i++) {
                buffer2[i] = voltages2[trigStart + i];
            }
            bufferIndex2 = count2 % BUFFER_SIZE;
        }

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
     * Process coherent-averaged data: the samples array IS one period (2000 points).
     * No trigger search or cycle extraction needed.
     */
    private void processAndDisplayCoherent(int[] samples, int[] samples2, double freq, int cycles) {
        lastRawSamples = samples;
        lastRawSamples2 = samples2;

        boolean hasCh2 = dualChannel && samples2 != null && samples2.length > 0;

        // Tile 3 periods into buffer for zoom-out view
        int period = samples.length;
        int total = Math.min(BUFFER_SIZE, period * 3);
        for (int i = 0; i < total; i++) {
            buffer[i] = (samples[i % period] * 3.3) / 4095.0;
        }
        bufferIndex = total;

        if (hasCh2) {
            int period2 = samples2.length;
            int total2 = Math.min(BUFFER_SIZE, period2 * 3);
            for (int i = 0; i < total2; i++) {
                buffer2[i] = (samples2[i % period2] * 3.3) / 4095.0;
            }
            bufferIndex2 = total2;
        }

        // Update frequency label with cycle count
        smoothedFreq = freq;
        freqLabel.setText(formatFreq(freq) + " (" + cycles + " cycles)");

        updateDisplay();
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
        if (contCoherentThread != null) {
            contCoherentThread.interrupt();
            contCoherentThread = null;
        }
        if (intervalThread != null) {
            intervalThread.interrupt();
            intervalThread = null;
        }
        statusLabel.setText("● Stopped");
        statusLabel.setTextFill(Color.RED);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        channelSelect.setDisable(false);
        channelSelect2.setDisable(false);
    }

    private void addToBuffer(double voltage) {
        buffer[bufferIndex] = voltage;
        bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
    }

    private void updateDisplay() {
        // Compute Vpp from buffer 1 (AC-coupled values)
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : buffer) {
            double adjusted = v - dcOffset;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }
        if (min != Double.MAX_VALUE && max != -Double.MAX_VALUE) {
            double vpp = max - min;
            voltageLabel.setText(String.format("CH1: %.3f Vpp", vpp));
        }

        // Compute Vpp from buffer 2 (only if dual channel)
        if (dualChannel) {
            double min2 = Double.MAX_VALUE;
            double max2 = -Double.MAX_VALUE;
            for (double v : buffer2) {
                double adjusted = v - dcOffset2;
                if (adjusted < min2) min2 = adjusted;
                if (adjusted > max2) max2 = adjusted;
            }
            if (min2 != Double.MAX_VALUE && max2 != -Double.MAX_VALUE) {
                double vpp2 = max2 - min2;
                voltageLabel2.setText(String.format("CH2: %.3f Vpp", vpp2));
            }
            voltageLabel2.setVisible(true);
        } else {
            voltageLabel2.setVisible(false);
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

    /** Catmull-Rom cubic interpolation: sample buf at fractional index fi (relative to offset). */
    private double cubicSample(double[] buf, int offset, double fi, int n, double dc) {
        int i1 = (int) fi;
        double t = fi - i1;
        int i0 = Math.max(0, i1 - 1);
        int i2 = Math.min(n - 1, i1 + 1);
        int i3 = Math.min(n - 1, i1 + 2);
        double y0 = buf[offset + i0] - dc, y1 = buf[offset + i1] - dc;
        double y2 = buf[offset + i2] - dc, y3 = buf[offset + i3] - dc;
        double a = -0.5*y0 + 1.5*y1 - 1.5*y2 + 0.5*y3;
        double b = y0 - 2.5*y1 + 2*y2 - 0.5*y3;
        double c = -0.5*y0 + 0.5*y2;
        return a*t*t*t + b*t*t + c*t + y1;
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

        // Calculate how many valid samples are in the buffer
        int validCount = (bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;
        int displaySamples;
        if (intervalMode) {
            displaySamples = validCount;  // show entire period
        } else {
            displaySamples = Math.min(validCount, (validCount * zoomPercent) / 100);
        }
        if (displaySamples < 2) displaySamples = 2;

        // Sub-sample X offset from trigger interpolation (computed during pre-alignment)
        double pixelsPerSample = w / displaySamples;
        double xOffset = triggerEnabled ? -triggerFraction * pixelsPerSample : 0;

        // Use cubic interpolation when few samples (e.g. single cycle with ~15 points)
        boolean useInterp = displaySamples < 100;
        int drawSteps = useInterp ? (int) w : displaySamples;

        // Draw channel 1 waveform (green)
        gc.setStroke(Color.web("#00ff88"));
        gc.setLineWidth(2);

        gc.beginPath();
        boolean first = true;

        for (int s = 0; s < drawSteps; s++) {
            double x, voltage;
            if (useInterp) {
                x = s + xOffset;
                double fi = (double) s / w * (displaySamples - 1);
                voltage = cubicSample(buffer, startOffset, fi, displaySamples, dcOffset);
            } else {
                int idx = startOffset + s;
                x = (double) s / displaySamples * w + xOffset;
                voltage = buffer[idx] - dcOffset;
            }
            double normalizedV = (voltage - scaleMin) / range;
            double y = h - (normalizedV * h);
            y = Math.max(0, Math.min(h, y));

            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();

        // Draw channel 2 waveform (cyan) — same Y scale, only if dual channel
        if (dualChannel) {
            gc.setStroke(Color.web("#00aaff"));
            gc.setLineWidth(2);

            gc.beginPath();
            first = true;

            for (int s = 0; s < drawSteps; s++) {
                double x, voltage2;
                if (useInterp) {
                    x = s + xOffset;
                    double fi = (double) s / w * (displaySamples - 1);
                    voltage2 = cubicSample(buffer2, startOffset, fi, displaySamples, dcOffset2);
                } else {
                    int idx = startOffset + s;
                    if (idx >= buffer2.length) break;
                    x = (double) s / displaySamples * w + xOffset;
                    voltage2 = buffer2[idx] - dcOffset2;
                }
                double normalizedV2 = (voltage2 - scaleMin) / range;
                double y2 = h - (normalizedV2 * h);
                y2 = Math.max(0, Math.min(h, y2));

                if (first) {
                    gc.moveTo(x, y2);
                    first = false;
                } else {
                    gc.lineTo(x, y2);
                }
            }
            gc.stroke();
        }
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
        saveConfig();
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
        saveConfig();
    }

    private void toggleAcCoupling() {
        acCoupling = !acCoupling;
        if (acCoupling) {
            acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        } else {
            acButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");
        }
        saveConfig();
    }

    private void updateDcOffset() {
        if (!acCoupling) {
            dcOffset = 0.0;
            dcOffset2 = 0.0;
            return;
        }
        // Calculate average (DC component) of valid buffer samples — independently per channel
        int validSamples = (bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;
        double sum = 0;
        for (int i = 0; i < validSamples; i++) {
            sum += buffer[i];
        }
        dcOffset = sum / validSamples;

        if (dualChannel) {
            int validSamples2 = (bufferIndex2 > 0 && bufferIndex2 < BUFFER_SIZE) ? bufferIndex2 : BUFFER_SIZE;
            double sum2 = 0;
            for (int i = 0; i < validSamples2; i++) {
                sum2 += buffer2[i];
            }
            dcOffset2 = sum2 / validSamples2;
        } else {
            dcOffset2 = 0.0;
        }
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

        // Use min/max from BOTH channels so they share the same Y axis
        int validSamples = (bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;
        for (int i = 0; i < validSamples; i++) {
            double adjusted = buffer[i] - dcOffset;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }

        if (dualChannel) {
            int validSamples2 = (bufferIndex2 > 0 && bufferIndex2 < BUFFER_SIZE) ? bufferIndex2 : BUFFER_SIZE;
            for (int i = 0; i < validSamples2; i++) {
                double adjusted2 = buffer2[i] - dcOffset2;
                if (adjusted2 < min) min = adjusted2;
                if (adjusted2 > max) max = adjusted2;
            }
        }

        if (min != Double.MAX_VALUE && max != Double.MIN_VALUE && max > min) {
            double range = max - min;
            double margin = range * 0.1;

            if (acCoupling) {
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
        boolean wasRunning = isRunning;
        if (wasRunning) {
            stopSampling();
        }
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
        saveConfig();
        if (wasRunning) {
            startSampling();
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
        saveConfig();
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
        Arrays.fill(buffer2, 0);
        bufferIndex = 0;
        bufferIndex2 = 0;
        drawGrid();
    }

    private void exportCsv() {
        int[] raw1 = lastRawSamples;
        int[] raw2 = lastRawSamples2;
        if (raw1 == null || raw1.length == 0) {
            statusLabel.setText("● No data");
            statusLabel.setTextFill(Color.YELLOW);
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = System.getProperty("user.home") + "/signal_" + timestamp + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            boolean hasCh2 = dualChannel && raw2 != null && raw2.length > 0;
            pw.println(hasCh2 ? "index,ch1_raw,ch1_voltage,ch2_raw,ch2_voltage" : "index,ch1_raw,ch1_voltage");
            int len = raw1.length;
            for (int i = 0; i < len; i++) {
                double v1 = (raw1[i] * 3.3) / 4095.0;
                if (hasCh2 && i < raw2.length) {
                    double v2 = (raw2[i] * 3.3) / 4095.0;
                    pw.printf("%d,%d,%.4f,%d,%.4f%n", i, raw1[i], v1, raw2[i], v2);
                } else {
                    pw.printf("%d,%d,%.4f%n", i, raw1[i], v1);
                }
            }
            statusLabel.setText("● Saved: " + filename);
            statusLabel.setTextFill(Color.web("#00aaff"));
        } catch (Exception e) {
            statusLabel.setText("● Export error");
            statusLabel.setTextFill(Color.RED);
        }
    }

    private void loadConfig() {
        // Channel 1
        int ch1 = ConfigPersistence.getInt("sa.ch1", 3);
        channelSelect.setValue("CH" + ch1);

        // Channel 2
        String ch2 = ConfigPersistence.get("sa.ch2", "CH2");
        channelSelect2.setValue(ch2);
        dualChannel = !"OFF".equals(ch2);
        if (dualChannel) {
            try { selectedChannel2 = Integer.parseInt(ch2.substring(2)); } catch (Exception ignored) {}
        }

        // Toggles
        acCoupling = ConfigPersistence.getBool("sa.ac", true);
        acButton.setStyle(acCoupling
            ? "-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");

        autoScale = ConfigPersistence.getBool("sa.auto", true);
        autoScaleButton.setStyle(autoScale
            ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");

        triggerEnabled = ConfigPersistence.getBool("sa.trig", true);
        triggerButton.setStyle(triggerEnabled
            ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 12;");

        // Mode
        boolean savedInterval = ConfigPersistence.getBool("sa.interval", false);
        if (savedInterval != intervalMode) {
            setMode(savedInterval);
        }

        // Interval seconds
        intervalSeconds = ConfigPersistence.getInt("sa.intervalSec", 5);
        selectInterval(intervalSeconds);
    }

    private void saveConfig() {
        ConfigPersistence.put("sa.ch1", selectedChannel);
        ConfigPersistence.put("sa.ch2", dualChannel ? "CH" + selectedChannel2 : "OFF");
        ConfigPersistence.put("sa.ac", acCoupling);
        ConfigPersistence.put("sa.auto", autoScale);
        ConfigPersistence.put("sa.trig", triggerEnabled);
        ConfigPersistence.put("sa.interval", intervalMode);
        ConfigPersistence.put("sa.intervalSec", intervalSeconds);
        ConfigPersistence.save();
    }

    private void shutdown() {
        saveConfig();
        // Don't close the shared controller — other consumers may still use it
        controller = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
