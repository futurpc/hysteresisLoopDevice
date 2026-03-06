# Claude Context - Hysteresis Loop Device

## Project Overview

Java + C application for Raspberry Pi that measures magnetic hysteresis loops (B-H curves).
Three main capabilities:
- **Waveform Generator** - AD9833 programmable waveform generator (SPI0)
- **Signal Analyzer** - MCP3208 12-bit ADC oscilloscope display (SPI1)
- **Hysteresis Loop** - Dual-channel coherent averaging to construct B-H curves

Multi-module Maven project with CLI, touchscreen GUI, and web server.

## Hardware Setup

- **Target Device**: Raspberry Pi 4 (Debian 13 Trixie / RPi OS Lite)
- **Waveform Generator**: AD9833 module (7-pin) on SPI0
- **ADC**: MCP3208 12-bit 8-channel on SPI1
- **Display**: 7-inch touchscreen (800x480)
- **Reference Clock**: 25 MHz (AD9833 default)

## Raspberry Pi Access

See `pi-credentials.local` for connection details (not committed to git).

## AD9833 Wiring (SPI0)

**CRITICAL: Add 100nF (0.1µF/104) ceramic capacitor between VCC and GND!**

| Pin | Connect to | Purpose |
|-----|------------|---------|
| VCC | 3.3V + 100nF cap to GND | Power (decoupled) |
| DGND | GND | Digital ground |
| SDATA | GPIO 10 (Pin 19) | SPI MOSI |
| SCLK | GPIO 11 (Pin 23) | SPI Clock |
| FSYNC | GPIO 8 (Pin 24) | SPI CE0 |
| **AGND** | **GND** | **Analog ground - MUST connect!** |
| OUT | Oscilloscope/MCP3208 | Signal output |

**If AGND is not connected, the OUT pin will show 0V!**

## MCP3208 Wiring (SPI1)

**CRITICAL: Add two 100nF (0.1µF/104) ceramic capacitors!**

| MCP3208 Pin | Pin # | Connect to | Purpose |
|-------------|-------|------------|---------|
| CH1-CH7 | 2-8 | Analog inputs | 0-3.3V range |
| DGND | 9 | GND | Digital ground |
| CS/SHDN | 10 | GPIO 18 (Pin 12) | SPI1 CE0 |
| DIN | 11 | GPIO 20 (Pin 38) | SPI1 MOSI |
| DOUT | 12 | GPIO 19 (Pin 35) | SPI1 MISO |
| CLK | 13 | GPIO 21 (Pin 40) | SPI1 Clock |
| AGND | 14 | GND | Analog ground |
| VREF | 15 | 3.3V + 100nF cap | Reference (decoupled) |
| VDD | 16 | 3.3V + 100nF cap | Power (decoupled) |

## Project Structure

```
ad9833-controller/
├── pom.xml                           # Parent POM (multi-module, Java 21)
├── README.md                         # User documentation
├── CLAUDE.md                         # This file
├── native/                           # C native code (runs on Pi)
│   └── adc_coherent.c                # MCP3208 sampler with coherent averaging (615 lines)
├── splash/                           # Boot splash files
├── ad9833-core/                      # Shared library
│   └── src/main/java/com/ad9833/
│       ├── AD9833Controller.java     # Waveform generator via pigpio CLI (239 lines)
│       ├── MCP3208Controller.java    # ADC reader, coherent averaging orchestrator (651 lines)
│       └── AD9833WebServer.java      # Embedded HTTP server for phone control (1318 lines)
├── ad9833-cli/                       # Command line interface
│   └── src/main/java/com/ad9833/cli/
│       └── Main.java                 # CLI entry point (126 lines)
└── ad9833-ui/                        # JavaFX touchscreen GUI
    └── src/main/java/com/ad9833/ui/
        ├── Launcher.java             # JAR entry point (12 lines)
        ├── MainMenuApp.java          # Main menu router (329 lines)
        ├── AD9833App.java            # Waveform generator UI (398 lines)
        ├── SignalAnalyzerApp.java    # ADC oscilloscope UI (1250 lines)
        ├── HysteresisLoopApp.java   # B-H curve display UI (1089 lines)
        ├── WiFiApp.java              # WiFi manager with on-screen keyboard (575 lines)
        └── ConfigPersistence.java    # Save/load app settings (50 lines)
```

**Total: ~6,744 lines** (11 Java files, 1 C file)

## Implementation Details

### Why pigpio CLI instead of Pi4J?

Pi4J v2 didn't work with the AD9833 despite correct data. Solution: use `pigs` CLI via ProcessBuilder.
- **Must use full path `/usr/local/bin/pigs`** when running from X session

### Native C ADC Sampler (`native/adc_coherent.c`)

High-performance ADC reader compiled and run on the Pi. Modes:
- **Legacy**: `adc_sample <ch> <count>` — raw samples + duration + frequency
- **Single-channel coherent**: `adc_sample <ch> <count> coherent <points>` — harmonic-fit averaged waveform
- **Dual-channel coherent**: `adc_sample <ch> <count> coherent <points> <ch2>` — two channels for B-H curves

Build on Pi: `gcc -O3 -Wall -o adc_sample adc_coherent.c -lm`

### Coherent Averaging Algorithm

Pipeline: Raw ADC → Period Detection (zero-crossings) → Phase Folding → Harmonic Fit (least squares) → Evaluation → Phase Normalization

Key details:
- ~10,000 raw samples → 2,000 output points per period
- Harmonics: up to K=12 (no clipping), K=1 (>2% clipping)
- Chebyshev recurrence for efficient basis evaluation
- Gaussian elimination with partial pivoting for solving normal equations
- Clipped samples (≤5 or ≥4090) excluded from fit, reconstructed by model

### AD9833WebServer (Embedded HTTP)

Runs on port 8080. Provides full waveform generator + signal analyzer control from phone browser.
- Complete HTML/CSS/JS UI embedded as string constants in Java
- REST-like endpoints for frequency/phase/waveform control and ADC reading
- QR code displayed on main menu for easy phone access

### SPI Configuration

**AD9833 (SPI0):**
- Channel: 0 (CE0), Speed: 1 MHz, Mode: 2

**MCP3208 (SPI1):**
- Channel: 1 with aux flag (256), Speed: 1 MHz, Mode: 0
- Native C code uses `/dev/spidev1.0` at 1.5 MHz

### AD9833 Register Commands

| Command | Value | Purpose |
|---------|-------|---------|
| Reset | 0x2100 | B28=1, RESET=1 |
| B28 mode | 0x2000 | B28=1, RESET=0 |
| FREQ0 | 0x4000 + data | Frequency register |
| PHASE0 | 0xC000 + data | Phase register |
| Triangle | 0x2002 | MODE bit set |
| Square | 0x2028 | OPBITEN + DIV2 |

## GUI Details

### Main Menu
- Routes to: Waveform Generator, Signal Analyzer, Hysteresis Loop, WiFi, QR Code
- Dark theme with accent colors

### Waveform Generator (AD9833App)
- Frequency slider (logarithmic) + presets (100, 440, 1k, 10k, 100k, 1M Hz)
- Phase slider (0-360°)
- Waveform selection (Sine, Triangle, Square)
- START/STOP buttons

### Signal Analyzer (SignalAnalyzerApp)
- Real-time oscilloscope display (Canvas)
- Channel selector (CH1-CH7; CH0 excluded — not working)
- Live voltage reading with Min/Max statistics
- Coherent averaging mode for clean single-period display
- ~60 Hz refresh rate

### Hysteresis Loop (HysteresisLoopApp)
- Dual-channel coherent capture (CH_X vs CH_Y)
- X-Y parametric plot of B-H curve
- AC coupling (mean subtracted) to center the loop
- Channel selector for X and Y axes

### WiFi Manager (WiFiApp)
- Scan and connect to WiFi networks
- On-screen keyboard for password entry
- Touch-optimized for 7-inch display

### Optimizations for 7-inch Touchscreen
- Resolution: 800x480 (maximized)
- Cursor: Hidden for touch
- Large touch-friendly buttons
- Font sizes: 14-18px

## Build & Deploy — Dev Cycle

Every change follows this cycle: **build → commit → deploy → restart**

```bash
# 1. Build locally
cd ad9833-controller
mvn clean package

# 2. Commit (when changes are ready)
git add <files> && git commit -m "message"

# 3. Deploy jar to Pi
sshpass -p 'spartak1' scp ad9833-ui/target/ad9833-ui.jar ras0001@192.168.68.179:~/

# 4. Kill old app and restart on Pi
sshpass -p 'spartak1' ssh ras0001@192.168.68.179 "pkill -f 'ad9833-ui.jar' 2>/dev/null; sleep 1; DISPLAY=:0 nohup java --module-path /usr/share/openjfx/lib --add-modules javafx.controls,javafx.fxml -jar ~/ad9833-ui.jar > /dev/null 2>&1 &"

# 5. Verify app is running
sshpass -p 'spartak1' ssh ras0001@192.168.68.179 "pgrep -f ad9833-ui.jar"
```

**Pi connection**: `ssh ras0001@192.168.68.179` (see `pi-credentials.local`)

## Boot Splash (Plymouth)

Custom theme with hysteresis loop logo:
- **Theme location**: `/usr/share/plymouth/themes/hysteresis/`
- **Setup script**: `~/splash/setup-splash.sh`

## Debugging Tips

1. **AD9833 no output (0V)?** → Check AGND + 100nF capacitor
2. **MCP3208 unstable readings?** → Check 100nF caps on VDD and VREF
3. **SPI not working?** → Run `pigs t` to verify pigpiod
4. **pigs not found in GUI?** → Uses full path `/usr/local/bin/pigs`
5. **Coherent averaging fails?** → Need ≥3 zero-crossings; check signal amplitude and frequency
6. **CH0 not working** → Known issue; CH0 is excluded from all UI channel selectors

## Lessons Learned

1. **AGND must be connected** - AD9833 has separate digital and analog grounds
2. **100nF capacitors are essential** - Power supply decoupling prevents noise
3. **Pi4J SPI didn't work** - Unknown reason, pigpio CLI works reliably
4. **PATH differs in X session** - Must use full path for pigs
5. **SPI1 for second device** - Avoids conflicts with AD9833 on SPI0
6. **7-inch display is 800x480** - UI must be compact
7. **Java 17 not available** - Using Java 21 on Debian Trixie
8. **pigpio built from source** - Not in apt repos
9. **Native C for ADC speed** - Java ProcessBuilder too slow for tight sampling loops
10. **Clipping handling matters** - Reduce harmonics to K=1 when ADC clips to avoid Gibbs oscillations
