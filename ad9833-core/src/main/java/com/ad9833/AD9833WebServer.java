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
import java.util.concurrent.Executors;

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
        this(port, null);
    }

    public AD9833WebServer(int port, MCP3208Controller externalAdc) throws Exception {
        try {
            controller = new AD9833Controller();
        } catch (Exception e) {
            System.err.println("AD9833 not available: " + e.getMessage());
        }
        if (externalAdc != null) {
            adcController = externalAdc;
        } else {
            try {
                adcController = MCP3208Controller.getShared();
            } catch (Exception e) {
                System.err.println("MCP3208 not available: " + e.getMessage());
            }
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Combined page
        server.createContext("/", new MainPageHandler());

        // Generator API
        server.createContext("/api/start", new StartHandler());
        server.createContext("/api/stop", new StopHandler());
        server.createContext("/api/set", new SetHandler());
        server.createContext("/api/status", new StatusHandler());

        // Analyzer API
        server.createContext("/api/analyzer/stream", new AnalyzerStreamHandler());
        server.createContext("/api/analyzer/start", new AnalyzerStartHandler());
        server.createContext("/api/analyzer/stop", new AnalyzerStopHandler());

        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
        System.out.println("AD9833 Web Server started on port " + server.getAddress().getPort());
        System.out.println("Access at: http://localhost:" + server.getAddress().getPort());
    }

    public void stop() {
        if (analyzerRunning) {
            analyzerRunning = false;
            if (adcController != null) {
                adcController.stopContinuousSampling();
            }
        }
        if (isRunning && controller != null) {
            try {
                controller.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (controller != null) controller.close();
        server.stop(0);
    }

    // ========== Generator Handlers ==========

    private class MainPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getCombinedHtml();
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
            if (adcController != null) {
                adcController.setSamplerChannel(analyzerChannel);
                adcController.setSamplerSamples(analyzerSamplesPerFrame);
                adcController.startContinuousSampling(analyzerChannel, analyzerSamplesPerFrame);
            }
            sendJson(exchange, "{\"status\":\"ok\",\"running\":true}");
        }
    }

    private class AnalyzerStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            analyzerRunning = false;
            if (adcController != null) {
                adcController.stopContinuousSampling();
            }
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
                int[] lastSent = null;
                while (analyzerRunning) {
                    if (adcController == null) {
                        String msg = "data: {\"error\":\"ADC not available\"}\n\n";
                        os.write(msg.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        break;
                    }

                    try {
                        int[] raw = adcController.getLatestSamples();
                        if (raw.length == 0 || raw == lastSent) {
                            Thread.sleep(33);
                            continue;
                        }
                        lastSent = raw;
                        double measuredFreq = adcController.getLatestFrequency();
                        double sampleDuration = adcController.getLatestSampleDuration();
                        long dtUs = (long)(sampleDuration * 1_000_000);
                        StringBuilder sb = new StringBuilder("data: {\"v\":[");
                        for (int i = 0; i < raw.length; i++) {
                            if (i > 0) sb.append(',');
                            double voltage = (raw[i] * 3.3) / 4095.0;
                            sb.append(String.format("%.4f", voltage));
                        }
                        sb.append("],\"dt\":").append(dtUs);
                        sb.append(",\"freq\":").append(String.format("%.2f", measuredFreq));
                        sb.append("}\n\n");
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

    // ========== Combined HTML ==========

    private String getCombinedHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hysteresis Loop Device</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh;
            color: white;
            padding: 8px;
        }
        .columns { display: flex; gap: 10px; max-width: 900px; margin: 0 auto; }
        .col-scope { flex: 3; min-width: 0; }
        .col-gen { flex: 2; min-width: 200px; }
        h2 { text-align: center; color: #00aaff; font-size: 15px; margin-bottom: 5px; }

        /* ---- Analyzer ---- */
        .scope-header { display: flex; align-items: center; justify-content: center; gap: 10px; margin-bottom: 4px; flex-wrap: wrap; }
        .voltage-display { font-family: 'Courier New', monospace; font-size: 22px; color: #00ff88; text-shadow: 0 0 10px rgba(0,255,136,0.5); }
        .measured-freq { font-family: 'Courier New', monospace; font-size: 16px; color: #ffcc00; }
        .canvas-wrap { background: #0a0a15; border: 2px solid #333355; border-radius: 8px; overflow: hidden; margin-bottom: 5px; }
        canvas { display: block; width: 100%; }
        .scope-controls { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; justify-content: center; margin-bottom: 5px; }
        .scope-btn { padding: 7px 14px; border: none; border-radius: 7px; font-size: 13px; font-weight: bold; cursor: pointer; }
        .scope-btn:active { transform: scale(0.97); }
        .scope-start { background: linear-gradient(135deg, #4CAF50, #45a049); color: white; }
        .scope-stop { background: linear-gradient(135deg, #f44336, #d32f2f); color: white; }
        .toggle-btn { padding: 4px 8px; border: 2px solid #333; border-radius: 6px; background: transparent; color: #888; font-size: 11px; font-weight: bold; cursor: pointer; }
        .toggle-btn.active { border-color: #ff9800; color: #ff9800; background: rgba(255,152,0,0.15); }
        .toggle-btn.active-ac { border-color: #9c27b0; color: #9c27b0; background: rgba(156,39,176,0.15); }
        select { background: #333; color: white; border: 1px solid #555; border-radius: 6px; padding: 3px 6px; font-size: 12px; }
        .status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 3px; }
        .status-dot.running { background: #4CAF50; box-shadow: 0 0 8px #4CAF50; }
        .status-dot.stopped { background: #f44336; }
        .stats { display: flex; gap: 10px; justify-content: center; font-family: monospace; font-size: 11px; color: #888899; }

        /* ---- Generator ---- */
        .gen-section { border-left: 1px solid #333355; padding-left: 10px; }
        .gen-status { text-align: center; font-size: 13px; margin-bottom: 3px; }
        .gen-freq { text-align: center; font-family: 'Courier New', monospace; font-size: 22px; color: #00ff88; margin: 3px 0; text-shadow: 0 0 12px rgba(0,255,136,0.5); }
        .ctrl-group { background: rgba(255,255,255,0.05); border-radius: 8px; padding: 6px 8px; margin-bottom: 6px; }
        .ctrl-group label { display: block; font-size: 11px; color: #888; margin-bottom: 3px; }
        input[type="range"] { width: 100%; height: 6px; -webkit-appearance: none; background: #333; border-radius: 3px; outline: none; }
        input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 20px; height: 20px; background: #00aaff; border-radius: 50%; cursor: pointer; }
        .presets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px; margin-top: 4px; }
        .preset-btn { padding: 6px; border: none; border-radius: 6px; background: #333; color: white; font-size: 12px; cursor: pointer; }
        .preset-btn:active { background: #00aaff; }
        .waveform-btns { display: flex; gap: 6px; }
        .wave-btn { flex: 1; padding: 8px; border: 2px solid #333; border-radius: 8px; background: transparent; color: white; font-size: 13px; cursor: pointer; }
        .wave-btn.active { border-color: #00aaff; background: rgba(0,170,255,0.2); }
        .gen-btns { display: flex; gap: 8px; margin-top: 6px; }
        .gen-btn { flex: 1; padding: 10px; border: none; border-radius: 8px; font-size: 14px; font-weight: bold; cursor: pointer; }
        .gen-btn:active { transform: scale(0.97); }
        .gen-start { background: linear-gradient(135deg, #4CAF50, #45a049); color: white; }
        .gen-stop { background: linear-gradient(135deg, #f44336, #d32f2f); color: white; }
        .phase-value { float: right; color: #00aaff; font-family: monospace; }
        .slider-row { display: flex; gap: 6px; align-items: center; }
        .slider-row label { font-size: 11px; color: #888; white-space: nowrap; }
        .slider-row input[type="range"] { flex: 1; }
        .slider-row .val { font-family: monospace; color: #00aaff; font-size: 11px; min-width: 30px; }
    </style>
</head>
<body>
<div class="columns">

    <!-- ========== ANALYZER (left) ========== -->
    <div class="col-scope">
        <h2>Signal Analyzer</h2>
        <div class="scope-header">
            <span><span class="status-dot stopped" id="scopeDot"></span><span id="scopeStatus">Stopped</span></span>
            <span class="voltage-display" id="voltageDisplay">-- Vpp</span>
            <span class="measured-freq" id="measuredFreq">-- Hz</span>
        </div>
        <div class="canvas-wrap">
            <canvas id="scope" width="560" height="260"></canvas>
        </div>
        <div class="scope-controls">
            <button class="scope-btn scope-start" onclick="startAnalyzer()">START</button>
            <button class="scope-btn scope-stop" onclick="stopAnalyzer()">STOP</button>
            <select id="channelSel" onchange="scopeUpdateSettings()">
                <option value="0">CH0</option>
                <option value="1" selected>CH1</option>
                <option value="2">CH2</option>
                <option value="3">CH3</option>
            </select>
            <button class="toggle-btn active" id="autoBtn" onclick="toggleAuto()">AUTO</button>
            <button class="toggle-btn active-ac" id="acBtn" onclick="toggleAC()">AC</button>
            <button class="toggle-btn active" id="trigBtn" onclick="toggleTrig()">TRIG</button>
        </div>
        <div class="scope-controls">
            <div class="slider-row">
                <label>Zoom</label>
                <input type="range" id="zoomSlider" min="10" max="90" value="75" style="width:100px" oninput="zoomVal.textContent=this.value+'%'">
                <span class="val" id="zoomVal">75%</span>
            </div>
            <div class="stats">
                <span id="minStat">Min: --</span>
                <span id="maxStat">Max: --</span>
            </div>
        </div>
    </div>

    <!-- ========== GENERATOR (right) ========== -->
    <div class="col-gen gen-section">
        <h2>Waveform Generator</h2>
        <div class="gen-status">
            <span class="status-dot stopped" id="genDot"></span>
            <span id="genStatus">Stopped</span>
        </div>
        <div class="gen-freq" id="genFreqDisplay">1000.00 Hz</div>
        <div class="ctrl-group">
            <label>Frequency</label>
            <input type="range" id="freqSlider" min="0" max="7" step="0.01" value="3">
            <div class="presets">
                <button class="preset-btn" onclick="setFreq(10)">10</button>
                <button class="preset-btn" onclick="setFreq(100)">100</button>
                <button class="preset-btn" onclick="setFreq(1000)">1k</button>
                <button class="preset-btn" onclick="setFreq(10000)">10k</button>
                <button class="preset-btn" onclick="setFreq(100000)">100k</button>
                <button class="preset-btn" onclick="setFreq(1000000)">1M</button>
            </div>
        </div>
        <div class="ctrl-group">
            <label>Phase <span class="phase-value" id="phaseValue">0\u00b0</span></label>
            <input type="range" id="phaseSlider" min="0" max="360" value="0">
        </div>
        <div class="ctrl-group">
            <label>Waveform</label>
            <div class="waveform-btns">
                <button class="wave-btn active" id="sineBtn" onclick="setWave('SINE')">Sine</button>
                <button class="wave-btn" id="triangleBtn" onclick="setWave('TRIANGLE')">Tri</button>
                <button class="wave-btn" id="squareBtn" onclick="setWave('SQUARE')">Sq</button>
            </div>
        </div>
        <div class="gen-btns">
            <button class="gen-btn gen-start" onclick="genStart()">START</button>
            <button class="gen-btn gen-stop" onclick="genStop()">STOP</button>
        </div>
    </div>

</div>
<script>
    // ===== ANALYZER =====
    const canvas = document.getElementById('scope');
    const ctx = canvas.getContext('2d');
    const BUFFER_SIZE = 400;
    let buffer = new Float64Array(BUFFER_SIZE);
    let bufferIndex = 0;
    let eventSource = null;
    let scopeRunning = false;

    let autoScale = true, acCoupling = true, triggerEnabled = true;
    let triggerLevel = 0.1;
    let scaleMin = -1.65, scaleMax = 1.65;
    let dcOffset = 0, smoothedFreq = 0, trigFrac = 0;

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
    function scopeUpdateSettings() { buffer.fill(0); bufferIndex = 0; }

    async function startAnalyzer() {
        if (scopeRunning) return;
        let ch = document.getElementById('channelSel').value;
        await fetch('/api/analyzer/start?channel=' + ch + '&samples=1000');
        scopeRunning = true;
        document.getElementById('scopeDot').className = 'status-dot running';
        document.getElementById('scopeStatus').textContent = 'Sampling';

        eventSource = new EventSource('/api/analyzer/stream');
        eventSource.onmessage = function(e) {
            let data = JSON.parse(e.data);
            if (data.error) { stopAnalyzer(); return; }
            let voltages = data.v;

            let sum = 0;
            for (let i = 0; i < voltages.length; i++) sum += voltages[i];
            let tempDc = acCoupling ? sum / voltages.length : 0;

            let trigStart = 0;
            trigFrac = 0;
            if (triggerEnabled && voltages.length > BUFFER_SIZE) {
                let searchEnd = voltages.length - BUFFER_SIZE;
                for (let i = 1; i < searchEnd; i++) {
                    let prev = voltages[i-1] - tempDc;
                    let curr = voltages[i] - tempDc;
                    if (prev < triggerLevel && curr >= triggerLevel) {
                        trigStart = i - 1;
                        let denom = curr - prev;
                        trigFrac = denom !== 0 ? (triggerLevel - prev) / denom : 0;
                        break;
                    }
                }
            }

            let count = Math.min(BUFFER_SIZE, voltages.length - trigStart);
            for (let i = 0; i < count; i++) buffer[i] = voltages[trigStart + i];
            bufferIndex = count % BUFFER_SIZE;

            if (data.freq && data.freq > 0) {
                smoothedFreq = smoothedFreq === 0 ? data.freq : smoothedFreq * 0.7 + data.freq * 0.3;
                document.getElementById('measuredFreq').textContent = formatFreqHz(smoothedFreq);
            }
        };
        eventSource.onerror = function() { stopAnalyzer(); };
        requestAnimationFrame(drawLoop);
    }

    function stopAnalyzer() {
        scopeRunning = false;
        if (eventSource) { eventSource.close(); eventSource = null; }
        fetch('/api/analyzer/stop');
        document.getElementById('scopeDot').className = 'status-dot stopped';
        document.getElementById('scopeStatus').textContent = 'Stopped';
    }

    function formatFreqHz(f) {
        if (f >= 1e6) return (f/1e6).toFixed(2) + ' MHz';
        if (f >= 1e3) return (f/1e3).toFixed(2) + ' kHz';
        return f.toFixed(1) + ' Hz';
    }

    function drawLoop() {
        if (!scopeRunning) return;
        drawWaveform();
        requestAnimationFrame(drawLoop);
    }

    function drawWaveform() {
        let w = canvas.width, h = canvas.height;

        if (acCoupling) {
            let sum = 0;
            for (let i = 0; i < BUFFER_SIZE; i++) sum += buffer[i];
            dcOffset = sum / BUFFER_SIZE;
        } else { dcOffset = 0; }

        if (autoScale) {
            let min = Infinity, max = -Infinity;
            for (let i = 0; i < BUFFER_SIZE; i++) {
                let v = buffer[i] - dcOffset;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (min !== Infinity && max !== -Infinity && max > min) {
                let range = max - min, margin = range * 0.1;
                if (acCoupling) {
                    let maxAbs = Math.max(Math.abs(min - margin), Math.abs(max + margin));
                    scaleMin = -maxAbs; scaleMax = maxAbs;
                } else {
                    scaleMin = Math.max(0, min - margin);
                    scaleMax = Math.min(3.3, max + margin);
                }
            }
        }

        let range = scaleMax - scaleMin;
        if (range <= 0) range = 3.3;

        ctx.fillStyle = '#0a0a15';
        ctx.fillRect(0, 0, w, h);

        ctx.strokeStyle = '#222244'; ctx.lineWidth = 1;
        for (let i = 0; i <= 10; i++) { let x = i*w/10; ctx.beginPath(); ctx.moveTo(x,0); ctx.lineTo(x,h); ctx.stroke(); }
        for (let i = 0; i <= 6; i++) { let y = i*h/6; ctx.beginPath(); ctx.moveTo(0,y); ctx.lineTo(w,y); ctx.stroke(); }

        ctx.fillStyle = '#666688'; ctx.font = '11px monospace';
        ctx.fillText(scaleMax.toFixed(2)+'V', 5, 14);
        ctx.fillText(((scaleMax+scaleMin)/2).toFixed(2)+'V', 5, h/2+4);
        ctx.fillText(scaleMin.toFixed(2)+'V', 5, h-4);

        if (triggerEnabled) {
            ctx.strokeStyle='#ff9800'; ctx.lineWidth=1; ctx.setLineDash([5,5]);
            let ty = Math.max(0, Math.min(h, h-((triggerLevel-scaleMin)/range*h)));
            ctx.beginPath(); ctx.moveTo(0,ty); ctx.lineTo(w,ty); ctx.stroke(); ctx.setLineDash([]);
        }
        if (acCoupling) {
            ctx.strokeStyle='#444466'; ctx.lineWidth=1;
            let zy = Math.max(0, Math.min(h, h-((0-scaleMin)/range*h)));
            ctx.beginPath(); ctx.moveTo(0,zy); ctx.lineTo(w,zy); ctx.stroke();
        }

        let zoomPct = parseInt(document.getElementById('zoomSlider').value);
        let dispSamples = Math.floor(BUFFER_SIZE * zoomPct / 100);
        let pxPerSample = w / dispSamples;
        let xOff = triggerEnabled ? -trigFrac * pxPerSample : 0;

        ctx.strokeStyle = '#00ff88'; ctx.lineWidth = 2; ctx.beginPath();
        let minV = Infinity, maxV = -Infinity;
        for (let i = 0; i < dispSamples; i++) {
            let x = i / dispSamples * w + xOff;
            let v = buffer[i] - dcOffset;
            if (v < minV) minV = v; if (v > maxV) maxV = v;
            let y = Math.max(0, Math.min(h, h - ((v - scaleMin) / range * h)));
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        }
        ctx.stroke();

        document.getElementById('minStat').textContent = 'Min:' + (minV===Infinity ? '--' : (minV>=0?'+':'') + minV.toFixed(2)+'V');
        document.getElementById('maxStat').textContent = 'Max:' + (maxV===-Infinity ? '--' : (maxV>=0?'+':'') + maxV.toFixed(2)+'V');
        if (minV !== Infinity && maxV !== -Infinity) {
            document.getElementById('voltageDisplay').textContent = (maxV-minV).toFixed(3) + ' Vpp';
        }
    }

    // ===== GENERATOR =====
    let currentFreq = 1000, currentPhase = 0, currentWave = 'SINE', genRunning = false;
    const freqSlider = document.getElementById('freqSlider');
    const phaseSlider = document.getElementById('phaseSlider');

    function formatFreq(f) { if(f>=1e6) return (f/1e6).toFixed(2)+' MHz'; if(f>=1e3) return (f/1e3).toFixed(2)+' kHz'; return f.toFixed(2)+' Hz'; }
    freqSlider.addEventListener('input', function() { currentFreq = Math.pow(10, this.value); document.getElementById('genFreqDisplay').textContent = formatFreq(currentFreq); updateGen(); });
    phaseSlider.addEventListener('input', function() { currentPhase = parseInt(this.value); document.getElementById('phaseValue').textContent = currentPhase + '\u00b0'; updateGen(); });
    function setFreq(f) { currentFreq=f; freqSlider.value=Math.log10(f); document.getElementById('genFreqDisplay').textContent=formatFreq(f); updateGen(); }
    function setWave(w) { currentWave=w; document.querySelectorAll('.wave-btn').forEach(b=>b.classList.remove('active')); document.getElementById(w.toLowerCase()+'Btn').classList.add('active'); updateGen(); }
    function updateGen() { fetch('/api/set?freq='+currentFreq+'&phase='+currentPhase+'&wave='+currentWave); }
    function genStart() { fetch('/api/start').then(r=>r.json()).then(d=>{ if(d.running){genRunning=true;document.getElementById('genDot').className='status-dot running';document.getElementById('genStatus').textContent='Running';} }); }
    function genStop() { fetch('/api/stop').then(r=>r.json()).then(d=>{ genRunning=false;document.getElementById('genDot').className='status-dot stopped';document.getElementById('genStatus').textContent='Stopped'; }); }

    // Load current generator state
    fetch('/api/status').then(r=>r.json()).then(d=>{
        genRunning=d.running; currentFreq=d.freq; currentPhase=d.phase; currentWave=d.wave;
        freqSlider.value=Math.log10(currentFreq);
        document.getElementById('genFreqDisplay').textContent=formatFreq(currentFreq);
        phaseSlider.value=currentPhase;
        document.getElementById('phaseValue').textContent=currentPhase+'\u00b0';
        document.querySelectorAll('.wave-btn').forEach(b=>b.classList.remove('active'));
        document.getElementById(currentWave.toLowerCase()+'Btn').classList.add('active');
        if(genRunning){document.getElementById('genDot').className='status-dot running';document.getElementById('genStatus').textContent='Running';}
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
