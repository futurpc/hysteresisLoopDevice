package com.ad9833.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class WiFiApp extends Application {

    private Runnable onBackAction;
    private Label connectedLabel;
    private Label ipLabel;
    private VBox networkListBox;
    private VBox keyboardPane;
    private TextField passwordField;
    private Label passwordPromptLabel;
    private String connectingSsid;
    private Button refreshButton;
    private Label statusLabel;
    private boolean shiftActive = false;
    private boolean symbolsActive = false;
    private String activeSsid = null;

    public void startWithBackButton(Stage stage, Runnable backAction) {
        this.onBackAction = backAction;
        startInternal(stage);
    }

    @Override
    public void start(Stage primaryStage) {
        startInternal(primaryStage);
    }

    private void startInternal(Stage primaryStage) {
        primaryStage.setTitle("WiFi Settings");

        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Title row
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        if (onBackAction != null) {
            Button backBtn = new Button("< MENU");
            backBtn.setStyle("-fx-background-color: #555555; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;");
            backBtn.setOnAction(e -> onBackAction.run());
            titleRow.getChildren().add(backBtn);
        }

        Label titleLabel = new Label("WIFI SETTINGS");
        titleLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.web("#00BCD4"));

        statusLabel = new Label("");
        statusLabel.setFont(Font.font("System", 14));
        statusLabel.setTextFill(Color.YELLOW);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        titleRow.getChildren().addAll(titleLabel, statusLabel);

        // Current status row
        HBox currentStatus = new HBox(20);
        currentStatus.setAlignment(Pos.CENTER_LEFT);
        currentStatus.setPadding(new Insets(5, 0, 5, 0));

        connectedLabel = new Label("Connected: --");
        connectedLabel.setTextFill(Color.web("#00ff88"));
        connectedLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        ipLabel = new Label("IP: --");
        ipLabel.setTextFill(Color.web("#00aaff"));
        ipLabel.setFont(Font.font("Monospace", 14));

        currentStatus.getChildren().addAll(connectedLabel, ipLabel);

        // Network list
        networkListBox = new VBox(4);
        networkListBox.setPadding(new Insets(5));

        ScrollPane scrollPane = new ScrollPane(networkListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #0a0a15; -fx-background-color: #0a0a15; -fx-border-color: #333355;");
        scrollPane.setPrefHeight(180);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Refresh button
        refreshButton = new Button("REFRESH");
        refreshButton.setStyle(
            "-fx-background-color: #00BCD4;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 30;"
        );
        refreshButton.setOnAction(e -> scanNetworks());

        // On-screen keyboard pane (hidden initially)
        keyboardPane = createKeyboardPane();
        keyboardPane.setVisible(false);
        keyboardPane.setManaged(false);

        root.getChildren().addAll(titleRow, currentStatus, scrollPane, refreshButton, keyboardPane);

        Scene scene = new Scene(root, 800, 480);
        scene.setCursor(Cursor.NONE);
        scene.addEventFilter(MouseEvent.ANY, e -> {
            scene.setCursor(Cursor.NONE);
            if (e.getTarget() instanceof javafx.scene.Node) {
                ((javafx.scene.Node) e.getTarget()).setCursor(Cursor.NONE);
            }
        });
        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
        primaryStage.show();

        // Initial scan
        scanNetworks();
        updateCurrentConnection();
    }

    private void scanNetworks() {
        refreshButton.setDisable(true);
        statusLabel.setText("Scanning...");
        networkListBox.getChildren().clear();

        new Thread(() -> {
            // First, get the currently active WiFi SSID
            String currentSsid = fetchActiveSsid();

            List<String[]> networks = new ArrayList<>();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "dev", "wifi", "list", "--rescan", "yes"
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                List<String> seen = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length >= 3 && !parts[0].isEmpty() && !seen.contains(parts[0])) {
                        seen.add(parts[0]);
                        networks.add(new String[]{parts[0], parts[1], parts[2]});
                    }
                }
                proc.waitFor();
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Scan failed: " + e.getMessage()));
            }

            // Sort: connected network first
            final String connSsid = currentSsid;
            networks.sort((a, b) -> {
                if (a[0].equals(connSsid)) return -1;
                if (b[0].equals(connSsid)) return 1;
                return Integer.parseInt(b[1]) - Integer.parseInt(a[1]);
            });

            Platform.runLater(() -> {
                activeSsid = connSsid;
                statusLabel.setText("");
                refreshButton.setDisable(false);
                for (String[] net : networks) {
                    networkListBox.getChildren().add(createNetworkRow(net[0], net[1], net[2]));
                }
                if (networks.isEmpty()) {
                    Label noNetworks = new Label("No networks found");
                    noNetworks.setTextFill(Color.web("#888899"));
                    noNetworks.setFont(Font.font("System", 14));
                    networkListBox.getChildren().add(noNetworks);
                }
            });
        }).start();
    }

    private String fetchActiveSsid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("nmcli", "-t", "-f", "NAME,DEVICE", "con", "show", "--active");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[1].startsWith("wlan")) {
                    proc.waitFor();
                    return parts[0];
                }
            }
            proc.waitFor();
        } catch (Exception ignored) {
        }
        return null;
    }

    private HBox createNetworkRow(String ssid, String signal, String security) {
        boolean isConnected = ssid.equals(activeSsid);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));
        row.setStyle(isConnected
            ? "-fx-background-color: #1a3a2a; -fx-background-radius: 5; -fx-border-color: #00ff88; -fx-border-radius: 5; -fx-border-width: 1;"
            : "-fx-background-color: #222244; -fx-background-radius: 5;");

        Label nameLabel = new Label(ssid);
        nameLabel.setTextFill(isConnected ? Color.web("#00ff88") : Color.WHITE);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        nameLabel.setPrefWidth(250);

        // Signal strength
        int sig = 0;
        try { sig = Integer.parseInt(signal); } catch (NumberFormatException ignored) {}
        String sigText = sig + "%";
        String sigColor = sig >= 70 ? "#00ff88" : sig >= 40 ? "#ffaa00" : "#ff4444";
        Label signalLabel = new Label(sigText);
        signalLabel.setTextFill(Color.web(sigColor));
        signalLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 14));
        signalLabel.setPrefWidth(60);

        Label secLabel = new Label(security.isEmpty() ? "Open" : security);
        secLabel.setTextFill(Color.web("#888899"));
        secLabel.setFont(Font.font("System", 12));
        secLabel.setPrefWidth(120);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (isConnected) {
            Label connLabel = new Label("CONNECTED");
            connLabel.setTextFill(Color.web("#00ff88"));
            connLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            connLabel.setStyle("-fx-padding: 6 15;");
            row.getChildren().addAll(nameLabel, signalLabel, secLabel, spacer, connLabel);
        } else {
            Button connectBtn = new Button("CONNECT");
            connectBtn.setStyle(
                "-fx-background-color: #4CAF50;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 12px;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 6 15;"
            );
            connectBtn.setOnAction(e -> {
                if (security.isEmpty() || security.equals("--")) {
                    connectToNetwork(ssid, null);
                } else {
                    showKeyboard(ssid);
                }
            });
            row.getChildren().addAll(nameLabel, signalLabel, secLabel, spacer, connectBtn);
        }

        return row;
    }

    private void showKeyboard(String ssid) {
        connectingSsid = ssid;
        passwordPromptLabel.setText("Password for: " + ssid);
        passwordField.clear();
        keyboardPane.setVisible(true);
        keyboardPane.setManaged(true);
        shiftActive = false;
        symbolsActive = false;
    }

    private void hideKeyboard() {
        keyboardPane.setVisible(false);
        keyboardPane.setManaged(false);
        connectingSsid = null;
        passwordField.clear();
    }

    private VBox createKeyboardPane() {
        VBox pane = new VBox(5);
        pane.setPadding(new Insets(8));
        pane.setAlignment(Pos.CENTER);
        pane.setStyle("-fx-background-color: #2a2a3e; -fx-border-color: #00BCD4; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");

        // Password prompt
        passwordPromptLabel = new Label("Password for: ");
        passwordPromptLabel.setTextFill(Color.WHITE);
        passwordPromptLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        // Password field
        passwordField = new TextField();
        passwordField.setPromptText("Enter password");
        passwordField.setStyle("-fx-background-color: #3c3c3c; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 8;");
        passwordField.setPrefWidth(700);
        passwordField.setEditable(false);

        HBox promptRow = new HBox(10);
        promptRow.setAlignment(Pos.CENTER_LEFT);
        promptRow.getChildren().addAll(passwordPromptLabel, passwordField);
        HBox.setHgrow(passwordField, Priority.ALWAYS);

        // Keyboard grid
        VBox keyRows = new VBox(3);
        keyRows.setAlignment(Pos.CENTER);

        String[][] normalRows = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"}
        };

        String[][] symbolRows = {
            {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")"},
            {"-", "_", "=", "+", "[", "]", "{", "}", "|", "\\"},
            {";", ":", "'", "\"", ",", ".", "<", ">", "/"},
            {"~", "`", "?"}
        };

        for (int r = 0; r < normalRows.length; r++) {
            HBox row = new HBox(3);
            row.setAlignment(Pos.CENTER);

            // Add shift key on the last letter row
            if (r == 3) {
                Button shiftBtn = createKeyButton("SHIFT", 70, 40);
                shiftBtn.setOnAction(e -> {
                    shiftActive = !shiftActive;
                    updateKeyLabels(keyRows, normalRows, symbolRows);
                });
                row.getChildren().add(shiftBtn);
            }

            String[] keys = symbolsActive ? symbolRows[r] : normalRows[r];
            for (String key : keys) {
                String display = shiftActive && !symbolsActive ? key.toUpperCase() : key;
                Button btn = createKeyButton(display, 50, 40);
                btn.setOnAction(e -> {
                    String ch = btn.getText();
                    passwordField.setText(passwordField.getText() + ch);
                    passwordField.positionCaret(passwordField.getText().length());
                });
                row.getChildren().add(btn);
            }

            // Add backspace on the last letter row
            if (r == 3) {
                Button bksp = createKeyButton("<X", 70, 40);
                bksp.setOnAction(e -> {
                    String text = passwordField.getText();
                    if (!text.isEmpty()) {
                        passwordField.setText(text.substring(0, text.length() - 1));
                        passwordField.positionCaret(passwordField.getText().length());
                    }
                });
                row.getChildren().add(bksp);
            }

            keyRows.getChildren().add(row);
        }

        // Bottom row: symbols toggle, space, action buttons
        HBox bottomRow = new HBox(5);
        bottomRow.setAlignment(Pos.CENTER);

        Button symBtn = createKeyButton("!@#", 60, 40);
        symBtn.setOnAction(e -> {
            symbolsActive = !symbolsActive;
            shiftActive = false;
            updateKeyLabels(keyRows, normalRows, symbolRows);
        });

        Button spaceBtn = createKeyButton("SPACE", 250, 40);
        spaceBtn.setOnAction(e -> {
            passwordField.setText(passwordField.getText() + " ");
            passwordField.positionCaret(passwordField.getText().length());
        });

        Button cancelBtn = new Button("CANCEL");
        cancelBtn.setStyle(
            "-fx-background-color: #f44336;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 13px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 20;"
        );
        cancelBtn.setOnAction(e -> hideKeyboard());

        Button connectBtn = new Button("CONNECT");
        connectBtn.setStyle(
            "-fx-background-color: #4CAF50;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 13px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 20;"
        );
        connectBtn.setOnAction(e -> {
            if (connectingSsid != null && !passwordField.getText().isEmpty()) {
                connectToNetwork(connectingSsid, passwordField.getText());
            }
        });

        bottomRow.getChildren().addAll(symBtn, spaceBtn, cancelBtn, connectBtn);
        keyRows.getChildren().add(bottomRow);

        pane.getChildren().addAll(promptRow, keyRows);
        return pane;
    }

    private void updateKeyLabels(VBox keyRows, String[][] normalRows, String[][] symbolRows) {
        // Rebuild the keyboard with updated labels
        int childCount = keyRows.getChildren().size();
        // We have 4 key rows + 1 bottom row = 5 children
        for (int r = 0; r < Math.min(4, childCount); r++) {
            if (!(keyRows.getChildren().get(r) instanceof HBox)) continue;
            HBox row = (HBox) keyRows.getChildren().get(r);
            String[] keys = symbolsActive ? symbolRows[r] : normalRows[r];

            int keyIdx = 0;
            for (javafx.scene.Node node : row.getChildren()) {
                if (!(node instanceof Button)) continue;
                Button btn = (Button) node;
                String text = btn.getText();
                // Skip special buttons
                if (text.equals("SHIFT") || text.equals("<X") || text.equals("!@#") ||
                    text.equals("SPACE") || text.equals("CANCEL") || text.equals("CONNECT")) {
                    continue;
                }
                if (keyIdx < keys.length) {
                    String display = shiftActive && !symbolsActive ? keys[keyIdx].toUpperCase() : keys[keyIdx];
                    btn.setText(display);
                }
                keyIdx++;
            }
        }
    }

    private Button createKeyButton(String text, double width, double height) {
        Button btn = new Button(text);
        btn.setPrefSize(width, height);
        btn.setMinSize(width, height);
        btn.setStyle(
            "-fx-background-color: #444466;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 4;"
        );
        return btn;
    }

    private void connectToNetwork(String ssid, String password) {
        hideKeyboard();
        statusLabel.setText("Connecting to " + ssid + "...");
        statusLabel.setTextFill(Color.YELLOW);

        new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("nmcli");
                cmd.add("dev");
                cmd.add("wifi");
                cmd.add("connect");
                cmd.add(ssid);
                if (password != null) {
                    cmd.add("password");
                    cmd.add(password);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
                int exitCode = proc.waitFor();

                if (exitCode == 0 && output.toString().contains("successfully")) {
                    // Enable autoconnect so connection persists across reboots
                    try {
                        new ProcessBuilder("nmcli", "con", "mod", ssid, "connection.autoconnect", "yes")
                            .redirectErrorStream(true).start().waitFor();
                    } catch (Exception ignored) {
                    }

                    Platform.runLater(() -> {
                        statusLabel.setText("Connected to " + ssid);
                        statusLabel.setTextFill(Color.LIME);
                        updateCurrentConnection();
                        scanNetworks();
                    });
                } else {
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed: " + output.toString());
                        statusLabel.setTextFill(Color.RED);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    statusLabel.setTextFill(Color.RED);
                });
            }
        }).start();
    }

    private void updateCurrentConnection() {
        new Thread(() -> {
            String ssid = "--";
            String ip = "--";

            try {
                // Get active connection
                ProcessBuilder pb = new ProcessBuilder("nmcli", "-t", "-f", "NAME,DEVICE", "con", "show", "--active");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length >= 2 && parts[1].startsWith("wlan")) {
                        ssid = parts[0];
                        break;
                    }
                }
                proc.waitFor();

                // Get IP address
                pb = new ProcessBuilder("nmcli", "-t", "-f", "IP4.ADDRESS", "dev", "show", "wlan0");
                pb.redirectErrorStream(true);
                proc = pb.start();
                reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("IP4.ADDRESS")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            ip = parts[1];
                        }
                        break;
                    }
                }
                proc.waitFor();
            } catch (Exception ignored) {
            }

            final String finalSsid = ssid;
            final String finalIp = ip;
            Platform.runLater(() -> {
                connectedLabel.setText("Connected: " + finalSsid);
                ipLabel.setText("IP: " + finalIp);
            });
        }).start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
