package com.ad9833;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple embedded web server for controlling AD9833 waveform generator
 * Access via http://<raspberry-pi-ip>:8080
 */
public class AD9833WebServer {

    private HttpServer server;
    private AD9833Controller controller;
    private boolean isRunning = false;
    private double currentFrequency = 1000;
    private double currentPhase = 0;
    private String currentWaveform = "SINE";

    public AD9833WebServer(int port) throws Exception {
        controller = new AD9833Controller();
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Serve the main page
        server.createContext("/", new MainPageHandler());

        // API endpoints
        server.createContext("/api/start", new StartHandler());
        server.createContext("/api/stop", new StopHandler());
        server.createContext("/api/set", new SetHandler());
        server.createContext("/api/status", new StatusHandler());

        server.setExecutor(null);
    }

    public void start() {
        server.start();
        System.out.println("AD9833 Web Server started on port " + server.getAddress().getPort());
        System.out.println("Access at: http://localhost:" + server.getAddress().getPort());
    }

    public void stop() {
        if (isRunning) {
            try {
                controller.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        controller.close();
        server.stop(0);
    }

    private class MainPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getHtmlPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class StartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                controller.setFrequency(currentFrequency);
                controller.setPhase(currentPhase);
                controller.setWaveform(AD9833Controller.Waveform.valueOf(currentWaveform));
                isRunning = true;
                sendJson(exchange, "{\"status\":\"ok\",\"running\":true}");
            } catch (Exception e) {
                sendJson(exchange, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class StopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                controller.stop();
                isRunning = false;
                sendJson(exchange, "{\"status\":\"ok\",\"running\":false}");
            } catch (Exception e) {
                sendJson(exchange, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class SetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);

                if (params.containsKey("freq")) {
                    currentFrequency = Double.parseDouble(params.get("freq"));
                    if (isRunning) controller.setFrequency(currentFrequency);
                }
                if (params.containsKey("phase")) {
                    currentPhase = Double.parseDouble(params.get("phase"));
                    if (isRunning) controller.setPhase(currentPhase);
                }
                if (params.containsKey("wave")) {
                    currentWaveform = params.get("wave").toUpperCase();
                    if (isRunning) controller.setWaveform(AD9833Controller.Waveform.valueOf(currentWaveform));
                }

                sendJson(exchange, "{\"status\":\"ok\",\"freq\":" + currentFrequency +
                         ",\"phase\":" + currentPhase + ",\"wave\":\"" + currentWaveform + "\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, "{\"running\":" + isRunning +
                     ",\"freq\":" + currentFrequency +
                     ",\"phase\":" + currentPhase +
                     ",\"wave\":\"" + currentWaveform + "\"}");
        }
    }

    private void sendJson(HttpExchange exchange, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    try {
                        params.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
                    } catch (Exception e) {
                        params.put(pair[0], pair[1]);
                    }
                }
            }
        }
        return params;
    }

    private String getHtmlPage() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AD9833 Waveform Generator</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh;
            color: white;
            padding: 20px;
        }
        .container {
            max-width: 500px;
            margin: 0 auto;
        }
        h1 {
            text-align: center;
            color: #00aaff;
            margin-bottom: 10px;
            font-size: 24px;
        }
        .status {
            text-align: center;
            font-size: 18px;
            margin-bottom: 20px;
        }
        .status-dot {
            display: inline-block;
            width: 12px;
            height: 12px;
            border-radius: 50%;
            margin-right: 8px;
        }
        .status-dot.running { background: #4CAF50; box-shadow: 0 0 10px #4CAF50; }
        .status-dot.stopped { background: #f44336; }
        .freq-display {
            text-align: center;
            font-family: 'Courier New', monospace;
            font-size: 42px;
            color: #00ff88;
            margin: 20px 0;
            text-shadow: 0 0 20px rgba(0,255,136,0.5);
        }
        .control-group {
            background: rgba(255,255,255,0.05);
            border-radius: 12px;
            padding: 15px;
            margin-bottom: 15px;
        }
        .control-group label {
            display: block;
            font-size: 14px;
            color: #888;
            margin-bottom: 8px;
        }
        input[type="range"] {
            width: 100%;
            height: 8px;
            -webkit-appearance: none;
            background: #333;
            border-radius: 4px;
            outline: none;
        }
        input[type="range"]::-webkit-slider-thumb {
            -webkit-appearance: none;
            width: 24px;
            height: 24px;
            background: #00aaff;
            border-radius: 50%;
            cursor: pointer;
        }
        .presets {
            display: flex;
            gap: 8px;
            margin-top: 10px;
            flex-wrap: wrap;
        }
        .preset-btn {
            flex: 1;
            min-width: 60px;
            padding: 10px;
            border: none;
            border-radius: 8px;
            background: #333;
            color: white;
            font-size: 14px;
            cursor: pointer;
            transition: background 0.2s;
        }
        .preset-btn:hover { background: #444; }
        .preset-btn:active { background: #00aaff; }
        .waveform-btns {
            display: flex;
            gap: 10px;
        }
        .wave-btn {
            flex: 1;
            padding: 15px;
            border: 2px solid #333;
            border-radius: 10px;
            background: transparent;
            color: white;
            font-size: 16px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .wave-btn.active {
            border-color: #00aaff;
            background: rgba(0,170,255,0.2);
        }
        .wave-btn:hover { border-color: #00aaff; }
        .main-btns {
            display: flex;
            gap: 15px;
            margin-top: 20px;
        }
        .main-btn {
            flex: 1;
            padding: 18px;
            border: none;
            border-radius: 12px;
            font-size: 20px;
            font-weight: bold;
            cursor: pointer;
            transition: transform 0.1s, box-shadow 0.2s;
        }
        .main-btn:active { transform: scale(0.98); }
        .start-btn {
            background: linear-gradient(135deg, #4CAF50, #45a049);
            color: white;
            box-shadow: 0 4px 15px rgba(76,175,80,0.4);
        }
        .stop-btn {
            background: linear-gradient(135deg, #f44336, #d32f2f);
            color: white;
            box-shadow: 0 4px 15px rgba(244,67,54,0.4);
        }
        .phase-value {
            text-align: right;
            color: #00aaff;
            font-family: monospace;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>AD9833 Waveform Generator</h1>
        <div class="status">
            <span class="status-dot stopped" id="statusDot"></span>
            <span id="statusText">Stopped</span>
        </div>

        <div class="freq-display" id="freqDisplay">1000.00 Hz</div>

        <div class="control-group">
            <label>Frequency</label>
            <input type="range" id="freqSlider" min="0" max="7" step="0.01" value="3">
            <div class="presets">
                <button class="preset-btn" onclick="setFreq(10)">10 Hz</button>
                <button class="preset-btn" onclick="setFreq(100)">100 Hz</button>
                <button class="preset-btn" onclick="setFreq(1000)">1 kHz</button>
                <button class="preset-btn" onclick="setFreq(10000)">10 kHz</button>
                <button class="preset-btn" onclick="setFreq(100000)">100 kHz</button>
                <button class="preset-btn" onclick="setFreq(1000000)">1 MHz</button>
            </div>
        </div>

        <div class="control-group">
            <label>Phase <span class="phase-value" id="phaseValue">0°</span></label>
            <input type="range" id="phaseSlider" min="0" max="360" value="0">
        </div>

        <div class="control-group">
            <label>Waveform</label>
            <div class="waveform-btns">
                <button class="wave-btn active" id="sineBtn" onclick="setWave('SINE')">Sine</button>
                <button class="wave-btn" id="triangleBtn" onclick="setWave('TRIANGLE')">Triangle</button>
                <button class="wave-btn" id="squareBtn" onclick="setWave('SQUARE')">Square</button>
            </div>
        </div>

        <div class="main-btns">
            <button class="main-btn start-btn" onclick="start()">START</button>
            <button class="main-btn stop-btn" onclick="stop()">STOP</button>
        </div>
    </div>

    <script>
        let currentFreq = 1000;
        let currentPhase = 0;
        let currentWave = 'SINE';
        let isRunning = false;

        const freqSlider = document.getElementById('freqSlider');
        const phaseSlider = document.getElementById('phaseSlider');
        const freqDisplay = document.getElementById('freqDisplay');
        const phaseValue = document.getElementById('phaseValue');
        const statusDot = document.getElementById('statusDot');
        const statusText = document.getElementById('statusText');

        function formatFreq(freq) {
            if (freq >= 1000000) return (freq / 1000000).toFixed(2) + ' MHz';
            if (freq >= 1000) return (freq / 1000).toFixed(2) + ' kHz';
            return freq.toFixed(2) + ' Hz';
        }

        freqSlider.addEventListener('input', function() {
            currentFreq = Math.pow(10, this.value);
            freqDisplay.textContent = formatFreq(currentFreq);
            updateGenerator();
        });

        phaseSlider.addEventListener('input', function() {
            currentPhase = parseInt(this.value);
            phaseValue.textContent = currentPhase + '°';
            updateGenerator();
        });

        function setFreq(freq) {
            currentFreq = freq;
            freqSlider.value = Math.log10(freq);
            freqDisplay.textContent = formatFreq(freq);
            updateGenerator();
        }

        function setWave(wave) {
            currentWave = wave;
            document.querySelectorAll('.wave-btn').forEach(b => b.classList.remove('active'));
            document.getElementById(wave.toLowerCase() + 'Btn').classList.add('active');
            updateGenerator();
        }

        function updateGenerator() {
            fetch('/api/set?freq=' + currentFreq + '&phase=' + currentPhase + '&wave=' + currentWave);
        }

        function start() {
            fetch('/api/start').then(r => r.json()).then(data => {
                if (data.running) {
                    isRunning = true;
                    statusDot.className = 'status-dot running';
                    statusText.textContent = 'Running';
                }
            });
        }

        function stop() {
            fetch('/api/stop').then(r => r.json()).then(data => {
                isRunning = false;
                statusDot.className = 'status-dot stopped';
                statusText.textContent = 'Stopped';
            });
        }

        // Get initial status
        fetch('/api/status').then(r => r.json()).then(data => {
            isRunning = data.running;
            currentFreq = data.freq;
            currentPhase = data.phase;
            currentWave = data.wave;

            freqSlider.value = Math.log10(currentFreq);
            freqDisplay.textContent = formatFreq(currentFreq);
            phaseSlider.value = currentPhase;
            phaseValue.textContent = currentPhase + '°';

            document.querySelectorAll('.wave-btn').forEach(b => b.classList.remove('active'));
            document.getElementById(currentWave.toLowerCase() + 'Btn').classList.add('active');

            if (isRunning) {
                statusDot.className = 'status-dot running';
                statusText.textContent = 'Running';
            }
        });
    </script>
</body>
</html>
""";
    }

    public static void main(String[] args) {
        try {
            int port = 8080;
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }

            AD9833WebServer server = new AD9833WebServer(port);
            server.start();

            // Keep running until interrupted
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down...");
                server.stop();
            }));

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
