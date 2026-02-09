package com.ad9833.cli;

import com.ad9833.AD9833Controller;
import com.ad9833.AD9833Controller.Waveform;

public class Main {

    public static void main(String[] args) {
        double frequency = 1000.0;
        double phase = 0.0;
        Waveform waveform = Waveform.SINE;
        long refClock = 25_000_000;
        boolean verbose = false;
        boolean stop = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-f":
                case "--frequency":
                    if (i + 1 < args.length) {
                        frequency = Double.parseDouble(args[++i]);
                    }
                    break;
                case "-p":
                case "--phase":
                    if (i + 1 < args.length) {
                        phase = Double.parseDouble(args[++i]);
                    }
                    break;
                case "-w":
                case "--waveform":
                    if (i + 1 < args.length) {
                        waveform = Waveform.valueOf(args[++i].toUpperCase());
                    }
                    break;
                case "-c":
                case "--clock":
                    if (i + 1 < args.length) {
                        refClock = Long.parseLong(args[++i]);
                    }
                    break;
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-s":
                case "--stop":
                    stop = true;
                    break;
                case "-h":
                case "--help":
                    printHelp();
                    return;
            }
        }

        // Handle stop command
        if (stop) {
            try (AD9833Controller controller = new AD9833Controller(refClock, verbose)) {
                System.out.println("Stopping AD9833 output...");
                controller.stop();
                System.out.println("AD9833 stopped.");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            return;
        }

        double maxFreq = refClock / 2.0;
        if (frequency < 0.1 || frequency > maxFreq) {
            System.err.printf("Error: Frequency must be between 0.1 Hz and %.0f Hz%n", maxFreq);
            System.exit(1);
        }

        if (phase < 0 || phase > 360) {
            System.err.println("Error: Phase must be between 0 and 360 degrees");
            System.exit(1);
        }

        System.out.println("AD9833 Waveform Generator");
        System.out.println("========================");
        System.out.printf("Reference Clock: %,d Hz%n", refClock);
        System.out.printf("Frequency: %.2f Hz%n", frequency);
        System.out.printf("Phase: %.2f degrees%n", phase);
        System.out.printf("Waveform: %s%n", waveform);
        System.out.println();

        try (AD9833Controller controller = new AD9833Controller(refClock, verbose)) {
            controller.setFrequency(frequency);
            controller.setPhase(phase);
            controller.setWaveform(waveform);

            System.out.println("Output configured successfully!");
            System.out.printf("  %.2f Hz %s wave%n", frequency, waveform.toString().toLowerCase());
            System.out.println();
            System.out.println("Press Enter to stop...");
            System.in.read();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("AD9833 Waveform Generator Controller");
        System.out.println();
        System.out.println("Usage: java -jar ad9833-cli.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -f, --frequency <Hz>    Output frequency (default: 1000)");
        System.out.println("  -p, --phase <degrees>   Phase offset 0-360 (default: 0)");
        System.out.println("  -w, --waveform <type>   SINE, TRIANGLE, or SQUARE (default: SINE)");
        System.out.println("  -c, --clock <Hz>        Reference clock frequency (default: 25000000)");
        System.out.println("  -s, --stop              Stop output (reset AD9833)");
        System.out.println("  -v, --verbose           Enable verbose debug output");
        System.out.println("  -h, --help              Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar ad9833-cli.jar -f 440 -w SINE");
        System.out.println("  java -jar ad9833-cli.jar -f 1000 -w TRIANGLE -v");
        System.out.println("  java -jar ad9833-cli.jar --stop");
    }
}
