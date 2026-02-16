package com.ad9833;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP3208 12-bit 8-channel SPI ADC Controller
 * Uses Python spidev for SPI1 communication (more reliable than pigpio for SPI1)
 *
 * Wiring (SPI1 - to avoid conflict with AD9833 on SPI0):
 *   SCLK  -> GPIO 21 (Pin 40)
 *   DOUT  -> GPIO 19 (Pin 35) - MISO
 *   DIN   -> GPIO 20 (Pin 38) - MOSI
 *   CS    -> GPIO 18 (Pin 12)
 *   VDD   -> 3.3V
 *   VREF  -> 3.3V (or external reference)
 *   AGND  -> GND
 *   DGND  -> GND
 */
public class MCP3208Controller implements AutoCloseable {

    private static final double VREF = 3.3;  // Reference voltage
    private static final int MAX_VALUE = 4095;  // 12-bit ADC (0-4095)

    /** Result of coherent averaging: one period reconstructed from many cycles. */
    public static class CoherentResult {
        public final int[] averagedValues;    // targetPoints integers, 0-4095
        public final double periodSeconds;
        public final double frequencyHz;
        public final int cyclesAveraged;

        public CoherentResult(int[] averagedValues, double periodSeconds,
                              double frequencyHz, int cyclesAveraged) {
            this.averagedValues = averagedValues;
            this.periodSeconds = periodSeconds;
            this.frequencyHz = frequencyHz;
            this.cyclesAveraged = cyclesAveraged;
        }
    }

    private final boolean verbose;
    private boolean initialized = false;
    private volatile double lastSampleDurationSeconds = 0;
    private volatile double lastMeasuredFrequency = 0;
    private boolean hasNativeHelper = false;

    // Shared continuous sampling thread
    private Thread samplerThread;
    private volatile boolean samplerRunning = false;
    private volatile int samplerChannel = 1;
    private volatile int samplerSamples = 1000;
    private volatile int[] latestRawSamples = new int[0];
    private volatile double latestSampleDuration = 0;
    private volatile double latestFrequency = 0;
    private final AtomicInteger activeConsumers = new AtomicInteger(0);

    // Dual-channel mode for X-Y plotting
    private volatile int samplerChannelY = -1;  // -1 = single-channel mode
    private volatile int[] latestRawSamplesX = new int[0];
    private volatile int[] latestRawSamplesY = new int[0];
    private volatile boolean dualChannelMode = false;

    // Singleton
    private static MCP3208Controller sharedInstance;

    public MCP3208Controller(boolean verbose) throws Exception {
        this.verbose = verbose;
        log("Initializing MCP3208 Controller (spidev mode)");

        // Check if native C helper is available
        try {
            ProcessBuilder pb = new ProcessBuilder(System.getProperty("user.home") + "/adc_sample", "0", "1");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            if (line != null && p.exitValue() == 0) {
                hasNativeHelper = true;
                initialized = true;
                log("Using native C helper for SPI1");
                return;
            }
        } catch (Exception e) {
            log("Native helper not available: %s", e.getMessage());
        }

        // Fallback: test that spidev is available via Python
        String test = readChannelViaPython(0);
        if (test != null) {
            initialized = true;
            log("SPI1 initialized successfully (Python mode)");
        } else {
            throw new Exception("Failed to initialize SPI1");
        }
    }

    public MCP3208Controller() throws Exception {
        this(false);
    }

    public static synchronized MCP3208Controller getShared() throws Exception {
        if (sharedInstance == null) {
            sharedInstance = new MCP3208Controller();
        }
        return sharedInstance;
    }

    private void log(String format, Object... args) {
        if (verbose) {
            System.out.printf("[MCP3208] " + format + "%n", args);
        }
    }

    private String readChannelViaPython(int channel) {
        try {
            String pythonCode = String.format(
                "import spidev;" +
                "spi=spidev.SpiDev();" +
                "spi.open(1,0);" +
                "spi.max_speed_hz=1000000;" +
                "spi.mode=0;" +
                "ch=%d;" +
                "cmd=[0x06|((ch&0x04)>>2),(ch&0x03)<<6,0x00];" +
                "r=spi.xfer2(cmd);" +
                "v=((r[1]&0x0F)<<8)|r[2];" +
                "print(v);" +
                "spi.close()",
                channel
            );

            ProcessBuilder pb = new ProcessBuilder("python3", "-c", pythonCode);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();

            return result;
        } catch (Exception e) {
            log("Python error: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Read a single channel (0-7)
     * @param channel The ADC channel to read (0-7)
     * @return Raw 12-bit value (0-4095)
     */
    public int readRaw(int channel) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("MCP3208 not initialized");
        }
        if (channel < 0 || channel > 7) {
            throw new IllegalArgumentException("Channel must be 0-7");
        }

        String result = readChannelViaPython(channel);
        if (result != null) {
            int value = Integer.parseInt(result.trim());
            log("Channel %d: raw=%d", channel, value);
            return value;
        }

        throw new IOException("Failed to read channel " + channel);
    }

    /**
     * Read a channel and convert to voltage
     * @param channel The ADC channel to read (0-7)
     * @return Voltage (0 to VREF)
     */
    public double readVoltage(int channel) throws Exception {
        int raw = readRaw(channel);
        double voltage = (raw * VREF) / MAX_VALUE;
        log("Channel %d: voltage=%.4fV", channel, voltage);
        return voltage;
    }

    /**
     * Read all 8 channels
     * @return Array of 8 raw values
     */
    public int[] readAllRaw() throws Exception {
        int[] values = new int[8];
        for (int i = 0; i < 8; i++) {
            values[i] = readRaw(i);
        }
        return values;
    }

    /**
     * Read all 8 channels as voltages
     * @return Array of 8 voltage values
     */
    public double[] readAllVoltages() throws Exception {
        double[] values = new double[8];
        for (int i = 0; i < 8; i++) {
            values[i] = readVoltage(i);
        }
        return values;
    }

    /**
     * Fast batch sampling - collects multiple samples in a single Python call
     * @param channel Channel to sample
     * @param samples Number of samples to collect
     * @return Array of raw samples
     */
    public int[] sampleFast(int channel, int samples) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("MCP3208 not initialized");
        }

        if (hasNativeHelper) {
            return sampleFastNative(channel, samples);
        }
        return sampleFastPython(channel, samples);
    }

    private int[] sampleFastNative(int channel, int samples) {
        try {
            String helper = System.getProperty("user.home") + "/adc_sample";
            ProcessBuilder pb = new ProcessBuilder(helper, String.valueOf(channel), String.valueOf(samples));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String valuesLine = reader.readLine();
            String timeLine = reader.readLine();
            String freqLine = reader.readLine();
            process.waitFor();

            if (timeLine != null) {
                try { lastSampleDurationSeconds = Double.parseDouble(timeLine.trim()); }
                catch (NumberFormatException ignored) {}
            }
            if (freqLine != null) {
                try { lastMeasuredFrequency = Double.parseDouble(freqLine.trim()); }
                catch (NumberFormatException ignored) {}
            }

            if (valuesLine != null && !valuesLine.isEmpty()) {
                String[] parts = valuesLine.trim().split("\\s+");
                int[] data = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    data[i] = Integer.parseInt(parts[i]);
                }
                return data;
            }
        } catch (Exception e) {
            log("Native sample error: %s", e.getMessage());
        }
        return new int[0];
    }

    private int[] sampleFastPython(int channel, int samples) {
        String pythonCode = String.format(
            "import spidev,time\n" +
            "spi=spidev.SpiDev()\n" +
            "spi.open(1,0)\n" +
            "spi.max_speed_hz=1000000\n" +
            "spi.mode=0\n" +
            "ch=%d\n" +
            "vals=[]\n" +
            "t0=time.monotonic()\n" +
            "for _ in range(%d):\n" +
            "  cmd=[0x06|((ch&0x04)>>2),(ch&0x03)<<6,0x00]\n" +
            "  r=spi.xfer2(cmd)\n" +
            "  vals.append(((r[1]&0x0F)<<8)|r[2])\n" +
            "t1=time.monotonic()\n" +
            "print(' '.join(map(str,vals)))\n" +
            "print(t1-t0)\n" +
            "spi.close()",
            channel, samples
        );

        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", pythonCode);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            String timeLine = reader.readLine();
            process.waitFor();

            if (timeLine != null) {
                try { lastSampleDurationSeconds = Double.parseDouble(timeLine.trim()); }
                catch (NumberFormatException ignored) {}
            }

            if (result != null && !result.isEmpty()) {
                String[] parts = result.trim().split("\\s+");
                int[] data = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    data[i] = Integer.parseInt(parts[i]);
                }
                return data;
            }
        } catch (Exception e) {
            log("Batch sample error: %s", e.getMessage());
        }
        return new int[0];
    }

    /**
     * Coherent averaging: capture rawSamples, fold onto one period, return targetPoints averaged values.
     * Requires native C helper.
     * @param channel Channel to sample (0-7)
     * @param targetPoints Number of output points per period (e.g. 2000)
     * @param rawSamples Number of raw samples to capture (e.g. 10000)
     * @return CoherentResult, or null if detection fails
     */
    public CoherentResult sampleCoherent(int channel, int targetPoints, int rawSamples) throws Exception {
        if (!initialized) throw new IllegalStateException("MCP3208 not initialized");
        if (!hasNativeHelper) throw new IllegalStateException("Native helper required for coherent mode");

        String helper = System.getProperty("user.home") + "/adc_sample";
        ProcessBuilder pb = new ProcessBuilder(helper,
                String.valueOf(channel), String.valueOf(rawSamples),
                "coherent", String.valueOf(targetPoints));
        pb.redirectErrorStream(false);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String valuesLine = reader.readLine();   // averaged values
        String periodLine = reader.readLine();   // period seconds
        String freqLine = reader.readLine();     // frequency Hz
        String cyclesLine = reader.readLine();   // cycles averaged
        process.waitFor();

        if (valuesLine == null || valuesLine.isEmpty()) return null;

        String[] parts = valuesLine.trim().split("\\s+");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }

        double period = 0, freq = 0;
        int cycles = 0;
        try { if (periodLine != null) period = Double.parseDouble(periodLine.trim()); } catch (NumberFormatException ignored) {}
        try { if (freqLine != null) freq = Double.parseDouble(freqLine.trim()); } catch (NumberFormatException ignored) {}
        try { if (cyclesLine != null) cycles = Integer.parseInt(cyclesLine.trim()); } catch (NumberFormatException ignored) {}

        lastMeasuredFrequency = freq;
        return new CoherentResult(values, period, freq, cycles);
    }

    /**
     * Dual-channel coherent averaging: capture both channels interleaved,
     * use CH1 for period detection, fold both onto one period.
     * @return CoherentResult[2] where [0]=CH1, [1]=CH2 (same period/freq/cycles)
     */
    public CoherentResult[] sampleCoherentDual(int ch1, int ch2, int targetPoints, int rawSamples) throws Exception {
        if (!initialized) throw new IllegalStateException("MCP3208 not initialized");
        if (!hasNativeHelper) throw new IllegalStateException("Native helper required for coherent mode");

        String helper = System.getProperty("user.home") + "/adc_sample";
        ProcessBuilder pb = new ProcessBuilder(helper,
                String.valueOf(ch1), String.valueOf(rawSamples),
                "coherent", String.valueOf(targetPoints), String.valueOf(ch2));
        pb.redirectErrorStream(false);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String ch1Line = reader.readLine();     // CH1 averaged values
        String ch2Line = reader.readLine();     // CH2 averaged values
        String periodLine = reader.readLine();  // period seconds
        String freqLine = reader.readLine();    // frequency Hz
        String cyclesLine = reader.readLine();  // cycles averaged
        process.waitFor();

        if (ch1Line == null || ch1Line.isEmpty()) return null;

        int[] values1 = parseIntLine(ch1Line);
        int[] values2 = (ch2Line != null && !ch2Line.isEmpty()) ? parseIntLine(ch2Line) : new int[0];

        double period = 0, freq = 0;
        int cycles = 0;
        try { if (periodLine != null) period = Double.parseDouble(periodLine.trim()); } catch (NumberFormatException ignored) {}
        try { if (freqLine != null) freq = Double.parseDouble(freqLine.trim()); } catch (NumberFormatException ignored) {}
        try { if (cyclesLine != null) cycles = Integer.parseInt(cyclesLine.trim()); } catch (NumberFormatException ignored) {}

        lastMeasuredFrequency = freq;
        return new CoherentResult[] {
            new CoherentResult(values1, period, freq, cycles),
            new CoherentResult(values2, period, freq, cycles)
        };
    }

    private int[] parseIntLine(String line) {
        String[] parts = line.trim().split("\\s+");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }
        return values;
    }

    /**
     * Get the duration of the last sampleFast call's sampling loop (seconds).
     */
    public double getLastSampleDurationSeconds() {
        return lastSampleDurationSeconds;
    }

    /**
     * Get the frequency measured by the native C helper using per-sample timestamps.
     * Returns 0 if not available (Python fallback or no signal detected).
     */
    public double getLastMeasuredFrequency() {
        return lastMeasuredFrequency;
    }

    /**
     * Continuously sample a channel at specified rate
     * @param channel Channel to sample
     * @param samples Number of samples to collect
     * @param delayMs Delay between samples in milliseconds
     * @return Array of raw samples
     */
    public int[] sample(int channel, int samples, int delayMs) throws Exception {
        int[] data = new int[samples];
        for (int i = 0; i < samples; i++) {
            data[i] = readRaw(channel);
            if (delayMs > 0 && i < samples - 1) {
                Thread.sleep(delayMs);
            }
        }
        return data;
    }

    public double getVref() {
        return VREF;
    }

    public int getMaxValue() {
        return MAX_VALUE;
    }

    public int getResolutionBits() {
        return 12;
    }

    // ========== Shared Continuous Sampling ==========

    public synchronized void startContinuousSampling(int channel, int samples) {
        samplerChannel = channel;
        samplerSamples = samples;
        activeConsumers.incrementAndGet();
        if (samplerThread == null || !samplerThread.isAlive()) {
            samplerRunning = true;
            samplerThread = new Thread(() -> {
                while (samplerRunning) {
                    try {
                        int[] raw = sampleFast(samplerChannel, samplerSamples);
                        latestRawSamples = raw;
                        latestSampleDuration = lastSampleDurationSeconds;
                        latestFrequency = lastMeasuredFrequency;
                        Thread.sleep(33);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Continue on sampling errors
                    }
                }
            }, "adc-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }
    }

    public synchronized void stopContinuousSampling() {
        if (activeConsumers.decrementAndGet() <= 0) {
            activeConsumers.set(0);
            dualChannelMode = false;
            samplerChannelY = -1;
            stopSamplerThread();
        }
    }

    private void stopSamplerThread() {
        samplerRunning = false;
        if (samplerThread != null) {
            samplerThread.interrupt();
            samplerThread = null;
        }
    }

    public int[] getLatestSamples() {
        return latestRawSamples;
    }

    public double getLatestSampleDuration() {
        return latestSampleDuration;
    }

    public double getLatestFrequency() {
        return latestFrequency;
    }

    public void setSamplerChannel(int channel) {
        samplerChannel = channel;
    }

    public void setSamplerSamples(int samples) {
        samplerSamples = samples;
    }

    // ========== Dual-Channel Sampling (X-Y Plot) ==========

    /**
     * Fast interleaved dual-channel sampling via Python.
     * Alternates CH_X, CH_Y reads in a tight loop for paired samples.
     * @param chX X-axis channel (0-7)
     * @param chY Y-axis channel (0-7)
     * @param pairs Number of (X,Y) sample pairs
     * @return int[2][] where [0]=X samples, [1]=Y samples
     */
    public int[][] sampleFastDualChannel(int chX, int chY, int pairs) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("MCP3208 not initialized");
        }

        String pythonCode = String.format(
            "import spidev,time\n" +
            "spi=spidev.SpiDev()\n" +
            "spi.open(1,0)\n" +
            "spi.max_speed_hz=1000000\n" +
            "spi.mode=0\n" +
            "chX=%d\n" +
            "chY=%d\n" +
            "cmdX=[0x06|((chX&0x04)>>2),(chX&0x03)<<6,0x00]\n" +
            "cmdY=[0x06|((chY&0x04)>>2),(chY&0x03)<<6,0x00]\n" +
            "xs=[]\n" +
            "ys=[]\n" +
            "t0=time.monotonic()\n" +
            "for _ in range(%d):\n" +
            "  r=spi.xfer2(list(cmdX))\n" +
            "  xs.append(((r[1]&0x0F)<<8)|r[2])\n" +
            "  r=spi.xfer2(list(cmdY))\n" +
            "  ys.append(((r[1]&0x0F)<<8)|r[2])\n" +
            "t1=time.monotonic()\n" +
            "print(' '.join(map(str,xs)))\n" +
            "print(' '.join(map(str,ys)))\n" +
            "print(t1-t0)\n" +
            "spi.close()",
            chX, chY, pairs
        );

        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", pythonCode);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String xLine = reader.readLine();
            String yLine = reader.readLine();
            String timeLine = reader.readLine();
            process.waitFor();

            if (timeLine != null) {
                try { lastSampleDurationSeconds = Double.parseDouble(timeLine.trim()); }
                catch (NumberFormatException ignored) {}
            }

            int[][] result = new int[2][];
            if (xLine != null && !xLine.isEmpty() && yLine != null && !yLine.isEmpty()) {
                String[] xParts = xLine.trim().split("\\s+");
                String[] yParts = yLine.trim().split("\\s+");
                result[0] = new int[xParts.length];
                result[1] = new int[yParts.length];
                for (int i = 0; i < xParts.length; i++) {
                    result[0][i] = Integer.parseInt(xParts[i]);
                }
                for (int i = 0; i < yParts.length; i++) {
                    result[1][i] = Integer.parseInt(yParts[i]);
                }
                return result;
            }
        } catch (Exception e) {
            log("Dual-channel sample error: %s", e.getMessage());
        }
        return new int[][] { new int[0], new int[0] };
    }

    /**
     * Start continuous dual-channel sampling for X-Y plotting.
     */
    public synchronized void startDualContinuousSampling(int chX, int chY, int pairs) {
        samplerChannel = chX;
        samplerChannelY = chY;
        samplerSamples = pairs;
        dualChannelMode = true;
        activeConsumers.incrementAndGet();
        if (samplerThread == null || !samplerThread.isAlive()) {
            samplerRunning = true;
            samplerThread = new Thread(() -> {
                while (samplerRunning) {
                    try {
                        if (dualChannelMode) {
                            int[][] xy = sampleFastDualChannel(samplerChannel, samplerChannelY, samplerSamples);
                            latestRawSamplesX = xy[0];
                            latestRawSamplesY = xy[1];
                            latestSampleDuration = lastSampleDurationSeconds;
                        } else {
                            int[] raw = sampleFast(samplerChannel, samplerSamples);
                            latestRawSamples = raw;
                            latestSampleDuration = lastSampleDurationSeconds;
                            latestFrequency = lastMeasuredFrequency;
                        }
                        Thread.sleep(33);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Continue on sampling errors
                    }
                }
            }, "adc-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }
    }

    public int[] getLatestSamplesX() {
        return latestRawSamplesX;
    }

    public int[] getLatestSamplesY() {
        return latestRawSamplesY;
    }

    public void setSamplerChannels(int chX, int chY) {
        samplerChannel = chX;
        samplerChannelY = chY;
    }

    @Override
    public void close() {
        log("Shutting down...");
        activeConsumers.set(0);
        stopSamplerThread();
        initialized = false;
        log("Shutdown complete");
    }
}
