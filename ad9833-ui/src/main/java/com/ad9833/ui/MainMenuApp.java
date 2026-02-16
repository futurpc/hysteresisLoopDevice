package com.ad9833.ui;

import com.ad9833.AD9833WebServer;
import com.ad9833.MCP3208Controller;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Main menu for Hysteresis Loop Device
 * Routes to Generator or Signal Analyzer
 */
public class MainMenuApp extends Application {

    private static Stage primaryStage;
    private static Scene menuScene;
    private AD9833WebServer webServer;
    private static final int WEB_PORT = 8080;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        primaryStage.setTitle("Hysteresis Loop Device");

        VBox root = new VBox(5);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Title
        Label titleLabel = new Label("HYSTERESIS LOOP DEVICE");
        titleLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 28));
        titleLabel.setTextFill(Color.web("#00ffaa"));

        // Subtitle
        Label subtitleLabel = new Label("Select Module");
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 16));
        subtitleLabel.setTextFill(Color.web("#888899"));

        // Generator button
        Button generatorBtn = new Button("WAVEFORM GENERATOR");
        generatorBtn.setStyle(
            "-fx-background-color: #4CAF50;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 20px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 12 60;" +
            "-fx-background-radius: 10;"
        );
        generatorBtn.setOnAction(e -> openGenerator());

        Label genDesc = new Label("AD9833 - Sine, Triangle, Square waves");
        genDesc.setTextFill(Color.web("#666677"));
        genDesc.setFont(Font.font("System", 12));

        // Analyzer button
        Button analyzerBtn = new Button("SIGNAL ANALYZER");
        analyzerBtn.setStyle(
            "-fx-background-color: #2196F3;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 20px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 12 60;" +
            "-fx-background-radius: 10;"
        );
        analyzerBtn.setOnAction(e -> openAnalyzer());

        Label anaDesc = new Label("MCP3208 - Read and visualize signals");
        anaDesc.setTextFill(Color.web("#666677"));
        anaDesc.setFont(Font.font("System", 12));

        // Hysteresis Loop button
        Button hysteresisBtn = new Button("HYSTERESIS LOOP");
        hysteresisBtn.setStyle(
            "-fx-background-color: #E91E63;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 20px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 12 60;" +
            "-fx-background-radius: 10;"
        );
        hysteresisBtn.setOnAction(e -> openHysteresisLoop());

        Label hystDesc = new Label("MCP3208 - X-Y plot, B-H curve");
        hystDesc.setTextFill(Color.web("#666677"));
        hystDesc.setFont(Font.font("System", 12));

        // WiFi button
        Button wifiBtn = new Button("WIFI");
        wifiBtn.setStyle(
            "-fx-background-color: #00BCD4;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 20px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 12 60;" +
            "-fx-background-radius: 10;"
        );
        wifiBtn.setOnAction(e -> openWifi());

        Label wifiDesc = new Label("Manage WiFi connections");
        wifiDesc.setTextFill(Color.web("#666677"));
        wifiDesc.setFont(Font.font("System", 12));

        // QR code section
        VBox qrBox = createQrSection();

        // Main content: buttons left, QR right
        VBox buttonsBox = new VBox(5);
        buttonsBox.setAlignment(Pos.CENTER);
        buttonsBox.getChildren().addAll(generatorBtn, genDesc, analyzerBtn, anaDesc, hysteresisBtn, hystDesc, wifiBtn, wifiDesc);

        HBox contentRow = new HBox(20);
        contentRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(buttonsBox, Priority.ALWAYS);
        contentRow.getChildren().addAll(buttonsBox, qrBox);

        root.getChildren().addAll(
            titleLabel,
            subtitleLabel,
            contentRow
        );

        menuScene = new Scene(root, 800, 480);
        menuScene.setCursor(Cursor.NONE);
        menuScene.addEventFilter(javafx.scene.input.MouseEvent.ANY, e -> {
            menuScene.setCursor(Cursor.NONE);
            if (e.getTarget() instanceof javafx.scene.Node) {
                ((javafx.scene.Node) e.getTarget()).setCursor(Cursor.NONE);
            }
        });

        primaryStage.setScene(menuScene);
        primaryStage.setMaximized(true);
        primaryStage.setOnCloseRequest(e -> {
            stopWebServer();
            Platform.exit();
        });
        primaryStage.show();

        // Start web server in background
        startWebServer();
    }

    private void openGenerator() {
        AD9833App generatorApp = new AD9833App();
        generatorApp.startWithBackButton(primaryStage, this::returnToMenu);
    }

    private void openAnalyzer() {
        SignalAnalyzerApp analyzerApp = new SignalAnalyzerApp();
        analyzerApp.startWithBackButton(primaryStage, this::returnToMenu);
    }

    private void openHysteresisLoop() {
        HysteresisLoopApp loopApp = new HysteresisLoopApp();
        loopApp.startWithBackButton(primaryStage, this::returnToMenu);
    }

    private void openWifi() {
        WiFiApp wifiApp = new WiFiApp();
        wifiApp.startWithBackButton(primaryStage, this::returnToMenu);
    }

    public void returnToMenu() {
        primaryStage.setScene(menuScene);
    }

    private void startWebServer() {
        new Thread(() -> {
            try {
                MCP3208Controller sharedAdc = MCP3208Controller.getShared();
                webServer = new AD9833WebServer(WEB_PORT, sharedAdc);
                webServer.start();
                System.out.println("Web server started on port " + WEB_PORT);
            } catch (Exception e) {
                System.err.println("Web server failed to start: " + e.getMessage());
            }
        }, "web-server").start();
    }

    private void stopWebServer() {
        if (webServer != null) {
            try {
                webServer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private VBox createQrSection() {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(5));

        String host = getHostAlias();
        if (host == null) {
            Label noIp = new Label("No network");
            noIp.setTextFill(Color.web("#888899"));
            noIp.setFont(Font.font("System", 12));
            box.getChildren().add(noIp);
            return box;
        }

        String url = "http://" + host + ":" + WEB_PORT;

        // Generate QR code on canvas
        Canvas qrCanvas = new Canvas(140, 140);
        drawQrCode(qrCanvas, url);

        Label urlLabel = new Label(url);
        urlLabel.setTextFill(Color.web("#00aaff"));
        urlLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 11));

        Label scanLabel = new Label("Scan to control");
        scanLabel.setTextFill(Color.web("#666677"));
        scanLabel.setFont(Font.font("System", 10));

        box.getChildren().addAll(qrCanvas, urlLabel, scanLabel);
        return box;
    }

    private String getHostAlias() {
        // Use system hostname + .local (mDNS) — dynamically reflects hostname changes
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isEmpty() && !hostname.equals("localhost")) {
                return hostname.endsWith(".local") ? hostname : hostname + ".local";
            }
        } catch (Exception ignored) {}
        // Fallback to IP if hostname unavailable
        return getLocalIp();
    }

    private void drawQrCode(Canvas canvas, String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 140, 140);
            GraphicsContext gc = canvas.getGraphicsContext2D();

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            double cellW = canvas.getWidth() / width;
            double cellH = canvas.getHeight() / height;

            // White background
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

            // Draw black modules
            gc.setFill(Color.BLACK);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (matrix.get(x, y)) {
                        gc.fillRect(x * cellW, y * cellH, cellW + 0.5, cellH + 0.5);
                    }
                }
            }
        } catch (Exception e) {
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFill(Color.web("#333355"));
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            gc.setFill(Color.web("#888899"));
            gc.setFont(Font.font("System", 12));
            gc.fillText("QR unavailable", 20, 70);
        }
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                // Prefer wlan0
                if (!iface.getName().startsWith("wlan")) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
