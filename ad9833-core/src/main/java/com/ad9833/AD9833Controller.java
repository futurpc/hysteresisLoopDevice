package com.ad9833;

import java.io.*;

/**
 * AD9833 Programmable Waveform Generator Controller
 * Uses pigpio via command line (pigs) for reliable SPI communication
 *
 * Wiring (SPI0):
 *   SCLK  -> GPIO 11 (Pin 23)
 *   SDATA -> GPIO 10 (Pin 19)
 *   FSYNC -> GPIO 8  (Pin 24) - CE0
 *   VCC   -> 3.3V
 *   DGND  -> GND
 *   AGND  -> GND (IMPORTANT: must be connected!)
 */
public class AD9833Controller implements AutoCloseable {

    // AD9833 Control Register Bits
    private static final int CTRL_B28     = 1 << 13;
    private static final int CTRL_RESET   = 1 << 8;
    private static final int CTRL_OPBITEN = 1 << 5;
    private static final int CTRL_DIV2    = 1 << 3;
    private static final int CTRL_MODE    = 1 << 1;

    // Register addresses
    private static final int FREQ0_REG = 0x4000;
    private static final int PHASE0_REG = 0xC000;

    private final long refClock;
    private int controlRegister;
    private final boolean verbose;
    private int spiHandle = -1;

    private double currentFrequency = 0;
    private double currentPhase = 0;
    private Waveform currentWaveform = Waveform.SINE;
    private volatile boolean running = false;

    // Singleton
    private static AD9833Controller sharedInstance;

    public enum Waveform {
        SINE,
        TRIANGLE,
        SQUARE
    }

    public AD9833Controller(long referenceClockHz, boolean verbose) throws Exception {
        this.refClock = referenceClockHz;
        this.controlRegister = CTRL_B28;
        this.verbose = verbose;

        log("Initializing AD9833 Controller (pigpio mode)");
        log("Reference clock: %,d Hz", referenceClockHz);

        // Open SPI using pigpio
        String result = pigs("spio 0 1000000 2");
        spiHandle = Integer.parseInt(result.trim());
        log("SPI opened, handle: %d", spiHandle);

        // Reset the device
        reset();
    }

    public AD9833Controller(long referenceClockHz) throws Exception {
        this(referenceClockHz, false);
    }

    public AD9833Controller() throws Exception {
        this(25_000_000, false);
    }

    public static synchronized AD9833Controller getShared() throws Exception {
        if (sharedInstance == null) {
            sharedInstance = new AD9833Controller();
        }
        return sharedInstance;
    }

    public boolean isRunning() {
        return running;
    }

    private void log(String format, Object... args) {
        if (verbose) {
            System.out.printf("[DEBUG] " + format + "%n", args);
        }
    }

    private String pigs(String command) throws Exception {
        String[] parts = command.split(" ");
        ProcessBuilder pb = new ProcessBuilder();
        // Use full path for pigs since /usr/local/bin may not be in PATH
        pb.command().add("/usr/local/bin/pigs");
        for (String part : parts) {
            pb.command().add(part);
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line);
        }

        process.waitFor();
        return output.toString();
    }

    private void writeWord(int word) throws Exception {
        int highByte = (word >> 8) & 0xFF;
        int lowByte = word & 0xFF;

        log("  SPI WRITE: 0x%04X = [0x%02X, 0x%02X]", word, highByte, lowByte);

        pigs(String.format("spiw %d %d %d", spiHandle, highByte, lowByte));
    }

    public void reset() throws Exception {
        log("RESET: Setting B28=1, RESET=1");
        controlRegister = CTRL_B28 | CTRL_RESET;
        writeWord(controlRegister);

        Thread.sleep(10);

        log("RESET: Clearing RESET bit");
        controlRegister = CTRL_B28;
        writeWord(controlRegister);

        currentFrequency = 0;
        currentPhase = 0;
        currentWaveform = Waveform.SINE;
    }

    public void stop() throws Exception {
        log("STOP: Putting AD9833 in reset mode");
        writeWord(CTRL_B28 | CTRL_RESET);
        running = false;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public void setFrequency(double frequencyHz) throws Exception {
        log("SET FREQUENCY: %.2f Hz", frequencyHz);

        long freqReg = Math.round((frequencyHz * (1L << 28)) / refClock);
        freqReg &= 0x0FFFFFFF;

        log("  Frequency register: %d (0x%07X)", freqReg, freqReg);

        int lsb = (int) (freqReg & 0x3FFF);
        int msb = (int) ((freqReg >> 14) & 0x3FFF);

        writeWord(FREQ0_REG | lsb);
        writeWord(FREQ0_REG | msb);

        this.currentFrequency = frequencyHz;
    }

    public void setPhase(double phaseDegrees) throws Exception {
        log("SET PHASE: %.2f degrees", phaseDegrees);
        int phaseReg = (int) ((phaseDegrees / 360.0) * 4096) & 0x0FFF;
        writeWord(PHASE0_REG | phaseReg);

        this.currentPhase = phaseDegrees;
    }

    public void setWaveform(Waveform waveform) throws Exception {
        log("SET WAVEFORM: %s", waveform);

        controlRegister &= ~(CTRL_OPBITEN | CTRL_MODE | CTRL_DIV2);

        switch (waveform) {
            case SINE:
                break;
            case TRIANGLE:
                controlRegister |= CTRL_MODE;
                break;
            case SQUARE:
                controlRegister |= CTRL_OPBITEN | CTRL_DIV2;
                break;
        }

        writeWord(controlRegister);

        this.currentWaveform = waveform;
    }

    // Getters for current state
    public double getFrequency() {
        return currentFrequency;
    }

    public double getPhase() {
        return currentPhase;
    }

    public Waveform getWaveform() {
        return currentWaveform;
    }

    public long getReferenceClock() {
        return refClock;
    }

    public double getMaxFrequency() {
        return refClock / 2.0;
    }

    @Override
    public void close() {
        log("Shutting down...");
        if (spiHandle >= 0) {
            try {
                pigs("spic " + spiHandle);
            } catch (Exception e) {
                // Ignore
            }
            spiHandle = -1;
        }
        log("Shutdown complete");
    }
}
