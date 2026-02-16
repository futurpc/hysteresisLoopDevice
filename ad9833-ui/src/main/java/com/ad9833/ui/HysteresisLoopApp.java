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

import java.util.ArrayList;
import java.util.List;

/**
 * Hysteresis Loop X-Y Plot screen.
 * Plots two MCP3208 ADC channels against each other in real-time.
 */
public class HysteresisLoopApp extends Application {

    private MCP3208Controller controller;
    private Canvas xyCanvas;
    private GraphicsContext gc;
    private Label statusLabel;
    private Label xVppLabel;
    private Label yVppLabel;
    private ComboBox<String> xChannelSelect;
    private ComboBox<String> yChannelSelect;
    private Button startButton;
    private Button stopButton;
    private Runnable onBackAction;

    private AnimationTimer timer;
    private boolean isRunning = false;
    private int selectedChannelX = 1;
    private int selectedChannelY = 2;

    private static final int SAMPLE_PAIRS = 500;
    private static final double VREF = 3.3;
    private static final int MAX_VALUE = 4095;

    // Canvas dimensions
    private static final double CANVAS_W = 560;
    private static final double CANVAS_H = 380;

    // Duplicate frame detection
    private int[] lastProcessedX = null;
    private int[] lastProcessedY = null;

    // AC coupling
    private boolean acCoupling = true;
    private Button acButton;

    // Auto-scale
    private boolean autoScale = true;
    private Button autoScaleButton;
    private double scaleMinX = -1.65, scaleMaxX = 1.65;
    private double scaleMinY = -1.65, scaleMaxY = 1.65;

    // Persistence mode
    private boolean persistence = false;
    private Button persistButton;
    private static final int MAX_PERSIST_POINTS = 5000;
    private final List<double[]> persistenceBuffer = new ArrayList<>();  // each: {x, y}

    // Mode: continuous vs interval
    private boolean intervalMode = false;
    private Button modeContButton;
    private Button modeIntervalButton;

    // Interval mode
    private static final int INTERVAL_SAMPLE_PAIRS = 2000;  // oversample to find one cycle
    private static final int COHERENT_TARGET_POINTS = 2000;  // output points per period
    private static final int COHERENT_RAW_SAMPLES = 10000;   // raw samples to capture
    private volatile int intervalSeconds = 5;
    private volatile boolean intervalPaused = false;
    private Thread intervalThread;
    private VBox intervalControls;
    private Button[] intervalBtns;
    private Button pauseBtn;

    // Continuous coherent mode
    private Thread contCoherentThread;
    private volatile MCP3208Controller.CoherentResult[] latestCoherentResult;

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
        primaryStage.setTitle("Hysteresis Loop");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(5));
        root.setStyle("-fx-background-color: #1a1a2e;");

        // === Title bar ===
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setPadding(new Insets(3, 5, 3, 5));

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

        Label titleLabel = new Label("HYSTERESIS LOOP");
        titleLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.web("#ff66aa"));

        statusLabel = new Label("● Stopped");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        statusLabel.setTextFill(Color.RED);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleRow.getChildren().addAll(titleLabel, spacer, statusLabel);
        root.setTop(titleRow);

        // === X-Y Plot Canvas ===
        xyCanvas = new Canvas(CANVAS_W, CANVAS_H);
        gc = xyCanvas.getGraphicsContext2D();
        drawGrid();

        StackPane canvasContainer = new StackPane(xyCanvas);
        canvasContainer.setStyle("-fx-border-color: #333355; -fx-border-width: 2; -fx-background-color: #0a0a15;");
        root.setCenter(canvasContainer);
        BorderPane.setMargin(canvasContainer, new Insets(3, 5, 3, 5));

        // === Right controls panel ===
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(5, 5, 5, 10));
        controls.setAlignment(Pos.TOP_CENTER);
        controls.setPrefWidth(210);

        // Channel selectors — one row each for visibility
        xChannelSelect = new ComboBox<>();
        for (int i = 0; i < 8; i++) xChannelSelect.getItems().add("CH" + i);
        xChannelSelect.setValue("CH1");
        xChannelSelect.setStyle("-fx-font-size: 13px;");
        xChannelSelect.setMaxWidth(Double.MAX_VALUE);
        xChannelSelect.setOnAction(e -> {
            selectedChannelX = Integer.parseInt(xChannelSelect.getValue().substring(2));
            if (controller != null && isRunning) {
                controller.setSamplerChannels(selectedChannelX, selectedChannelY);
            }
            saveConfig();
        });

        yChannelSelect = new ComboBox<>();
        for (int i = 0; i < 8; i++) yChannelSelect.getItems().add("CH" + i);
        yChannelSelect.setValue("CH2");
        yChannelSelect.setStyle("-fx-font-size: 13px;");
        yChannelSelect.setMaxWidth(Double.MAX_VALUE);
        yChannelSelect.setOnAction(e -> {
            selectedChannelY = Integer.parseInt(yChannelSelect.getValue().substring(2));
            if (controller != null && isRunning) {
                controller.setSamplerChannels(selectedChannelX, selectedChannelY);
            }
            saveConfig();
        });

        HBox xChRow = new HBox(5);
        xChRow.setAlignment(Pos.CENTER_LEFT);
        Label xChLabel = new Label("X:");
        xChLabel.setTextFill(Color.WHITE);
        xChLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        xChLabel.setMinWidth(25);
        HBox.setHgrow(xChannelSelect, Priority.ALWAYS);
        xChRow.getChildren().addAll(xChLabel, xChannelSelect);

        HBox yChRow = new HBox(5);
        yChRow.setAlignment(Pos.CENTER_LEFT);
        Label yChLabel = new Label("Y:");
        yChLabel.setTextFill(Color.WHITE);
        yChLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        yChLabel.setMinWidth(25);
        HBox.setHgrow(yChannelSelect, Priority.ALWAYS);
        yChRow.getChildren().addAll(yChLabel, yChannelSelect);

        VBox chRow = new VBox(3);
        chRow.getChildren().addAll(xChRow, yChRow);

        // Mode toggle buttons
        modeContButton = new Button("CONT");
        modeContButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
        modeContButton.setMaxWidth(Double.MAX_VALUE);
        modeContButton.setOnAction(e -> setMode(false));

        modeIntervalButton = new Button("INTERVAL");
        modeIntervalButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
        modeIntervalButton.setMaxWidth(Double.MAX_VALUE);
        modeIntervalButton.setOnAction(e -> setMode(true));

        HBox modeRow = new HBox(5);
        modeRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(modeContButton, Priority.ALWAYS);
        HBox.setHgrow(modeIntervalButton, Priority.ALWAYS);
        modeRow.getChildren().addAll(modeContButton, modeIntervalButton);

        // Start/Stop buttons
        startButton = new Button("START");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 20;");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> startSampling());

        stopButton = new Button("STOP");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 20;");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopSampling());

        HBox startStopRow = new HBox(5);
        startStopRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(startButton, Priority.ALWAYS);
        HBox.setHgrow(stopButton, Priority.ALWAYS);
        startStopRow.getChildren().addAll(startButton, stopButton);

        // Toggle buttons row
        acButton = new Button("AC");
        acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        acButton.setOnAction(e -> toggleAcCoupling());

        autoScaleButton = new Button("AUTO");
        autoScaleButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        autoScaleButton.setOnAction(e -> toggleAutoScale());

        persistButton = new Button("PERSIST");
        persistButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        persistButton.setOnAction(e -> togglePersistence());

        HBox toggleRow = new HBox(5);
        toggleRow.setAlignment(Pos.CENTER);
        toggleRow.getChildren().addAll(acButton, autoScaleButton, persistButton);

        // Clear button
        Button clearButton = new Button("CLEAR");
        clearButton.setStyle("-fx-background-color: #607D8B; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8 20;");
        clearButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setOnAction(e -> clearDisplay());

        // Interval controls (hidden by default)
        intervalControls = new VBox(5);
        intervalControls.setAlignment(Pos.CENTER);

        int[] intervals = {1, 5, 10, 20};
        intervalBtns = new Button[intervals.length];
        HBox intRow1 = new HBox(3);
        intRow1.setAlignment(Pos.CENTER);
        for (int i = 0; i < intervals.length; i++) {
            final int secs = intervals[i];
            intervalBtns[i] = new Button(secs + "s");
            intervalBtns[i].setStyle(secs == intervalSeconds
                ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 10;"
                : "-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 10;");
            intervalBtns[i].setOnAction(e -> selectInterval(secs));
            intRow1.getChildren().add(intervalBtns[i]);
        }

        pauseBtn = new Button("PAUSE");
        pauseBtn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 5 10;");
        pauseBtn.setMaxWidth(Double.MAX_VALUE);
        pauseBtn.setOnAction(e -> selectPause());

        intervalControls.getChildren().addAll(intRow1, pauseBtn);
        intervalControls.setVisible(false);
        intervalControls.setManaged(false);

        // Vpp labels
        xVppLabel = new Label("X: -- Vpp");
        xVppLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 16));
        xVppLabel.setTextFill(Color.web("#00ff88"));

        yVppLabel = new Label("Y: -- Vpp");
        yVppLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 16));
        yVppLabel.setTextFill(Color.web("#00ff88"));

        controls.getChildren().addAll(chRow, modeRow, startStopRow, toggleRow, clearButton, intervalControls, xVppLabel, yVppLabel);
        root.setRight(controls);

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
        xChannelSelect.setDisable(true);
        yChannelSelect.setDisable(true);
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
        latestCoherentResult = null;

        // Background thread: continuously calls sampleCoherentDual (~200-500ms per call)
        contCoherentThread = new Thread(() -> {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    MCP3208Controller.CoherentResult[] cr = controller.sampleCoherentDual(
                            selectedChannelX, selectedChannelY,
                            COHERENT_TARGET_POINTS, COHERENT_RAW_SAMPLES);
                    latestCoherentResult = cr;
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        }, "hyst-cont-coherent-sampler");
        contCoherentThread.setDaemon(true);
        contCoherentThread.start();

        // AnimationTimer polls for new results
        timer = new AnimationTimer() {
            private Object lastDisplayedRef = null;

            @Override
            public void handle(long now) {
                MCP3208Controller.CoherentResult[] cr = latestCoherentResult;
                if (cr == null || cr == lastDisplayedRef) return;
                lastDisplayedRef = cr;
                if (cr[0].cyclesAveraged > 0) {
                    processAndDrawCoherent(cr);
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
                    MCP3208Controller.CoherentResult[] cr = controller.sampleCoherentDual(
                            selectedChannelX, selectedChannelY,
                            COHERENT_TARGET_POINTS, COHERENT_RAW_SAMPLES);
                    if (cr != null && cr[0].cyclesAveraged > 0) {
                        Platform.runLater(() -> processAndDrawCoherent(cr));
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
        }, "hyst-interval-sampler");
        intervalThread.setDaemon(true);
        intervalThread.start();
    }

    /**
     * Shared processing for both modes: convert to voltages, extract cycle if needed, draw.
     */
    private void processAndDraw(int[] samplesX, int[] samplesY, boolean extractCycle) {
        int len = Math.min(samplesX.length, samplesY.length);

        // Convert to voltages
        double[] vx = new double[len];
        double[] vy = new double[len];
        double sumX = 0, sumY = 0;
        for (int i = 0; i < len; i++) {
            vx[i] = (samplesX[i] * VREF) / MAX_VALUE;
            vy[i] = (samplesY[i] * VREF) / MAX_VALUE;
            sumX += vx[i];
            sumY += vy[i];
        }

        // AC coupling: compute DC offsets from full buffer
        double dcX = acCoupling ? sumX / len : 0;
        double dcY = acCoupling ? sumY / len : 0;

        // Apply DC offset
        for (int i = 0; i < len; i++) {
            vx[i] -= dcX;
            vy[i] -= dcY;
        }

        // Extract one cycle if in interval mode
        int start = 0;
        int end = len;
        if (extractCycle) {
            int[] bounds = findCycleBounds(vx);
            if (bounds != null) {
                start = bounds[0];
                end = bounds[1];
            }
        }

        int cycleLen = end - start;
        double[] cx = new double[cycleLen];
        double[] cy = new double[cycleLen];
        System.arraycopy(vx, start, cx, 0, cycleLen);
        System.arraycopy(vy, start, cy, 0, cycleLen);

        // Compute Vpp from extracted data
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < cycleLen; i++) {
            if (cx[i] < minX) minX = cx[i];
            if (cx[i] > maxX) maxX = cx[i];
            if (cy[i] < minY) minY = cy[i];
            if (cy[i] > maxY) maxY = cy[i];
        }

        xVppLabel.setText(String.format("X: %.3f Vpp", maxX - minX));
        yVppLabel.setText(String.format("Y: %.3f Vpp", maxY - minY));

        // Auto-scale
        if (autoScale && minX < maxX && minY < maxY) {
            double rangeX = maxX - minX;
            double marginX = rangeX * 0.1;
            double rangeY = maxY - minY;
            double marginY = rangeY * 0.1;

            if (acCoupling) {
                double absX = Math.max(Math.abs(minX - marginX), Math.abs(maxX + marginX));
                scaleMinX = -absX;
                scaleMaxX = absX;
                double absY = Math.max(Math.abs(minY - marginY), Math.abs(maxY + marginY));
                scaleMinY = -absY;
                scaleMaxY = absY;
            } else {
                scaleMinX = Math.max(0, minX - marginX);
                scaleMaxX = Math.min(VREF, maxX + marginX);
                scaleMinY = Math.max(0, minY - marginY);
                scaleMaxY = Math.min(VREF, maxY + marginY);
            }
        }

        // Persistence: accumulate points
        if (persistence) {
            for (int i = 0; i < cycleLen; i++) {
                persistenceBuffer.add(new double[]{cx[i], cy[i]});
            }
            while (persistenceBuffer.size() > MAX_PERSIST_POINTS) {
                persistenceBuffer.remove(0);
            }
        }

        drawXYPlot(cx, cy, cycleLen);
    }

    /**
     * Process coherent-averaged dual-channel data for X-Y plot.
     * Data IS one period (2000 points) — no cycle extraction needed.
     */
    private void processAndDrawCoherent(MCP3208Controller.CoherentResult[] cr) {
        int[] rawX = cr[0].averagedValues;
        int[] rawY = cr[1].averagedValues;
        int len = Math.min(rawX.length, rawY.length);

        // Convert to voltages
        double[] vx = new double[len];
        double[] vy = new double[len];
        double sumX = 0, sumY = 0;
        for (int i = 0; i < len; i++) {
            vx[i] = (rawX[i] * VREF) / MAX_VALUE;
            vy[i] = (rawY[i] * VREF) / MAX_VALUE;
            sumX += vx[i];
            sumY += vy[i];
        }

        // AC coupling: subtract mean
        double dcX = acCoupling ? sumX / len : 0;
        double dcY = acCoupling ? sumY / len : 0;
        for (int i = 0; i < len; i++) {
            vx[i] -= dcX;
            vy[i] -= dcY;
        }

        // Compute Vpp
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            if (vx[i] < minX) minX = vx[i];
            if (vx[i] > maxX) maxX = vx[i];
            if (vy[i] < minY) minY = vy[i];
            if (vy[i] > maxY) maxY = vy[i];
        }

        xVppLabel.setText(String.format("X: %.3f Vpp", maxX - minX));
        yVppLabel.setText(String.format("Y: %.3f Vpp", maxY - minY));

        // Auto-scale
        if (autoScale && minX < maxX && minY < maxY) {
            double rangeX = maxX - minX;
            double marginX = rangeX * 0.1;
            double rangeY = maxY - minY;
            double marginY = rangeY * 0.1;

            if (acCoupling) {
                double absX = Math.max(Math.abs(minX - marginX), Math.abs(maxX + marginX));
                scaleMinX = -absX;
                scaleMaxX = absX;
                double absY = Math.max(Math.abs(minY - marginY), Math.abs(maxY + marginY));
                scaleMinY = -absY;
                scaleMaxY = absY;
            } else {
                scaleMinX = Math.max(0, minX - marginX);
                scaleMaxX = Math.min(VREF, maxX + marginX);
                scaleMinY = Math.max(0, minY - marginY);
                scaleMaxY = Math.min(VREF, maxY + marginY);
            }
        }

        // Persistence: accumulate points
        if (persistence) {
            for (int i = 0; i < len; i++) {
                persistenceBuffer.add(new double[]{vx[i], vy[i]});
            }
            while (persistenceBuffer.size() > MAX_PERSIST_POINTS) {
                persistenceBuffer.remove(0);
            }
        }

        drawXYPlot(vx, vy, len);
    }

    /**
     * Find one complete sine cycle using rising zero-crossings on the given AC-coupled signal.
     * Uses median period from all crossings to reject noise and pick a clean cycle.
     * @return int[]{startIndex, endIndex} or null if no full cycle found
     */
    private int[] findCycleBounds(double[] signal) {
        // Find all rising zero-crossings
        List<Integer> crossings = new ArrayList<>();
        for (int i = 1; i < signal.length; i++) {
            if (signal[i - 1] < 0 && signal[i] >= 0) {
                crossings.add(i);
            }
        }

        if (crossings.size() < 2) return null;

        // Compute median period from consecutive crossing spacings
        List<Integer> spacings = new ArrayList<>();
        for (int i = 1; i < crossings.size(); i++) {
            spacings.add(crossings.get(i) - crossings.get(i - 1));
        }
        spacings.sort(Integer::compareTo);
        int medianPeriod = spacings.get(spacings.size() / 2);

        // Reject if period is too short (likely noise)
        if (medianPeriod < 10) return null;

        // Find the best crossing pair: start + the crossing closest to one period later
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
        xChannelSelect.setDisable(false);
        yChannelSelect.setDisable(false);
        modeContButton.setDisable(false);
        modeIntervalButton.setDisable(false);
    }

    private void drawGrid() {
        double w = xyCanvas.getWidth();
        double h = xyCanvas.getHeight();

        gc.setFill(Color.web("#0a0a15"));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web("#222244"));
        gc.setLineWidth(1);

        // 8x8 grid
        for (int i = 0; i <= 8; i++) {
            double x = i * w / 8;
            gc.strokeLine(x, 0, x, h);
            double y = i * h / 8;
            gc.strokeLine(0, y, w, y);
        }

        // Center crosshair
        gc.setStroke(Color.web("#334466"));
        gc.setLineWidth(1.5);
        gc.strokeLine(w / 2, 0, w / 2, h);
        gc.strokeLine(0, h / 2, w, h / 2);

        // Axis voltage labels
        gc.setFill(Color.web("#666688"));
        gc.setFont(Font.font("Monospace", 10));
        gc.fillText(String.format("X:%.2fV", scaleMinX), 5, h - 4);
        gc.fillText(String.format("%.2fV", scaleMaxX), w - 55, h - 4);
        gc.fillText(String.format("Y:%.2fV", scaleMaxY), 5, 12);
        gc.fillText(String.format("%.2fV", scaleMinY), 5, h - 16);
    }

    private void drawXYPlot(double[] vx, double[] vy, int len) {
        double w = xyCanvas.getWidth();
        double h = xyCanvas.getHeight();

        // Redraw grid (clears canvas)
        drawGrid();

        double rangeX = scaleMaxX - scaleMinX;
        double rangeY = scaleMaxY - scaleMinY;
        if (rangeX <= 0) rangeX = VREF;
        if (rangeY <= 0) rangeY = VREF;

        gc.setStroke(Color.web("#00ff88"));
        gc.setLineWidth(1.5);

        // Draw persistence buffer first (dimmer)
        if (persistence && !persistenceBuffer.isEmpty()) {
            gc.setStroke(Color.web("#00ff88", 0.3));
            gc.setLineWidth(1);
            gc.beginPath();
            boolean first = true;
            for (double[] pt : persistenceBuffer) {
                double px = ((pt[0] - scaleMinX) / rangeX) * w;
                double py = h - ((pt[1] - scaleMinY) / rangeY) * h;
                px = Math.max(0, Math.min(w, px));
                py = Math.max(0, Math.min(h, py));
                if (first) {
                    gc.moveTo(px, py);
                    first = false;
                } else {
                    gc.lineTo(px, py);
                }
            }
            gc.stroke();

            // Reset for current frame
            gc.setStroke(Color.web("#00ff88"));
            gc.setLineWidth(1.5);
        }

        // Draw current frame
        gc.beginPath();
        boolean first = true;
        for (int i = 0; i < len; i++) {
            double px = ((vx[i] - scaleMinX) / rangeX) * w;
            double py = h - ((vy[i] - scaleMinY) / rangeY) * h;
            px = Math.max(0, Math.min(w, px));
            py = Math.max(0, Math.min(h, py));

            if (first) {
                gc.moveTo(px, py);
                first = false;
            } else {
                gc.lineTo(px, py);
            }
        }
        gc.stroke();
    }

    private void toggleAcCoupling() {
        acCoupling = !acCoupling;
        if (acCoupling) {
            acButton.setStyle("-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        } else {
            acButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
            if (!autoScale) {
                scaleMinX = 0; scaleMaxX = VREF;
                scaleMinY = 0; scaleMaxY = VREF;
            }
        }
        saveConfig();
    }

    private void toggleAutoScale() {
        autoScale = !autoScale;
        if (autoScale) {
            autoScaleButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        } else {
            autoScaleButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
            if (acCoupling) {
                scaleMinX = -1.65; scaleMaxX = 1.65;
                scaleMinY = -1.65; scaleMaxY = 1.65;
            } else {
                scaleMinX = 0; scaleMaxX = VREF;
                scaleMinY = 0; scaleMaxY = VREF;
            }
        }
        saveConfig();
    }

    private void togglePersistence() {
        persistence = !persistence;
        if (persistence) {
            persistButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
        } else {
            persistButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");
            persistenceBuffer.clear();
        }
        saveConfig();
    }

    private void clearDisplay() {
        persistenceBuffer.clear();
        drawGrid();
    }

    private void setMode(boolean interval) {
        if (isRunning) return;  // can't switch while running
        intervalMode = interval;
        if (intervalMode) {
            modeContButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
            modeIntervalButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
            intervalControls.setVisible(true);
            intervalControls.setManaged(true);
        } else {
            modeContButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
            modeIntervalButton.setStyle("-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 6 12;");
            intervalControls.setVisible(false);
            intervalControls.setManaged(false);
        }
        saveConfig();
    }

    private void selectInterval(int seconds) {
        intervalSeconds = seconds;
        intervalPaused = false;
        // Update button styles
        int[] intervals = {1, 5, 10, 20};
        for (int i = 0; i < intervalBtns.length; i++) {
            intervalBtns[i].setStyle(intervals[i] == seconds
                ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 10;"
                : "-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 10;");
        }
        pauseBtn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 5 10;");
        saveConfig();
    }

    private void selectPause() {
        intervalPaused = true;
        // Update button styles
        for (Button btn : intervalBtns) {
            btn.setStyle("-fx-background-color: #444444; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 10;");
        }
        pauseBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 5 10;");
    }

    private void loadConfig() {
        // Channels
        int chX = ConfigPersistence.getInt("hl.chX", 1);
        xChannelSelect.setValue("CH" + chX);

        int chY = ConfigPersistence.getInt("hl.chY", 2);
        yChannelSelect.setValue("CH" + chY);

        // Toggles
        acCoupling = ConfigPersistence.getBool("hl.ac", true);
        acButton.setStyle(acCoupling
            ? "-fx-background-color: #9c27b0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");

        autoScale = ConfigPersistence.getBool("hl.auto", true);
        autoScaleButton.setStyle(autoScale
            ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");

        persistence = ConfigPersistence.getBool("hl.persist", false);
        persistButton.setStyle(persistence
            ? "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;"
            : "-fx-background-color: #666666; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 10;");

        // Mode
        boolean savedInterval = ConfigPersistence.getBool("hl.interval", false);
        if (savedInterval != intervalMode) {
            setMode(savedInterval);
        }

        // Interval seconds
        intervalSeconds = ConfigPersistence.getInt("hl.intervalSec", 5);
        selectInterval(intervalSeconds);
    }

    private void saveConfig() {
        ConfigPersistence.put("hl.chX", selectedChannelX);
        ConfigPersistence.put("hl.chY", selectedChannelY);
        ConfigPersistence.put("hl.ac", acCoupling);
        ConfigPersistence.put("hl.auto", autoScale);
        ConfigPersistence.put("hl.persist", persistence);
        ConfigPersistence.put("hl.interval", intervalMode);
        ConfigPersistence.put("hl.intervalSec", intervalSeconds);
        ConfigPersistence.save();
    }

    private void shutdown() {
        saveConfig();
        controller = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
