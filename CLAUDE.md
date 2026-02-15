# Claude Context - Hysteresis Loop Device

## Project Overview

Java application for Raspberry Pi with two main modules:
- **Waveform Generator** - AD9833 programmable waveform generator (SPI0)
- **Signal Analyzer** - MCP3208 12-bit ADC for reading signals (SPI1)

Multi-module Maven project with CLI and touchscreen GUI versions.

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
| CH0-CH7 | 1-8 | Analog inputs | 0-3.3V range |
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
├── splash/                           # Boot splash files
├── ad9833-core/                      # Shared library
│   └── src/main/java/com/ad9833/
│       ├── AD9833Controller.java     # Waveform generator (SPI0)
│       └── MCP3208Controller.java    # ADC reader (SPI1)
├── ad9833-cli/                       # Command line interface
│   └── src/main/java/com/ad9833/cli/
│       └── Main.java
└── ad9833-ui/                        # JavaFX touchscreen GUI
    └── src/main/java/com/ad9833/ui/
        ├── MainMenuApp.java          # Main menu router
        ├── AD9833App.java            # Generator UI
        ├── SignalAnalyzerApp.java    # ADC visualization UI
        └── Launcher.java             # JAR entry point
```

## Implementation Details

### Why pigpio CLI instead of Pi4J?

Pi4J v2 didn't work with the AD9833 despite correct data. Solution: use `pigs` CLI via ProcessBuilder.
- **Must use full path `/usr/local/bin/pigs`** when running from X session

### SPI Configuration

**AD9833 (SPI0):**
- Channel: 0 (CE0), Speed: 1 MHz, Mode: 2

**MCP3208 (SPI1):**
- Channel: 1 with aux flag (256), Speed: 1 MHz, Mode: 0

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
- Routes to Waveform Generator or Signal Analyzer
- Dark theme with accent colors

### Waveform Generator
- Frequency slider (logarithmic) + presets
- Phase slider (0-360°)
- Waveform selection (Sine, Triangle, Square)
- START/STOP buttons

### Signal Analyzer
- Real-time oscilloscope display (Canvas)
- Channel selector (CH0-CH7)
- Live voltage reading
- Min/Max statistics
- ~60 Hz refresh rate

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

## Lessons Learned

1. **AGND must be connected** - AD9833 has separate digital and analog grounds
2. **100nF capacitors are essential** - Power supply decoupling prevents noise
3. **Pi4J SPI didn't work** - Unknown reason, pigpio CLI works reliably
4. **PATH differs in X session** - Must use full path for pigs
5. **SPI1 for second device** - Avoids conflicts with AD9833 on SPI0
6. **7-inch display is 800x480** - UI must be compact
7. **Java 17 not available** - Using Java 21 on Debian Trixie
8. **pigpio built from source** - Not in apt repos
