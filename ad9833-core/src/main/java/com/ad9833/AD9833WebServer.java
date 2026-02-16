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
    private int analyzerChannel = 3;
    private int analyzerChannel2 = -1; // -1 = OFF
    private int analyzerSamplesPerFrame = 1000;
    private volatile int[] lastRawCh1;
    private volatile int[] lastRawCh2;

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
        server.createContext("/api/analyzer/sample", new AnalyzerIntervalHandler());
        server.createContext("/api/analyzer/csv", new AnalyzerCsvHandler());

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
            if (params.containsKey("channel2")) {
                String ch2 = params.get("channel2");
                analyzerChannel2 = "off".equalsIgnoreCase(ch2) ? -1 : Integer.parseInt(ch2);
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
                        StringBuilder sb = new StringBuilder("data: {\"v\":[");
                        int rawSamples = Math.max(analyzerSamplesPerFrame, 10000);
                        int targetPoints = 2000;

                        if (analyzerChannel2 >= 0) {
                            MCP3208Controller.CoherentResult[] cr =
                                adcController.sampleCoherentDual(analyzerChannel, analyzerChannel2,
                                                                  targetPoints, rawSamples);
                            if (cr != null && cr[0].cyclesAveraged > 0) {
                                lastRawCh1 = cr[0].averagedValues;
                                lastRawCh2 = cr[1].averagedValues;
                                for (int i = 0; i < cr[0].averagedValues.length; i++) {
                                    if (i > 0) sb.append(',');
                                    sb.append(String.format("%.4f", (cr[0].averagedValues[i] * 3.3) / 4095.0));
                                }
                                sb.append("],\"v2\":[");
                                for (int i = 0; i < cr[1].averagedValues.length; i++) {
                                    if (i > 0) sb.append(',');
                                    sb.append(String.format("%.4f", (cr[1].averagedValues[i] * 3.3) / 4095.0));
                                }
                                sb.append("]");
                                sb.append(",\"freq\":").append(String.format("%.2f", cr[0].frequencyHz));
                                sb.append(",\"cycles\":").append(cr[0].cyclesAveraged);
                                sb.append(",\"coherent\":true");
                            } else {
                                sb.append("],\"error\":\"Period detection failed\"");
                            }
                        } else {
                            MCP3208Controller.CoherentResult cr =
                                adcController.sampleCoherent(analyzerChannel, targetPoints, rawSamples);
                            if (cr != null && cr.cyclesAveraged > 0) {
                                lastRawCh1 = cr.averagedValues;
                                lastRawCh2 = null;
                                for (int i = 0; i < cr.averagedValues.length; i++) {
                                    if (i > 0) sb.append(',');
                                    sb.append(String.format("%.4f", (cr.averagedValues[i] * 3.3) / 4095.0));
                                }
                                sb.append("]");
                                sb.append(",\"freq\":").append(String.format("%.2f", cr.frequencyHz));
                                sb.append(",\"cycles\":").append(cr.cyclesAveraged);
                                sb.append(",\"coherent\":true");
                            } else {
                                sb.append("],\"error\":\"Period detection failed\"");
                            }
                        }
                        sb.append("}\n\n");
                        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        os.flush();

                        Thread.sleep(100); // ~200ms capture + 100ms pause
                    } catch (IOException e) {
                        break; // Client disconnected
                    } catch (Exception e) {
                        // Continue on sampling errors
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                }
            } catch (IOException ignored) {
                // Client disconnected
            }
        }
    }

    private class AnalyzerIntervalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (adcController == null) {
                sendJson(exchange, "{\"error\":\"ADC not available\"}");
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);
                int ch1 = params.containsKey("channel") ? Integer.parseInt(params.get("channel")) : analyzerChannel;
                int ch2 = -1;
                if (params.containsKey("channel2")) {
                    String c2 = params.get("channel2");
                    ch2 = "off".equalsIgnoreCase(c2) ? -1 : Integer.parseInt(c2);
                }
                int samples = params.containsKey("samples") ? Integer.parseInt(params.get("samples")) : 10000;
                boolean coherent = "true".equals(params.get("coherent"));

                if (coherent) {
                    int targetPoints = params.containsKey("points") ? Integer.parseInt(params.get("points")) : 2000;
                    StringBuilder sb = new StringBuilder("{\"v\":[");
                    if (ch2 >= 0) {
                        MCP3208Controller.CoherentResult[] cr = adcController.sampleCoherentDual(ch1, ch2, targetPoints, samples);
                        if (cr != null && cr[0].cyclesAveraged > 0) {
                            for (int i = 0; i < cr[0].averagedValues.length; i++) {
                                if (i > 0) sb.append(',');
                                sb.append(String.format("%.4f", (cr[0].averagedValues[i] * 3.3) / 4095.0));
                            }
                            sb.append("],\"v2\":[");
                            for (int i = 0; i < cr[1].averagedValues.length; i++) {
                                if (i > 0) sb.append(',');
                                sb.append(String.format("%.4f", (cr[1].averagedValues[i] * 3.3) / 4095.0));
                            }
                            sb.append("]");
                            lastRawCh1 = cr[0].averagedValues;
                            lastRawCh2 = cr[1].averagedValues;
                            sb.append(",\"freq\":").append(String.format("%.2f", cr[0].frequencyHz));
                            sb.append(",\"cycles\":").append(cr[0].cyclesAveraged);
                            sb.append(",\"coherent\":true");
                        } else {
                            sb.append("],\"error\":\"Period detection failed\"");
                        }
                    } else {
                        MCP3208Controller.CoherentResult cr = adcController.sampleCoherent(ch1, targetPoints, samples);
                        if (cr != null && cr.cyclesAveraged > 0) {
                            for (int i = 0; i < cr.averagedValues.length; i++) {
                                if (i > 0) sb.append(',');
                                sb.append(String.format("%.4f", (cr.averagedValues[i] * 3.3) / 4095.0));
                            }
                            sb.append("]");
                            lastRawCh1 = cr.averagedValues;
                            lastRawCh2 = null;
                            sb.append(",\"freq\":").append(String.format("%.2f", cr.frequencyHz));
                            sb.append(",\"cycles\":").append(cr.cyclesAveraged);
                            sb.append(",\"coherent\":true");
                        } else {
                            sb.append("],\"error\":\"Period detection failed\"");
                        }
                    }
                    sb.append("}");
                    sendJson(exchange, sb.toString());
                } else {
                    // Legacy non-coherent mode
                    StringBuilder sb = new StringBuilder("{\"v\":[");
                    if (ch2 >= 0) {
                        int[][] xy = adcController.sampleFastDualChannel(ch1, ch2, samples);
                        lastRawCh1 = xy[0];
                        lastRawCh2 = xy[1];
                        for (int i = 0; i < xy[0].length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(String.format("%.4f", (xy[0][i] * 3.3) / 4095.0));
                        }
                        sb.append("],\"v2\":[");
                        for (int i = 0; i < xy[1].length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(String.format("%.4f", (xy[1][i] * 3.3) / 4095.0));
                        }
                        sb.append("]");
                    } else {
                        int[] raw = adcController.sampleFast(ch1, samples);
                        lastRawCh1 = raw;
                        lastRawCh2 = null;
                        for (int i = 0; i < raw.length; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(String.format("%.4f", (raw[i] * 3.3) / 4095.0));
                        }
                        sb.append("]");
                    }
                    double duration = adcController.getLastSampleDurationSeconds();
                    sb.append(",\"dt\":").append((long)(duration * 1_000_000));
                    sb.append("}");
                    sendJson(exchange, sb.toString());
                }
            } catch (Exception e) {
                sendJson(exchange, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    private class AnalyzerCsvHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int[] raw1 = lastRawCh1;
            int[] raw2 = lastRawCh2;
            if (raw1 == null || raw1.length == 0) {
                sendJson(exchange, "{\"error\":\"No data\"}");
                return;
            }
            StringBuilder sb = new StringBuilder();
            boolean hasCh2 = raw2 != null && raw2.length > 0;
            sb.append(hasCh2 ? "index,ch1_raw,ch1_voltage,ch2_raw,ch2_voltage\n" : "index,ch1_raw,ch1_voltage\n");
            for (int i = 0; i < raw1.length; i++) {
                double v1 = (raw1[i] * 3.3) / 4095.0;
                if (hasCh2 && i < raw2.length) {
                    double v2 = (raw2[i] * 3.3) / 4095.0;
                    sb.append(String.format("%d,%d,%.4f,%d,%.4f\n", i, raw1[i], v1, raw2[i], v2));
                } else {
                    sb.append(String.format("%d,%d,%.4f\n", i, raw1[i], v1));
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "text/csv");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=signal_data.csv");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
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
        .columns { display: flex; gap: 12px; max-width: 1400px; margin: 0 auto; }
        .col-scope { flex: 3; min-width: 0; }
        .col-gen { flex: 1.5; min-width: 240px; }
        h2 { text-align: center; color: #00aaff; font-size: 18px; margin-bottom: 6px; }

        /* ---- Analyzer ---- */
        .scope-header { display: flex; align-items: center; justify-content: center; gap: 10px; margin-bottom: 4px; flex-wrap: wrap; }
        .voltage-display { font-family: 'Courier New', monospace; font-size: 26px; color: #00ff88; text-shadow: 0 0 10px rgba(0,255,136,0.5); }
        .measured-freq { font-family: 'Courier New', monospace; font-size: 20px; color: #ffcc00; }
        .canvas-wrap { background: #0a0a15; border: 2px solid #333355; border-radius: 8px; overflow: hidden; margin-bottom: 5px; }
        canvas { display: block; width: 100%; }
        .scope-controls { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; justify-content: center; margin-bottom: 5px; }
        .scope-btn { padding: 10px 18px; border: none; border-radius: 7px; font-size: 15px; font-weight: bold; cursor: pointer; }
        .scope-btn:active { transform: scale(0.97); }
        .scope-start { background: linear-gradient(135deg, #4CAF50, #45a049); color: white; }
        .scope-stop { background: linear-gradient(135deg, #f44336, #d32f2f); color: white; }
        .toggle-btn { padding: 6px 12px; border: 2px solid #333; border-radius: 6px; background: transparent; color: #888; font-size: 13px; font-weight: bold; cursor: pointer; }
        .toggle-btn.active { border-color: #ff9800; color: #ff9800; background: rgba(255,152,0,0.15); }
        .toggle-btn.active-ac { border-color: #9c27b0; color: #9c27b0; background: rgba(156,39,176,0.15); }
        select { background: #333; color: white; border: 1px solid #555; border-radius: 6px; padding: 5px 8px; font-size: 14px; }
        .interval-btn { padding: 8px 14px; border: 2px solid #444; border-radius: 6px; background: transparent; color: #ccc; font-size: 14px; font-weight: bold; cursor: pointer; }
        .interval-btn.active { border-color: #ff9800; color: #ff9800; background: rgba(255,152,0,0.15); }
        .csv-btn { padding: 8px 16px; border: none; border-radius: 7px; font-size: 14px; font-weight: bold; cursor: pointer; background: linear-gradient(135deg, #2196F3, #1976D2); color: white; }
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
            <span class="voltage-display" id="voltageDisplay" style="color:#00ff88">CH1: -- Vpp</span>
            <span class="voltage-display" id="voltageDisplay2" style="color:#00aaff">CH2: -- Vpp</span>
            <span class="measured-freq" id="measuredFreq">-- Hz</span>
        </div>
        <div class="canvas-wrap">
            <canvas id="scope" width="800" height="360"></canvas>
        </div>
        <div class="scope-controls">
            <button class="scope-btn scope-start" onclick="startScope()">START</button>
            <button class="scope-btn scope-stop" onclick="stopScope()">STOP</button>
            <button class="toggle-btn active" id="contBtn" onclick="setMode('cont')">CONT</button>
            <button class="toggle-btn" id="intBtn" onclick="setMode('interval')">INTRVL</button>
            <span style="color:#00ff88;font-size:12px;font-weight:bold">1:</span>
            <select id="channelSel" onchange="scopeUpdateSettings()" style="width:60px">
                <option value="0">CH0</option>
                <option value="1">CH1</option>
                <option value="2">CH2</option>
                <option value="3" selected>CH3</option>
                <option value="4">CH4</option>
                <option value="5">CH5</option>
                <option value="6">CH6</option>
                <option value="7">CH7</option>
            </select>
            <span style="color:#00aaff;font-size:12px;font-weight:bold">2:</span>
            <select id="channelSel2" onchange="scopeUpdateSettings()" style="width:60px">
                <option value="off" selected>OFF</option>
                <option value="0">CH0</option>
                <option value="1">CH1</option>
                <option value="2">CH2</option>
                <option value="3">CH3</option>
                <option value="4">CH4</option>
                <option value="5">CH5</option>
                <option value="6">CH6</option>
                <option value="7">CH7</option>
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
        <div class="scope-controls" id="intervalRow" style="display:none">
            <span style="color:#ccc;font-size:13px;font-weight:bold">Interval:</span>
            <button class="interval-btn active" onclick="setInterval2(1,this)">1s</button>
            <button class="interval-btn" onclick="setInterval2(5,this)">5s</button>
            <button class="interval-btn" onclick="setInterval2(10,this)">10s</button>
            <button class="interval-btn" onclick="setInterval2(20,this)">20s</button>
            <button class="interval-btn" onclick="togglePause()" id="pauseBtn">PAUSE</button>
            <span style="color:#888;font-size:12px">Raw:</span>
            <input type="range" id="intSamplesSlider" min="5000" max="10000" value="10000" style="width:100px" oninput="intSmpVal.textContent=this.value">
            <span class="val" id="intSmpVal" style="color:#00aaff;font-family:monospace;font-size:13px">10000</span>
            <button class="csv-btn" onclick="downloadCsv()">CSV</button>
        </div>

        <!-- Hysteresis Loop X-Y Plot -->
        <div id="xySection" style="display:none">
            <h2 style="color:#ff66aa;margin-top:8px;font-size:16px">Hysteresis Loop</h2>
            <div style="display:flex;gap:10px;align-items:flex-start;justify-content:center">
                <div class="canvas-wrap" style="flex:0 0 auto">
                    <canvas id="xyPlot" width="340" height="300"></canvas>
                </div>
                <div style="display:flex;flex-direction:column;gap:6px;padding-top:5px">
                    <span style="font-family:'Courier New',monospace;font-size:16px;color:#00ff88" id="xVpp">X: -- Vpp</span>
                    <span style="font-family:'Courier New',monospace;font-size:16px;color:#00aaff" id="yVpp">Y: -- Vpp</span>
                    <button class="toggle-btn active-ac" id="xyAcBtn" onclick="toggleXYAC()">AC</button>
                    <button class="toggle-btn active" id="xyAutoBtn" onclick="toggleXYAuto()">AUTO</button>
                    <button class="toggle-btn" id="xyPersistBtn" onclick="toggleXYPersist()">PERSIST</button>
                    <button class="toggle-btn" style="background:#607D8B;color:white;border-color:#607D8B" onclick="clearXY()">CLEAR</button>
                </div>
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
    const BUFFER_SIZE = 6000;
    let buffer = new Float64Array(BUFFER_SIZE);
    let buffer2 = new Float64Array(BUFFER_SIZE);
    let bufferIndex = 0;
    let eventSource = null;
    let scopeRunning = false;
    let dualChannel = true;
    let scopeMode = 'cont'; // 'cont' or 'interval'
    let intervalSec = 1;
    let intervalPaused = false;
    let intervalTimer = null;

    let autoScale = true, acCoupling = true, triggerEnabled = true;
    let triggerLevel = 0.1;
    let scaleMin = -1.65, scaleMax = 1.65;
    let dcOffset = 0, dcOffset2 = 0, smoothedFreq = 0, trigFrac = 0;
    let cycleLength = 0; // valid samples when interval mode extracts one cycle

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
    function scopeUpdateSettings() {
        buffer.fill(0); buffer2.fill(0); bufferIndex = 0;
        dualChannel = document.getElementById('channelSel2').value !== 'off';
        document.getElementById('voltageDisplay2').style.display = dualChannel ? '' : 'none';
        document.getElementById('xySection').style.display = dualChannel ? '' : 'none';
    }

    async function startAnalyzer() {
        if (scopeRunning) return;
        let ch = document.getElementById('channelSel').value;
        let ch2 = document.getElementById('channelSel2').value;
        dualChannel = ch2 !== 'off';
        document.getElementById('voltageDisplay2').style.display = dualChannel ? '' : 'none';
        document.getElementById('xySection').style.display = dualChannel ? '' : 'none';
        await fetch('/api/analyzer/start?channel=' + ch + '&channel2=' + ch2 + '&samples=1000');
        scopeRunning = true;
        document.getElementById('scopeDot').className = 'status-dot running';
        document.getElementById('scopeStatus').textContent = 'Sampling';

        eventSource = new EventSource('/api/analyzer/stream');
        eventSource.onmessage = function(e) {
            let data = JSON.parse(e.data);
            if (data.error) return;
            if (data.coherent) {
                processCoherentFrame(data);
            } else {
                processFrame(data);
                if (data.freq && data.freq > 0) {
                    smoothedFreq = smoothedFreq === 0 ? data.freq : smoothedFreq * 0.7 + data.freq * 0.3;
                    document.getElementById('measuredFreq').textContent = formatFreqHz(smoothedFreq);
                }
            }
            drawWaveform();
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

        // How many valid samples to use (cycleLength tracks valid count in both modes)
        let validSamples = (cycleLength > 0) ? cycleLength : (bufferIndex > 0 && bufferIndex < BUFFER_SIZE) ? bufferIndex : BUFFER_SIZE;

        // DC offsets
        if (acCoupling) {
            let sum = 0;
            for (let i = 0; i < validSamples; i++) sum += buffer[i];
            dcOffset = sum / validSamples;
            if (dualChannel) {
                let sum2 = 0;
                for (let i = 0; i < validSamples; i++) sum2 += buffer2[i];
                dcOffset2 = sum2 / validSamples;
            } else { dcOffset2 = 0; }
        } else { dcOffset = 0; dcOffset2 = 0; }

        // Auto-scale from both channels
        if (autoScale) {
            let min = Infinity, max = -Infinity;
            for (let i = 0; i < validSamples; i++) {
                let v = buffer[i] - dcOffset;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (dualChannel) {
                for (let i = 0; i < validSamples; i++) {
                    let v2 = buffer2[i] - dcOffset2;
                    if (v2 < min) min = v2;
                    if (v2 > max) max = v2;
                }
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

        // Grid
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

        let dispSamples;
        let xOff = 0;
        if (scopeMode === 'interval' && cycleLength > 0) {
            dispSamples = cycleLength;  // interval mode: show full period
        } else {
            let zoomPct = parseInt(document.getElementById('zoomSlider').value);
            dispSamples = Math.min(validSamples, Math.floor(validSamples * zoomPct / 100));
            if (dispSamples < 2) dispSamples = 2;
            let pxPerSample = w / dispSamples;
            xOff = triggerEnabled ? -trigFrac * pxPerSample : 0;
        }

        // Catmull-Rom cubic interpolation: sample buf at fractional index fi
        function cubicSample(buf, fi, n, dc) {
            let i1 = Math.floor(fi), t = fi - i1;
            let i0 = Math.max(0, i1 - 1), i2 = Math.min(n - 1, i1 + 1), i3 = Math.min(n - 1, i1 + 2);
            let y0 = buf[i0]-dc, y1 = buf[i1]-dc, y2 = buf[i2]-dc, y3 = buf[i3]-dc;
            let a = -0.5*y0 + 1.5*y1 - 1.5*y2 + 0.5*y3;
            let b = y0 - 2.5*y1 + 2*y2 - 0.5*y3;
            let c = -0.5*y0 + 0.5*y2;
            return a*t*t*t + b*t*t + c*t + y1;
        }
        let useInterp = dispSamples < 100; // cubic interpolation when few samples
        let drawSteps = useInterp ? w : dispSamples;

        // CH1 waveform (green)
        ctx.strokeStyle = '#00ff88'; ctx.lineWidth = 2; ctx.beginPath();
        let minV = Infinity, maxV = -Infinity;
        for (let s = 0; s < drawSteps; s++) {
            let x, v;
            if (useInterp) {
                x = s + xOff;
                let fi = (s / w) * (dispSamples - 1);
                v = cubicSample(buffer, fi, dispSamples, dcOffset);
            } else {
                x = s / dispSamples * w + xOff;
                v = buffer[s] - dcOffset;
            }
            if (v < minV) minV = v; if (v > maxV) maxV = v;
            let y = Math.max(0, Math.min(h, h - ((v - scaleMin) / range * h)));
            if (s === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        }
        ctx.stroke();

        // CH2 waveform (cyan)
        let minV2 = Infinity, maxV2 = -Infinity;
        if (dualChannel) {
            ctx.strokeStyle = '#00aaff'; ctx.lineWidth = 2; ctx.beginPath();
            for (let s = 0; s < drawSteps; s++) {
                let x, v2;
                if (useInterp) {
                    x = s + xOff;
                    let fi = (s / w) * (dispSamples - 1);
                    v2 = cubicSample(buffer2, fi, dispSamples, dcOffset2);
                } else {
                    x = s / dispSamples * w + xOff;
                    v2 = buffer2[s] - dcOffset2;
                }
                if (v2 < minV2) minV2 = v2; if (v2 > maxV2) maxV2 = v2;
                let y = Math.max(0, Math.min(h, h - ((v2 - scaleMin) / range * h)));
                if (s === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            }
            ctx.stroke();
        }

        // Stats
        document.getElementById('minStat').textContent = 'Min:' + (minV===Infinity ? '--' : (minV>=0?'+':'') + minV.toFixed(2)+'V');
        document.getElementById('maxStat').textContent = 'Max:' + (maxV===-Infinity ? '--' : (maxV>=0?'+':'') + maxV.toFixed(2)+'V');
        if (minV !== Infinity && maxV !== -Infinity) {
            document.getElementById('voltageDisplay').textContent = 'CH1: ' + (maxV-minV).toFixed(3) + ' Vpp';
        }
        if (dualChannel && minV2 !== Infinity && maxV2 !== -Infinity) {
            document.getElementById('voltageDisplay2').textContent = 'CH2: ' + (maxV2-minV2).toFixed(3) + ' Vpp';
        }
    }

    // ===== MODE / INTERVAL / CSV =====
    function setMode(m) {
        // Stop current mode before switching
        if (scopeRunning) {
            if (scopeMode === 'cont') stopAnalyzer();
            else stopIntervalMode();
        }
        scopeMode = m;
        document.getElementById('contBtn').className = m==='cont' ? 'toggle-btn active' : 'toggle-btn';
        document.getElementById('intBtn').className = m==='interval' ? 'toggle-btn active' : 'toggle-btn';
        document.getElementById('intervalRow').style.display = m==='interval' ? 'flex' : 'none';
    }

    function startScope() {
        if (scopeMode === 'cont') startAnalyzer();
        else startIntervalMode();
    }
    function stopScope() {
        if (scopeMode === 'cont') stopAnalyzer();
        else stopIntervalMode();
    }

    function startIntervalMode() {
        if (scopeRunning) return;
        scopeRunning = true; intervalPaused = false;
        document.getElementById('scopeDot').className = 'status-dot running';
        document.getElementById('scopeStatus').textContent = 'Interval';
        doIntervalSample();
        intervalTimer = setInterval(() => { if (!intervalPaused) doIntervalSample(); }, intervalSec * 1000);
    }

    function stopIntervalMode() {
        scopeRunning = false;
        if (intervalTimer) { clearInterval(intervalTimer); intervalTimer = null; }
        document.getElementById('scopeDot').className = 'status-dot stopped';
        document.getElementById('scopeStatus').textContent = 'Stopped';
    }

    async function doIntervalSample() {
        let ch = document.getElementById('channelSel').value;
        let ch2 = document.getElementById('channelSel2').value;
        dualChannel = ch2 !== 'off';
        document.getElementById('voltageDisplay2').style.display = dualChannel ? '' : 'none';
        let smp = document.getElementById('intSamplesSlider').value;
        try {
            let resp = await fetch('/api/analyzer/sample?channel=' + ch + '&channel2=' + ch2 + '&samples=' + smp + '&coherent=true');
            let text = await resp.text();
            let json = text.startsWith('data: ') ? text.substring(6) : text;
            let data = JSON.parse(json);
            if (data.error) return;
            if (data.coherent) {
                processCoherentFrame(data);
            } else {
                processFrame(data);
            }
            drawWaveform();
        } catch(e) {}
    }

    function processCoherentFrame(data) {
        let voltages = data.v;
        let voltages2 = data.v2 || null;
        let period = voltages.length;  // one period worth of points
        // Tile 3 periods into the buffer for zoom-out view
        let total = Math.min(BUFFER_SIZE, period * 3);
        for (let i = 0; i < total; i++) buffer[i] = voltages[i % period];
        for (let i = total; i < BUFFER_SIZE; i++) buffer[i] = 0;
        cycleLength = total;

        if (dualChannel && voltages2) {
            let period2 = voltages2.length;
            let total2 = Math.min(BUFFER_SIZE, period2 * 3);
            for (let i = 0; i < total2; i++) buffer2[i] = voltages2[i % period2];
            for (let i = total2; i < BUFFER_SIZE; i++) buffer2[i] = 0;
        }

        if (data.freq && data.freq > 0) {
            smoothedFreq = data.freq;
            let label = formatFreqHz(data.freq);
            if (data.cycles) label += ' (' + data.cycles + ' cycles)';
            document.getElementById('measuredFreq').textContent = label;
        }

        // Update hysteresis loop X-Y plot
        if (dualChannel && data.v2) updateXYPlot(data);
    }

    function findCycleBounds(signal) {
        let crossings = [];
        for (let i = 1; i < signal.length; i++) {
            if (signal[i-1] < 0 && signal[i] >= 0) crossings.push(i);
        }
        if (crossings.length < 2) return null;
        let spacings = [];
        for (let i = 1; i < crossings.length; i++) spacings.push(crossings[i] - crossings[i-1]);
        spacings.sort((a,b) => a-b);
        let medianPeriod = spacings[Math.floor(spacings.length / 2)];
        if (medianPeriod < 10) return null;
        let start = crossings[0];
        let targetEnd = start + medianPeriod;
        let bestEnd = -1, bestDist = Infinity;
        for (let i = 1; i < crossings.length; i++) {
            let dist = Math.abs(crossings[i] - targetEnd);
            if (dist < bestDist) { bestDist = dist; bestEnd = crossings[i]; }
        }
        if (bestEnd <= start) return null;
        return [start, bestEnd];
    }

    function processFrame(data) {
        let voltages = data.v;
        let voltages2 = data.v2 || null;
        let sum = 0;
        for (let i = 0; i < voltages.length; i++) sum += voltages[i];
        let tempDc = acCoupling ? sum / voltages.length : 0;

        // Interval mode: extract single cycle
        if (scopeMode === 'interval') {
            let acSignal = new Float64Array(voltages.length);
            for (let i = 0; i < voltages.length; i++) acSignal[i] = voltages[i] - tempDc;
            let bounds = findCycleBounds(acSignal);
            if (bounds) {
                let cycleLen = bounds[1] - bounds[0];
                let count = Math.min(BUFFER_SIZE, cycleLen);
                for (let i = 0; i < count; i++) {
                    buffer[i] = voltages[bounds[0] + i];
                    if (dualChannel && voltages2 && bounds[0] + i < voltages2.length)
                        buffer2[i] = voltages2[bounds[0] + i];
                }
                for (let i = count; i < BUFFER_SIZE; i++) buffer[i] = 0;
                cycleLength = count;
                // Compute frequency from cycle length
                if (data.dt && data.dt > 0) {
                    let sampleRate = voltages.length / (data.dt / 1e6);
                    let freq = sampleRate / cycleLen;
                    smoothedFreq = freq;
                    document.getElementById('measuredFreq').textContent = formatFreqHz(freq);
                }
                return;
            }
            // Fallback: no cycle found, show all data
        }

        cycleLength = 0; // not in cycle mode
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

        if (dualChannel && voltages2) {
            let count2 = Math.min(BUFFER_SIZE, voltages2.length - trigStart);
            for (let i = 0; i < count2; i++) buffer2[i] = voltages2[trigStart + i];
        }
    }

    function setInterval2(s, btn) {
        intervalSec = s; intervalPaused = false;
        document.querySelectorAll('.interval-btn').forEach(b => b.className = 'interval-btn');
        if (btn) btn.className = 'interval-btn active';
        document.getElementById('pauseBtn').className = 'interval-btn';
        if (scopeRunning && intervalTimer) {
            clearInterval(intervalTimer);
            doIntervalSample();
            intervalTimer = setInterval(() => { if (!intervalPaused) doIntervalSample(); }, intervalSec * 1000);
        }
    }

    function togglePause() {
        intervalPaused = !intervalPaused;
        document.querySelectorAll('.interval-btn').forEach(b => {
            if (b.id !== 'pauseBtn') b.className = 'interval-btn';
        });
        document.getElementById('pauseBtn').className = intervalPaused ? 'interval-btn active' : 'interval-btn';
        document.getElementById('scopeStatus').textContent = intervalPaused ? 'Paused' : 'Interval';
    }

    function downloadCsv() {
        window.open('/api/analyzer/csv', '_blank');
    }

    // ===== HYSTERESIS LOOP (X-Y Plot) =====
    const xyCanvas = document.getElementById('xyPlot');
    const xyCtx = xyCanvas.getContext('2d');
    let xyAC = true, xyAuto = true, xyPersist = false;
    let xyMinX = -1.65, xyMaxX = 1.65, xyMinY = -1.65, xyMaxY = 1.65;
    let xyPersistBuf = [];
    const MAX_XY_PERSIST = 5000;

    function drawXYGrid() {
        let w = xyCanvas.width, h = xyCanvas.height;
        xyCtx.fillStyle = '#0a0a15';
        xyCtx.fillRect(0, 0, w, h);
        xyCtx.strokeStyle = '#222244'; xyCtx.lineWidth = 1;
        for (let i = 0; i <= 8; i++) {
            let x = i*w/8; xyCtx.beginPath(); xyCtx.moveTo(x,0); xyCtx.lineTo(x,h); xyCtx.stroke();
            let y = i*h/8; xyCtx.beginPath(); xyCtx.moveTo(0,y); xyCtx.lineTo(w,y); xyCtx.stroke();
        }
        xyCtx.strokeStyle = '#334466'; xyCtx.lineWidth = 1.5;
        xyCtx.beginPath(); xyCtx.moveTo(w/2,0); xyCtx.lineTo(w/2,h); xyCtx.stroke();
        xyCtx.beginPath(); xyCtx.moveTo(0,h/2); xyCtx.lineTo(w,h/2); xyCtx.stroke();
        xyCtx.fillStyle = '#666688'; xyCtx.font = '10px monospace';
        xyCtx.fillText('X:'+xyMinX.toFixed(2)+'V', 3, h-3);
        xyCtx.fillText(xyMaxX.toFixed(2)+'V', w-50, h-3);
        xyCtx.fillText('Y:'+xyMaxY.toFixed(2)+'V', 3, 11);
        xyCtx.fillText(xyMinY.toFixed(2)+'V', 3, h-14);
    }

    function updateXYPlot(data) {
        if (!data.v2 || !dualChannel) return;
        let vx = data.v, vy = data.v2;
        let len = Math.min(vx.length, vy.length);

        // AC coupling: subtract mean
        let sumX = 0, sumY = 0;
        for (let i = 0; i < len; i++) { sumX += vx[i]; sumY += vy[i]; }
        let dcX = xyAC ? sumX/len : 0;
        let dcY = xyAC ? sumY/len : 0;

        let cx = new Float64Array(len), cy = new Float64Array(len);
        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (let i = 0; i < len; i++) {
            cx[i] = vx[i] - dcX; cy[i] = vy[i] - dcY;
            if (cx[i] < minX) minX = cx[i]; if (cx[i] > maxX) maxX = cx[i];
            if (cy[i] < minY) minY = cy[i]; if (cy[i] > maxY) maxY = cy[i];
        }

        document.getElementById('xVpp').textContent = 'X: ' + (maxX-minX).toFixed(3) + ' Vpp';
        document.getElementById('yVpp').textContent = 'Y: ' + (maxY-minY).toFixed(3) + ' Vpp';

        // Auto-scale
        if (xyAuto && minX < maxX && minY < maxY) {
            let rx = maxX-minX, mx = rx*0.1;
            let ry = maxY-minY, my = ry*0.1;
            if (xyAC) {
                let ax = Math.max(Math.abs(minX-mx), Math.abs(maxX+mx));
                xyMinX = -ax; xyMaxX = ax;
                let ay = Math.max(Math.abs(minY-my), Math.abs(maxY+my));
                xyMinY = -ay; xyMaxY = ay;
            } else {
                xyMinX = Math.max(0, minX-mx); xyMaxX = Math.min(3.3, maxX+mx);
                xyMinY = Math.max(0, minY-my); xyMaxY = Math.min(3.3, maxY+my);
            }
        }

        // Persistence: accumulate
        if (xyPersist) {
            for (let i = 0; i < len; i++) xyPersistBuf.push([cx[i], cy[i]]);
            while (xyPersistBuf.length > MAX_XY_PERSIST) xyPersistBuf.shift();
        }

        // Draw
        let w = xyCanvas.width, h = xyCanvas.height;
        drawXYGrid();
        let rx = xyMaxX - xyMinX, ry = xyMaxY - xyMinY;
        if (rx <= 0) rx = 3.3; if (ry <= 0) ry = 3.3;

        // Persistence layer (dimmer)
        if (xyPersist && xyPersistBuf.length > 0) {
            xyCtx.strokeStyle = 'rgba(0,255,136,0.3)'; xyCtx.lineWidth = 1;
            xyCtx.beginPath();
            for (let i = 0; i < xyPersistBuf.length; i++) {
                let px = Math.max(0, Math.min(w, ((xyPersistBuf[i][0]-xyMinX)/rx)*w));
                let py = Math.max(0, Math.min(h, h - ((xyPersistBuf[i][1]-xyMinY)/ry)*h));
                if (i===0) xyCtx.moveTo(px,py); else xyCtx.lineTo(px,py);
            }
            xyCtx.stroke();
        }

        // Current frame
        xyCtx.strokeStyle = '#00ff88'; xyCtx.lineWidth = 2;
        xyCtx.beginPath();
        for (let i = 0; i < len; i++) {
            let px = Math.max(0, Math.min(w, ((cx[i]-xyMinX)/rx)*w));
            let py = Math.max(0, Math.min(h, h - ((cy[i]-xyMinY)/ry)*h));
            if (i===0) xyCtx.moveTo(px,py); else xyCtx.lineTo(px,py);
        }
        xyCtx.stroke();
    }

    function toggleXYAC() {
        xyAC = !xyAC;
        document.getElementById('xyAcBtn').className = xyAC ? 'toggle-btn active-ac' : 'toggle-btn';
        if (!xyAuto) { xyMinX = xyAC?-1.65:0; xyMaxX = xyAC?1.65:3.3; xyMinY = xyAC?-1.65:0; xyMaxY = xyAC?1.65:3.3; }
    }
    function toggleXYAuto() {
        xyAuto = !xyAuto;
        document.getElementById('xyAutoBtn').className = xyAuto ? 'toggle-btn active' : 'toggle-btn';
        if (!xyAuto) { xyMinX = xyAC?-1.65:0; xyMaxX = xyAC?1.65:3.3; xyMinY = xyAC?-1.65:0; xyMaxY = xyAC?1.65:3.3; }
    }
    function toggleXYPersist() {
        xyPersist = !xyPersist;
        document.getElementById('xyPersistBtn').className = xyPersist ? 'toggle-btn active' : 'toggle-btn';
        if (!xyPersist) xyPersistBuf = [];
    }
    function clearXY() { xyPersistBuf = []; drawXYGrid(); }

    // Init X-Y plot
    drawXYGrid();

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
