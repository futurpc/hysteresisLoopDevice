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
 * Embedded web server for controlling AD9833 waveform generator
 * and viewing MCP3208 signal analyzer.
 * Access via http://<raspberry-pi-ip>:8080
 */
public class AD9833WebServer {

    private HttpServer server;
    private AD9833Controller controller;
    private MCP3208Controller adcController;
    private boolean isRunning = false;
    private double currentFrequency = 1000;
    private double currentPhase = 0;
    private String currentWaveform = "SINE";

    // Analyzer state
    private volatile boolean analyzerRunning = false;
    private int analyzerChannel = 1;
    private int analyzerSamplesPerFrame = 1000;

    public AD9833WebServer(int port) throws Exception {
        controller = new AD9833Controller();
        try {
            adcController = new MCP3208Controller();
        } catch (Exception e) {
            System.err.println("MCP3208 not available: " + e.getMessage());
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Generator pages
        server.createContext("/", new MainPageHandler());
        server.createContext("/api/start", new StartHandler());
        server.createContext("/api/stop", new StopHandler());
        server.createContext("/api/set", new SetHandler());
        server.createContext("/api/status", new StatusHandler());

        // Analyzer pages
        server.createContext("/analyzer", new AnalyzerPageHandler());
        server.createContext("/api/analyzer/stream", new AnalyzerStreamHandler());
        server.createContext("/api/analyzer/start", new AnalyzerStartHandler());
        server.createContext("/api/analyzer/stop", new AnalyzerStopHandler());

        server.setExecutor(null);
    }

    public void start() {
        server.start();
        System.out.println("AD9833 Web Server started on port " + server.getAddress().getPort());
        System.out.println("Access at: http://localhost:" + server.getAddress().getPort());
    }

    public void stop() {
        analyzerRunning = false;
        if (isRunning) {
            try {
                controller.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        controller.close();
        if (adcController != null) {
            adcController.close();
        }
        server.stop(0);
    }

    // ========== Generator Handlers ==========

    private class MainPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getGeneratorHtml();
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

    // ========== Analyzer Handlers ==========

    private class AnalyzerPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getAnalyzerHtml();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class AnalyzerStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            if (params.containsKey("channel")) {
                analyzerChannel = Integer.parseInt(params.get("channel"));
            }
            if (params.containsKey("samples")) {
                analyzerSamplesPerFrame = Integer.parseInt(params.get("samples"));
            }
            analyzerRunning = true;
            sendJson(exchange, "{\"status\":\"ok\",\"running\":true}");
        }
    }

    private class AnalyzerStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            analyzerRunning = false;
            sendJson(exchange, "{\"status\":\"ok\",\"running\":false}");
        }
    }

    private class AnalyzerStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                while (analyzerRunning) {
                    if (adcController == null) {
                        String msg = "data: {\"error\":\"ADC not available\"}\n\n";
                        os.write(msg.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        break;
                    }

                    try {
                        int[] raw = adcController.sampleFast(analyzerChannel, analyzerSamplesPerFrame);
                        StringBuilder sb = new StringBuilder("data: {\"v\":[");
                        for (int i = 0; i < raw.length; i++) {
                            if (i > 0) sb.append(',');
                            // Send voltage with 4 decimal places
                            double voltage = (raw[i] * 3.3) / 4095.0;
                            sb.append(String.format("%.4f", voltage));
                        }
                        sb.append("]}\n\n");
                        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        os.flush();

                        Thread.sleep(33); // ~30fps
                    } catch (IOException e) {
                        break; // Client disconnected
                    } catch (Exception e) {
                        // Continue on sampling errors
                    }
                }
            } catch (IOException ignored) {
                // Client disconnected
            }
        }
    }

    // ========== Utility ==========

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

    // ========== Generator HTML ==========

    private String getGeneratorHtml() {
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
        .container { max-width: 500px; margin: 0 auto; }
        .nav { text-align: center; margin-bottom: 15px; }
        .nav a { color: #00aaff; text-decoration: none; font-size: 14px; padding: 8px 16px; border: 1px solid #333; border-radius: 8px; }
        .nav a:hover { background: rgba(0,170,255,0.2); }
        .nav a.active { background: rgba(0,170,255,0.2); border-color: #00aaff; }
        h1 { text-align: center; color: #00aaff; margin-bottom: 10px; font-size: 24px; }
        .status { text-align: center; font-size: 18px; margin-bottom: 20px; }
        .status-dot { display: inline-block; width: 12px; height: 12px; border-radius: 50%; margin-right: 8px; }
        .status-dot.running { background: #4CAF50; box-shadow: 0 0 10px #4CAF50; }
        .status-dot.stopped { background: #f44336; }
        .freq-display { text-align: center; font-family: 'Courier New', monospace; font-size: 42px; color: #00ff88; margin: 20px 0; text-shadow: 0 0 20px rgba(0,255,136,0.5); }
        .control-group { background: rgba(255,255,255,0.05); border-radius: 12px; padding: 15px; margin-bottom: 15px; }
        .control-group label { display: block; font-size: 14px; color: #888; margin-bottom: 8px; }
        input[type="range"] { width: 100%; height: 8px; -webkit-appearance: none; background: #333; border-radius: 4px; outline: none; }
        input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 24px; height: 24px; background: #00aaff; border-radius: 50%; cursor: pointer; }
        .presets { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
        .preset-btn { flex: 1; min-width: 60px; padding: 10px; border: none; border-radius: 8px; background: #333; color: white; font-size: 14px; cursor: pointer; transition: background 0.2s; }
        .preset-btn:hover { background: #444; }
        .preset-btn:active { background: #00aaff; }
        .waveform-btns { display: flex; gap: 10px; }
        .wave-btn { flex: 1; padding: 15px; border: 2px solid #333; border-radius: 10px; background: transparent; color: white; font-size: 16px; cursor: pointer; transition: all 0.2s; }
        .wave-btn.active { border-color: #00aaff; background: rgba(0,170,255,0.2); }
        .wave-btn:hover { border-color: #00aaff; }
        .main-btns { display: flex; gap: 15px; margin-top: 20px; }
        .main-btn { flex: 1; padding: 18px; border: none; border-radius: 12px; font-size: 20px; font-weight: bold; cursor: pointer; transition: transform 0.1s, box-shadow 0.2s; }
        .main-btn:active { transform: scale(0.98); }
        .start-btn { background: linear-gradient(135deg, #4CAF50, #45a049); color: white; box-shadow: 0 4px 15px rgba(76,175,80,0.4); }
        .stop-btn { background: linear-gradient(135deg, #f44336, #d32f2f); color: white; box-shadow: 0 4px 15px rgba(244,67,54,0.4); }
        .phase-value { text-align: right; color: #00aaff; font-family: monospace; }
    </style>
</head>
<body>
    <div class="container">
        <div class="nav">
            <a href="/" class="active">Generator</a>
            <a href="/analyzer">Analyzer</a>
        </div>
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
            <label>Phase <span class="phase-value" id="phaseValue">0\u00b0</span></label>
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
        let currentFreq = 1000, currentPhase = 0, currentWave = 'SINE', isRunning = false;
        const freqSlider = document.getElementById('freqSlider');
        const phaseSlider = document.getElementById('phaseSlider');
        const freqDisplay = document.getElementById('freqDisplay');
        const phaseValue = document.getElementById('phaseValue');
        const statusDot = document.getElementById('statusDot');
        const statusText = document.getElementById('statusText');

        function formatFreq(f) { if(f>=1e6) return (f/1e6).toFixed(2)+' MHz'; if(f>=1e3) return (f/1e3).toFixed(2)+' kHz'; return f.toFixed(2)+' Hz'; }
        freqSlider.addEventListener('input', function() { currentFreq = Math.pow(10, this.value); freqDisplay.textContent = formatFreq(currentFreq); updateGenerator(); });
        phaseSlider.addEventListener('input', function() { currentPhase = parseInt(this.value); phaseValue.textContent = currentPhase + '\u00b0'; updateGenerator(); });
        function setFreq(f) { currentFreq=f; freqSlider.value=Math.log10(f); freqDisplay.textContent=formatFreq(f); updateGenerator(); }
        function setWave(w) { currentWave=w; document.querySelectorAll('.wave-btn').forEach(b=>b.classList.remove('active')); document.getElementById(w.toLowerCase()+'Btn').classList.add('active'); updateGenerator(); }
        function updateGenerator() { fetch('/api/set?freq='+currentFreq+'&phase='+currentPhase+'&wave='+currentWave); }
        function start() { fetch('/api/start').then(r=>r.json()).then(d=>{ if(d.running){isRunning=true;statusDot.className='status-dot running';statusText.textContent='Running';} }); }
        function stop() { fetch('/api/stop').then(r=>r.json()).then(d=>{ isRunning=false;statusDot.className='status-dot stopped';statusText.textContent='Stopped'; }); }
        fetch('/api/status').then(r=>r.json()).then(d=>{ isRunning=d.running; currentFreq=d.freq; currentPhase=d.phase; currentWave=d.wave; freqSlider.value=Math.log10(currentFreq); freqDisplay.textContent=formatFreq(currentFreq); phaseSlider.value=currentPhase; phaseValue.textContent=currentPhase+'\u00b0'; document.querySelectorAll('.wave-btn').forEach(b=>b.classList.remove('active')); document.getElementById(currentWave.toLowerCase()+'Btn').classList.add('active'); if(isRunning){statusDot.className='status-dot running';statusText.textContent='Running';} });
    </script>
</body>
</html>
""";
    }

    // ========== Analyzer HTML ==========

    private String getAnalyzerHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Signal Analyzer</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh;
            color: white;
            padding: 15px;
        }
        .container { max-width: 500px; margin: 0 auto; }
        .nav { text-align: center; margin-bottom: 10px; }
        .nav a { color: #00aaff; text-decoration: none; font-size: 14px; padding: 8px 16px; border: 1px solid #333; border-radius: 8px; }
        .nav a:hover { background: rgba(0,170,255,0.2); }
        .nav a.active { background: rgba(0,170,255,0.2); border-color: #00aaff; }
        h1 { text-align: center; color: #00aaff; margin-bottom: 8px; font-size: 22px; }
        .status { text-align: center; font-size: 16px; margin-bottom: 10px; }
        .status-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }
        .status-dot.running { background: #4CAF50; box-shadow: 0 0 10px #4CAF50; }
        .status-dot.stopped { background: #f44336; }
        .voltage-display { text-align: center; font-family: 'Courier New', monospace; font-size: 32px; color: #00ff88; margin: 8px 0; text-shadow: 0 0 15px rgba(0,255,136,0.5); }
        .canvas-wrap { background: #0a0a15; border: 2px solid #333355; border-radius: 8px; margin-bottom: 10px; overflow: hidden; }
        canvas { display: block; width: 100%; }
        .controls { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; align-items: center; justify-content: center; }
        .ctrl-group { background: rgba(255,255,255,0.05); border-radius: 8px; padding: 8px 12px; }
        .ctrl-group label { font-size: 11px; color: #888; display: block; margin-bottom: 4px; }
        select { background: #333; color: white; border: 1px solid #555; border-radius: 6px; padding: 6px 10px; font-size: 14px; }
        .toggle-btn { padding: 8px 14px; border: 2px solid #333; border-radius: 8px; background: transparent; color: #888; font-size: 13px; font-weight: bold; cursor: pointer; transition: all 0.2s; }
        .toggle-btn.active { border-color: #ff9800; color: #ff9800; background: rgba(255,152,0,0.15); }
        .toggle-btn.active-ac { border-color: #9c27b0; color: #9c27b0; background: rgba(156,39,176,0.15); }
        input[type="range"] { width: 100%; height: 6px; -webkit-appearance: none; background: #333; border-radius: 3px; outline: none; }
        input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 20px; height: 20px; background: #00aaff; border-radius: 50%; }
        .main-btns { display: flex; gap: 10px; margin-bottom: 10px; }
        .main-btn { flex: 1; padding: 14px; border: none; border-radius: 10px; font-size: 18px; font-weight: bold; cursor: pointer; }
        .main-btn:active { transform: scale(0.98); }
        .start-btn { background: linear-gradient(135deg, #4CAF50, #45a049); color: white; }
        .stop-btn { background: linear-gradient(135deg, #f44336, #d32f2f); color: white; }
        .stats { display: flex; gap: 15px; justify-content: center; font-family: monospace; font-size: 13px; color: #888899; }
        .slider-row { display: flex; gap: 10px; align-items: center; }
        .slider-row label { font-size: 11px; color: #888; white-space: nowrap; }
        .slider-row input[type="range"] { flex: 1; }
        .slider-row .val { font-family: monospace; color: #00aaff; font-size: 13px; min-width: 40px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="nav">
            <a href="/">Generator</a>
            <a href="/analyzer" class="active">Analyzer</a>
        </div>
        <h1>Signal Analyzer</h1>
        <div class="status">
            <span class="status-dot stopped" id="statusDot"></span>
            <span id="statusText">Stopped</span>
        </div>
        <div class="voltage-display" id="voltageDisplay">0.000 V</div>
        <div class="canvas-wrap">
            <canvas id="scope" width="760" height="300"></canvas>
        </div>
        <div class="main-btns">
            <button class="main-btn start-btn" onclick="startAnalyzer()">START</button>
            <button class="main-btn stop-btn" onclick="stopAnalyzer()">STOP</button>
        </div>
        <div class="controls">
            <div class="ctrl-group">
                <label>Channel</label>
                <select id="channelSel" onchange="updateSettings()">
                    <option value="0">CH0</option>
                    <option value="1" selected>CH1</option>
                    <option value="2">CH2</option>
                    <option value="3">CH3</option>
                    <option value="4">CH4</option>
                    <option value="5">CH5</option>
                    <option value="6">CH6</option>
                    <option value="7">CH7</option>
                </select>
            </div>
            <button class="toggle-btn active" id="autoBtn" onclick="toggleAuto()">AUTO</button>
            <button class="toggle-btn active-ac" id="acBtn" onclick="toggleAC()">AC</button>
            <button class="toggle-btn active" id="trigBtn" onclick="toggleTrig()">TRIG</button>
        </div>
        <div class="ctrl-group" style="margin-bottom:8px">
            <div class="slider-row">
                <label>Samples</label>
                <input type="range" id="samplesSlider" min="10" max="1000" value="1000" oninput="samplesVal.textContent=this.value">
                <span class="val" id="samplesVal">1000</span>
            </div>
        </div>
        <div class="ctrl-group" style="margin-bottom:8px">
            <div class="slider-row">
                <label>Zoom</label>
                <input type="range" id="zoomSlider" min="10" max="90" value="75" oninput="zoomVal.textContent=this.value+'%'">
                <span class="val" id="zoomVal">75%</span>
            </div>
        </div>
        <div class="stats">
            <span id="minStat">Min: --</span>
            <span id="maxStat">Max: --</span>
        </div>
    </div>
    <script>
        const canvas = document.getElementById('scope');
        const ctx = canvas.getContext('2d');
        const BUFFER_SIZE = 400;
        let buffer = new Float64Array(BUFFER_SIZE);
        let bufferIndex = 0;
        let eventSource = null;
        let isRunning = false;

        // Settings
        let autoScale = true;
        let acCoupling = true;
        let triggerEnabled = true;
        let triggerLevel = 0.1;
        let scaleMin = -1.65, scaleMax = 1.65;
        let dcOffset = 0;

        function toggleAuto() {
            autoScale = !autoScale;
            document.getElementById('autoBtn').className = autoScale ? 'toggle-btn active' : 'toggle-btn';
            if (!autoScale) { scaleMin = acCoupling ? -1.65 : 0; scaleMax = acCoupling ? 1.65 : 3.3; }
        }
        function toggleAC() {
            acCoupling = !acCoupling;
            document.getElementById('acBtn').className = acCoupling ? 'toggle-btn active-ac' : 'toggle-btn';
        }
        function toggleTrig() {
            triggerEnabled = !triggerEnabled;
            document.getElementById('trigBtn').className = triggerEnabled ? 'toggle-btn active' : 'toggle-btn';
        }
        function updateSettings() {
            buffer.fill(0);
            bufferIndex = 0;
        }

        function startAnalyzer() {
            if (isRunning) return;
            let ch = document.getElementById('channelSel').value;
            let samples = document.getElementById('samplesSlider').value;
            fetch('/api/analyzer/start?channel=' + ch + '&samples=' + samples);
            isRunning = true;
            document.getElementById('statusDot').className = 'status-dot running';
            document.getElementById('statusText').textContent = 'Sampling';

            eventSource = new EventSource('/api/analyzer/stream');
            eventSource.onmessage = function(e) {
                let data = JSON.parse(e.data);
                if (data.error) { stopAnalyzer(); return; }
                let voltages = data.v;
                for (let i = 0; i < voltages.length; i++) {
                    buffer[bufferIndex] = voltages[i];
                    bufferIndex = (bufferIndex + 1) % BUFFER_SIZE;
                }
                // Update voltage display with last sample
                if (voltages.length > 0) {
                    let last = voltages[voltages.length - 1];
                    let display = acCoupling ? (last - dcOffset) : last;
                    document.getElementById('voltageDisplay').textContent = (display >= 0 ? '+' : '') + display.toFixed(3) + ' V';
                }
            };
            eventSource.onerror = function() { stopAnalyzer(); };
            requestAnimationFrame(drawLoop);
        }

        function stopAnalyzer() {
            fetch('/api/analyzer/stop');
            isRunning = false;
            if (eventSource) { eventSource.close(); eventSource = null; }
            document.getElementById('statusDot').className = 'status-dot stopped';
            document.getElementById('statusText').textContent = 'Stopped';
        }

        function drawLoop() {
            if (!isRunning) return;
            drawWaveform();
            requestAnimationFrame(drawLoop);
        }

        function drawWaveform() {
            let w = canvas.width, h = canvas.height;

            // Calculate DC offset
            if (acCoupling) {
                let sum = 0;
                for (let i = 0; i < BUFFER_SIZE; i++) sum += buffer[i];
                dcOffset = sum / BUFFER_SIZE;
            } else {
                dcOffset = 0;
            }

            // Auto-scale
            if (autoScale) {
                let min = Infinity, max = -Infinity;
                for (let i = 0; i < BUFFER_SIZE; i++) {
                    let v = buffer[i] - dcOffset;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                if (min !== Infinity && max !== -Infinity && max > min) {
                    let range = max - min;
                    let margin = range * 0.1;
                    if (acCoupling) {
                        let maxAbs = Math.max(Math.abs(min - margin), Math.abs(max + margin));
                        scaleMin = -maxAbs;
                        scaleMax = maxAbs;
                    } else {
                        scaleMin = Math.max(0, min - margin);
                        scaleMax = Math.min(3.3, max + margin);
                    }
                }
            }

            let range = scaleMax - scaleMin;
            if (range <= 0) range = 3.3;

            // Clear
            ctx.fillStyle = '#0a0a15';
            ctx.fillRect(0, 0, w, h);

            // Grid
            ctx.strokeStyle = '#222244';
            ctx.lineWidth = 1;
            for (let i = 0; i <= 10; i++) { let x = i * w / 10; ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
            for (let i = 0; i <= 6; i++) { let y = i * h / 6; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }

            // Scale labels
            ctx.fillStyle = '#666688';
            ctx.font = '11px monospace';
            let mid = (scaleMax + scaleMin) / 2;
            ctx.fillText(scaleMax.toFixed(2) + 'V', 5, 14);
            ctx.fillText(mid.toFixed(2) + 'V', 5, h / 2 + 4);
            ctx.fillText(scaleMin.toFixed(2) + 'V', 5, h - 4);

            // Trigger level line
            if (triggerEnabled) {
                ctx.strokeStyle = '#ff9800';
                ctx.lineWidth = 1;
                ctx.setLineDash([5, 5]);
                let trigY = h - ((triggerLevel - scaleMin) / range * h);
                trigY = Math.max(0, Math.min(h, trigY));
                ctx.beginPath(); ctx.moveTo(0, trigY); ctx.lineTo(w, trigY); ctx.stroke();
                ctx.setLineDash([]);
            }

            // Zero line (AC coupling)
            if (acCoupling) {
                ctx.strokeStyle = '#444466';
                ctx.lineWidth = 1;
                let zeroY = h - ((0 - scaleMin) / range * h);
                zeroY = Math.max(0, Math.min(h, zeroY));
                ctx.beginPath(); ctx.moveTo(0, zeroY); ctx.lineTo(w, zeroY); ctx.stroke();
            }

            // Find trigger point
            let zoomPercent = parseInt(document.getElementById('zoomSlider').value);
            let displaySamples = Math.floor(BUFFER_SIZE * zoomPercent / 100);
            let startOffset = 0;

            if (triggerEnabled) {
                let searchRange = BUFFER_SIZE - displaySamples;
                for (let i = 1; i < searchRange; i++) {
                    let prevIdx = (bufferIndex + i - 1) % BUFFER_SIZE;
                    let currIdx = (bufferIndex + i) % BUFFER_SIZE;
                    let prevV = buffer[prevIdx] - dcOffset;
                    let currV = buffer[currIdx] - dcOffset;
                    if (prevV < triggerLevel && currV >= triggerLevel) {
                        startOffset = i;
                        break;
                    }
                }
            }

            // Draw waveform
            ctx.strokeStyle = '#00ff88';
            ctx.lineWidth = 2;
            ctx.beginPath();
            let minV = Infinity, maxV = -Infinity;

            for (let i = 0; i < displaySamples; i++) {
                let idx = (bufferIndex + startOffset + i) % BUFFER_SIZE;
                let x = i / displaySamples * w;
                let voltage = buffer[idx] - dcOffset;
                if (voltage < minV) minV = voltage;
                if (voltage > maxV) maxV = voltage;
                let normalized = (voltage - scaleMin) / range;
                let y = h - (normalized * h);
                y = Math.max(0, Math.min(h, y));
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            }
            ctx.stroke();

            // Update stats
            document.getElementById('minStat').textContent = 'Min: ' + (minV === Infinity ? '--' : (minV >= 0 ? '+' : '') + minV.toFixed(2) + 'V');
            document.getElementById('maxStat').textContent = 'Max: ' + (maxV === -Infinity ? '--' : (maxV >= 0 ? '+' : '') + maxV.toFixed(2) + 'V');
        }
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
