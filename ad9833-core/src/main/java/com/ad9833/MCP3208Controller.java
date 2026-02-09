package com.ad9833;

import java.io.*;

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

    private final boolean verbose;
    private boolean initialized = false;

    public MCP3208Controller(boolean verbose) throws Exception {
        this.verbose = verbose;
        log("Initializing MCP3208 Controller (spidev mode)");

        // Test that spidev is available
        String test = readChannelViaPython(0);
        if (test != null) {
            initialized = true;
            log("SPI1 initialized successfully");
        } else {
            throw new Exception("Failed to initialize SPI1");
        }
    }

    public MCP3208Controller() throws Exception {
        this(false);
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

        String pythonCode = String.format(
            "import spidev\n" +
            "spi=spidev.SpiDev()\n" +
            "spi.open(1,0)\n" +
            "spi.max_speed_hz=1000000\n" +
            "spi.mode=0\n" +
            "ch=%d\n" +
            "vals=[]\n" +
            "for _ in range(%d):\n" +
            "  cmd=[0x06|((ch&0x04)>>2),(ch&0x03)<<6,0x00]\n" +
            "  r=spi.xfer2(cmd)\n" +
            "  vals.append(((r[1]&0x0F)<<8)|r[2])\n" +
            "print(' '.join(map(str,vals)))\n" +
            "spi.close()",
            channel, samples
        );

        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", pythonCode);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();

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

    @Override
    public void close() {
        log("Shutting down...");
        initialized = false;
        log("Shutdown complete");
    }
}
