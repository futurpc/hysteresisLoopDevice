package com.ad9833.ui;

import com.ad9833.AD9833Controller;
import com.ad9833.AD9833Controller.Waveform;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.Cursor;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class AD9833App extends Application {

    private AD9833Controller controller;
    private Label statusLabel;
    private Label frequencyDisplay;
    private Slider frequencySlider;
    private TextField frequencyInput;
    private Slider phaseSlider;
    private ToggleGroup waveformGroup;
    private Button startButton;
    private Button stopButton;
    private Runnable onBackAction;

    private boolean isRunning = false;

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
        primaryStage.setTitle("AD9833");

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle("-fx-background-color: #2b2b2b;");

        // Title row with status and optional back button
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER);

        if (onBackAction != null) {
            Button backBtn = new Button("< MENU");
            backBtn.setStyle("-fx-background-color: #555555; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;");
            backBtn.setOnAction(e -> {
                // Don't stop the generator - keep it running while switching views
                onBackAction.run();
            });
            titleRow.getChildren().add(backBtn);
        }

        Label titleLabel = new Label("AD9833 Waveform Generator");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.WHITE);

        statusLabel = new Label("● Stopped");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        statusLabel.setTextFill(Color.RED);

        titleRow.getChildren().addAll(titleLabel, statusLabel);

        // Frequency display
        frequencyDisplay = new Label("1000.00 Hz");
        frequencyDisplay.setFont(Font.font("Monospace", FontWeight.BOLD, 48));
        frequencyDisplay.setTextFill(Color.LIME);

        // Frequency controls
        VBox frequencyBox = createFrequencyControls();

        // Phase controls
        HBox phaseBox = createPhaseControls();

        // Waveform and buttons on same row
        HBox bottomRow = new HBox(15);
        bottomRow.setAlignment(Pos.CENTER);
        HBox waveformBox = createWaveformControls();
        HBox buttonBox = createControlButtons();
        bottomRow.getChildren().addAll(waveformBox, buttonBox);

        root.getChildren().addAll(
            titleRow,
            frequencyDisplay,
            frequencyBox,
            phaseBox,
            bottomRow
        );

        // Fullscreen for 7-inch display (800x480)
        Scene scene = new Scene(root, 800, 480);
        scene.setCursor(Cursor.NONE);  // Hide cursor for touchscreen
        scene.addEventFilter(javafx.scene.input.MouseEvent.ANY, e -> {
            scene.setCursor(Cursor.NONE);
            if (e.getTarget() instanceof javafx.scene.Node) {
                ((javafx.scene.Node) e.getTarget()).setCursor(Cursor.NONE);
            }
        });
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> shutdown());
        primaryStage.setMaximized(true);
        primaryStage.show();

        // Initialize controller
        initController();
    }

    private VBox createFrequencyControls() {
        VBox box = new VBox(3);
        box.setAlignment(Pos.CENTER);

        // Frequency slider (logarithmic scale)
        frequencySlider = new Slider(0, 7, 3); // 10^0 to 10^7 Hz
        frequencySlider.setShowTickLabels(false);
        frequencySlider.setShowTickMarks(true);
        frequencySlider.setMajorTickUnit(1);
        frequencySlider.setPrefWidth(700);

        frequencySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double freq = Math.pow(10, newVal.doubleValue());
            frequencyInput.setText(String.format("%.0f", freq));
            frequencyDisplay.setText(formatFrequency(freq));
            if (isRunning) {
                applyFrequency(freq);
            }
        });

        // Input field + presets on same row
        HBox controlRow = new HBox(5);
        controlRow.setAlignment(Pos.CENTER);

        frequencyInput = new TextField("1000");
        frequencyInput.setPrefWidth(100);
        frequencyInput.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: white; -fx-font-size: 14px;");
        frequencyInput.setOnAction(e -> {
            try {
                double freq = Double.parseDouble(frequencyInput.getText());
                frequencySlider.setValue(Math.log10(freq));
                if (isRunning) {
                    applyFrequency(freq);
                }
            } catch (NumberFormatException ex) {
                showError("Invalid frequency");
            }
        });

        Label hzLabel = new Label("Hz");
        hzLabel.setTextFill(Color.WHITE);
        hzLabel.setStyle("-fx-font-size: 14px;");

        // Preset buttons
        String[] presets = {"100", "440", "1k", "10k", "100k", "1M"};
        double[] presetValues = {100, 440, 1000, 10000, 100000, 1000000};

        controlRow.getChildren().addAll(frequencyInput, hzLabel);

        for (int i = 0; i < presets.length; i++) {
            Button btn = new Button(presets[i]);
            btn.setStyle("-fx-font-size: 14px; -fx-padding: 6 12;");
            final double freq = presetValues[i];
            btn.setOnAction(e -> frequencySlider.setValue(Math.log10(freq)));
            controlRow.getChildren().add(btn);
        }

        box.getChildren().addAll(frequencySlider, controlRow);
        return box;
    }

    private HBox createPhaseControls() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER);

        Label label = new Label("Phase:");
        label.setTextFill(Color.WHITE);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        phaseSlider = new Slider(0, 360, 0);
        phaseSlider.setShowTickLabels(false);
        phaseSlider.setShowTickMarks(true);
        phaseSlider.setMajorTickUnit(90);
        phaseSlider.setPrefWidth(550);

        Label phaseValue = new Label("0°");
        phaseValue.setTextFill(Color.WHITE);
        phaseValue.setStyle("-fx-font-size: 14px;");
        phaseValue.setPrefWidth(50);

        phaseSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            phaseValue.setText(String.format("%.0f°", newVal.doubleValue()));
            if (isRunning) {
                applyPhase(newVal.doubleValue());
            }
        });

        box.getChildren().addAll(label, phaseSlider, phaseValue);
        return box;
    }

    private HBox createWaveformControls() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        waveformGroup = new ToggleGroup();

        RadioButton sineBtn = new RadioButton("Sine");
        sineBtn.setToggleGroup(waveformGroup);
        sineBtn.setSelected(true);
        sineBtn.setUserData(Waveform.SINE);
        sineBtn.setTextFill(Color.WHITE);
        sineBtn.setStyle("-fx-font-size: 16px;");

        RadioButton triangleBtn = new RadioButton("Triangle");
        triangleBtn.setToggleGroup(waveformGroup);
        triangleBtn.setUserData(Waveform.TRIANGLE);
        triangleBtn.setTextFill(Color.WHITE);
        triangleBtn.setStyle("-fx-font-size: 16px;");

        RadioButton squareBtn = new RadioButton("Square");
        squareBtn.setToggleGroup(waveformGroup);
        squareBtn.setUserData(Waveform.SQUARE);
        squareBtn.setTextFill(Color.WHITE);
        squareBtn.setStyle("-fx-font-size: 16px;");

        waveformGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && isRunning) {
                applyWaveform((Waveform) newVal.getUserData());
            }
        });

        box.getChildren().addAll(sineBtn, triangleBtn, squareBtn);
        return box;
    }

    private HBox createControlButtons() {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_RIGHT);

        startButton = new Button("START");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 12 35;");
        startButton.setOnAction(e -> startOutput());

        stopButton = new Button("STOP");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 12 35;");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopOutput());

        box.getChildren().addAll(startButton, stopButton);
        return box;
    }

    private void initController() {
        try {
            controller = AD9833Controller.getShared();
            if (controller.isRunning()) {
                // Restore UI to match running state
                isRunning = true;
                double freq = controller.getFrequency();
                double phase = controller.getPhase();
                Waveform waveform = controller.getWaveform();

                // Restore frequency slider/display
                if (freq > 0) {
                    frequencySlider.setValue(Math.log10(freq));
                    frequencyInput.setText(String.format("%.0f", freq));
                    frequencyDisplay.setText(formatFrequency(freq));
                }

                // Restore phase slider
                phaseSlider.setValue(phase);

                // Restore waveform selection
                for (Toggle toggle : waveformGroup.getToggles()) {
                    if (toggle.getUserData() == waveform) {
                        toggle.setSelected(true);
                        break;
                    }
                }

                statusLabel.setText("● Running");
                statusLabel.setTextFill(Color.LIME);
                startButton.setDisable(true);
                stopButton.setDisable(false);
            } else {
                statusLabel.setText("● Ready");
                statusLabel.setTextFill(Color.YELLOW);
            }
        } catch (Exception e) {
            showError("Failed to initialize: " + e.getMessage());
            statusLabel.setText("● Error");
            statusLabel.setTextFill(Color.RED);
            startButton.setDisable(true);
        }
    }

    private void startOutput() {
        try {
            double freq = Math.pow(10, frequencySlider.getValue());
            double phase = phaseSlider.getValue();
            Waveform waveform = (Waveform) waveformGroup.getSelectedToggle().getUserData();

            controller.setFrequency(freq);
            controller.setPhase(phase);
            controller.setWaveform(waveform);

            isRunning = true;
            controller.setRunning(true);
            statusLabel.setText("● Running");
            statusLabel.setTextFill(Color.LIME);
            startButton.setDisable(true);
            stopButton.setDisable(false);

        } catch (Exception e) {
            showError("Failed to start: " + e.getMessage());
        }
    }

    private void stopOutput() {
        try {
            controller.stop();
            isRunning = false;
            statusLabel.setText("● Stopped");
            statusLabel.setTextFill(Color.RED);
            startButton.setDisable(false);
            stopButton.setDisable(true);
        } catch (Exception e) {
            showError("Failed to stop: " + e.getMessage());
        }
    }

    private void applyFrequency(double freq) {
        try {
            controller.setFrequency(freq);
        } catch (Exception e) {
            showError("Failed to set frequency: " + e.getMessage());
        }
    }

    private void applyPhase(double phase) {
        try {
            controller.setPhase(phase);
        } catch (Exception e) {
            showError("Failed to set phase: " + e.getMessage());
        }
    }

    private void applyWaveform(Waveform waveform) {
        try {
            controller.setWaveform(waveform);
        } catch (Exception e) {
            showError("Failed to set waveform: " + e.getMessage());
        }
    }

    private String formatFrequency(double freq) {
        if (freq >= 1_000_000) {
            return String.format("%.2f MHz", freq / 1_000_000);
        } else if (freq >= 1000) {
            return String.format("%.2f kHz", freq / 1000);
        } else {
            return String.format("%.2f Hz", freq);
        }
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void shutdown() {
        // Don't close the shared controller — keep generator running while switching views
        controller = null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
