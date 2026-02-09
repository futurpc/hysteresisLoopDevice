# Hysteresis Loop Device

Java application for Raspberry Pi with two modules:
- **Waveform Generator** - AD9833 programmable waveform generator (Sine, Triangle, Square)
- **Signal Analyzer** - MCP3208 12-bit ADC for reading and visualizing signals

## Project Structure

```
ad9833-controller/
├── pom.xml                    # Parent POM (multi-module)
├── ad9833-core/               # Shared library
│   └── src/main/java/com/ad9833/
│       ├── AD9833Controller.java   # Waveform generator
│       └── MCP3208Controller.java  # ADC reader
├── ad9833-cli/                # Command line interface
│   └── src/main/java/com/ad9833/cli/Main.java
└── ad9833-ui/                 # JavaFX touchscreen GUI
    └── src/main/java/com/ad9833/ui/
        ├── MainMenuApp.java        # Main menu router
        ├── AD9833App.java          # Generator UI
        ├── SignalAnalyzerApp.java  # ADC visualization UI
        └── Launcher.java           # JAR entry point
```

## Raspberry Pi Connection

| Property | Value |
|----------|-------|
| OS | Debian 13 (Trixie) / Raspberry Pi OS Lite |
| Display | 7-inch touchscreen (800x480) |

Credentials are stored locally in `pi-credentials.local` (not committed to git).

---

## AD9833 Waveform Generator Wiring

**IMPORTANT:**
- Both DGND and AGND must be connected to GND!
- Add 100nF (0.1µF) ceramic capacitor between VCC and GND for stability!

```
    ┌──────────────────────────────────────────────────────────────────────────┐
    │                            AD9833 WIRING                                 │
    └──────────────────────────────────────────────────────────────────────────┘

          AD9833 Module (7-pin)                      Raspberry Pi
         ┌─────────────────────┐                  ┌──────────────────┐
         │                     │                  │   GPIO Header    │
         │  VCC   (pin 1) ─────┼────────────────────► Pin 1  (3.3V)  │
         │                     │                  │                  │
         │  DGND  (pin 2) ─────┼────────────────────► Pin 6  (GND)   │
         │                     │                  │                  │
         │  SDATA (pin 3) ─────┼────────────────────► Pin 19 (GPIO10)│  SPI0 MOSI
         │                     │                  │                  │
         │  SCLK  (pin 4) ─────┼────────────────────► Pin 23 (GPIO11)│  SPI0 SCLK
         │                     │                  │                  │
         │  FSYNC (pin 5) ─────┼────────────────────► Pin 24 (GPIO8) │  SPI0 CE0
         │                     │                  │                  │
         │  AGND  (pin 6) ─────┼────────────────────► Pin 6  (GND)   │  ◄── MUST CONNECT!
         │                     │                  │                  │
         │  OUT   (pin 7) ─────┼──► Oscilloscope  └──────────────────┘
         │                     │   or MCP3208 CH0
         └─────────────────────┘


    POWER CONNECTION (with decoupling capacitor):

         3.3V (Pin 1) ─────────────┐
                                   │
                                ┌──┴──┐
                                │100nF│
                                │(104)│
                                └──┬──┘
                                   │
         GND (Pin 6) ──────────────┴───────────────┐
                                   │               │
                                   ▼               ▼
                            ┌─────────────────────────┐
                            │        AD9833           │
                            │   VCC  ◄─── 3.3V        │
                            │   DGND ◄─── GND         │
                            │   AGND ◄─── GND         │  ◄── BOTH grounds!
                            └─────────────────────────┘


    ╔══════════════════════════════════════════════════════════════════════════╗
    ║  CAPACITOR: 100nF (0.1µF) ceramic capacitor marked "104"                 ║
    ║                                                                          ║
    ║  • Place capacitor as CLOSE to the AD9833 VCC pin as possible!           ║
    ║  • Connects between VCC and GND for power supply decoupling              ║
    ║  • If AGND is not connected, the OUT pin will show 0V!                   ║
    ╚══════════════════════════════════════════════════════════════════════════╝
```

### AD9833 Pin Mapping

| AD9833 Pin | Connect To | Notes |
|------------|------------|-------|
| VCC | 3.3V (Pin 1) + 100nF cap to GND | Power supply |
| DGND | GND (Pin 6) | Digital ground |
| SDATA | GPIO 10 (Pin 19) | SPI MOSI |
| SCLK | GPIO 11 (Pin 23) | SPI Clock |
| FSYNC | GPIO 8 (Pin 24) | SPI CE0 (Chip Select) |
| **AGND** | **GND (Pin 6)** | **Analog ground - MUST connect!** |
| OUT | Oscilloscope/ADC | Signal output (~0.6Vpp) |

---

## MCP3208 ADC Wiring

**IMPORTANT:** Add 100nF (0.1µF) ceramic capacitors for stable readings!

```
    ┌──────────────────────────────────────────────────────────────────────────┐
    │                            MCP3208 WIRING                                │
    └──────────────────────────────────────────────────────────────────────────┘

                    MCP3208                           Raspberry Pi
               ┌──────┴──────┐                    ┌──────────────────┐
               │  1 ●    16  │                    │   GPIO Header    │
        CH0 ───┤  2      15  ├─── VREF            │                  │
        CH1 ───┤  3      14  ├─── AGND            │  Pin 1  = 3.3V   │
        CH2 ───┤  4      13  ├─── CLK ──────────────► Pin 40 (GPIO21)│
        CH3 ───┤  5      12  ├─── DOUT ─────────────► Pin 35 (GPIO19)│
        CH4 ───┤  6      11  ├─── DIN ──────────────► Pin 38 (GPIO20)│
        CH5 ───┤  7      10  ├─── CS ───────────────► Pin 12 (GPIO18)│
        CH6 ───┤  8       9  ├─── DGND              │  Pin 6  = GND   │
        CH7 ───┤             │                    │                  │
               └─────────────┘                    └──────────────────┘


    POWER CONNECTIONS (with decoupling capacitors):

         3.3V (Pin 1) ────────┬─────────────────┬─────────────────┐
                              │                 │                 │
                           ┌──┴──┐           ┌──┴──┐              │
                           │100nF│           │100nF│              │
                           │(104)│           │(104)│              │
                           └──┬──┘           └──┬──┘              │
                              │                 │                 │
                              │                 │                 │
         GND (Pin 6) ─────────┴─────────────────┴─────────────────┤
                              │                 │                 │
                              ▼                 ▼                 │
                       ┌─────────────────────────────┐            │
                       │         MCP3208             │            │
                       │   Pin 14 (AGND) ◄───────────┘            │
                       │   Pin  9 (DGND) ◄────────────────────────┘
                       │   Pin 15 (VREF) ◄──── 3.3V (through cap)
                       │   Pin 16 (VDD)  ◄──── 3.3V (through cap)
                       └─────────────────────────────┘


    ANALOG INPUTS (directly to your signal sources):

         AD9833 OUT ──────────► CH0 (Pin 1)   ← Connect signal here
         (or other) ──────────► CH1 (Pin 2)
                    ──────────► CH2 (Pin 3)
                    ──────────► CH3 (Pin 4)
                    ──────────► CH4 (Pin 5)
                    ──────────► CH5 (Pin 6)
                    ──────────► CH6 (Pin 7)
                    ──────────► CH7 (Pin 8)


    ╔══════════════════════════════════════════════════════════════════════════╗
    ║  CAPACITORS: Two 100nF (0.1µF) ceramic capacitors marked "104"           ║
    ║                                                                          ║
    ║  • Place capacitors as CLOSE to the MCP3208 chip as possible!            ║
    ║  • One capacitor: VDD (pin 16) to AGND (pin 14)                          ║
    ║  • One capacitor: VREF (pin 15) to AGND (pin 14)                         ║
    ╚══════════════════════════════════════════════════════════════════════════╝
```

### MCP3208 Pin Mapping

| MCP3208 Pin | Pin # | Connect To | Notes |
|-------------|-------|------------|-------|
| CH0 | 1 | Analog input | Channel 0 (0-3.3V) |
| CH1 | 2 | Analog input | Channel 1 |
| CH2 | 3 | Analog input | Channel 2 |
| CH3 | 4 | Analog input | Channel 3 |
| CH4 | 5 | Analog input | Channel 4 |
| CH5 | 6 | Analog input | Channel 5 |
| CH6 | 7 | Analog input | Channel 6 |
| CH7 | 8 | Analog input | Channel 7 |
| DGND | 9 | GND (RPi Pin 6) | Digital ground |
| CS/SHDN | 10 | GPIO 18 (RPi Pin 12) | SPI1 CE0 |
| DIN | 11 | GPIO 20 (RPi Pin 38) | SPI1 MOSI |
| DOUT | 12 | GPIO 19 (RPi Pin 35) | SPI1 MISO |
| CLK | 13 | GPIO 21 (RPi Pin 40) | SPI1 Clock |
| AGND | 14 | GND (RPi Pin 6) | Analog ground |
| VREF | 15 | 3.3V + 100nF cap to GND | Reference voltage |
| VDD | 16 | 3.3V + 100nF cap to GND | Power supply |

---

## Installed Software on Pi

- Java 21 (OpenJDK 21.0.10)
- Maven 3.9.9
- pigpio (built from source, /usr/local/bin/pigpiod)
- X server (xorg, xinit, openbox) - for GUI
- OpenJFX 11 - for JavaFX GUI
- Plymouth - boot splash
- SPI enabled (both SPI0 and SPI1)

## Build

```bash
cd ad9833-controller
mvn clean package
```

This creates:
- `ad9833-cli/target/ad9833-cli.jar` - Command line interface
- `ad9833-ui/target/ad9833-ui.jar` - Touchscreen GUI (with main menu)

## Deploy to Raspberry Pi

```bash
# GUI version (includes main menu, generator, and analyzer)
scp ad9833-ui/target/ad9833-ui.jar <PI_USER>@<PI_IP>:~/

# CLI version (generator only)
scp ad9833-cli/target/ad9833-cli.jar <PI_USER>@<PI_IP>:~/
```

## GUI Usage

The GUI starts with a main menu offering two modules:

```bash
DISPLAY=:0 java --module-path /usr/share/openjfx/lib \
  --add-modules javafx.controls,javafx.fxml \
  -jar ad9833-ui.jar
```

### Main Menu
- **WAVEFORM GENERATOR** - Control AD9833 output
- **SIGNAL ANALYZER** - Read and visualize MCP3208 ADC
- **WIFI** - Manage WiFi connections with on-screen keyboard
- **QR Code** - Scan to open web control panel on your phone (port 8080)

### Waveform Generator Features
- Frequency slider with presets (100, 440, 1k, 10k, 100k, 1M Hz)
- Phase control (0-360°)
- Waveform selection (Sine, Triangle, Square)
- START/STOP buttons

### Signal Analyzer Features
- Real-time oscilloscope-style waveform display
- Channel selector (CH0-CH7)
- Live voltage reading
- Min/Max statistics

## CLI Usage

```bash
# Basic - 1 kHz sine wave
java -jar ad9833-cli.jar

# 440 Hz sine wave (A4 note)
java -jar ad9833-cli.jar -f 440

# 1 kHz triangle wave with 90° phase
java -jar ad9833-cli.jar -f 1000 -w TRIANGLE -p 90

# 5 kHz square wave
java -jar ad9833-cli.jar -f 5000 -w SQUARE
```

### CLI Options

| Option | Description | Default |
|--------|-------------|---------|
| `-f, --frequency` | Output frequency in Hz | 1000 |
| `-p, --phase` | Phase offset (0-360°) | 0 |
| `-w, --waveform` | SINE, TRIANGLE, or SQUARE | SINE |
| `-c, --clock` | Reference clock in Hz | 25000000 |
| `-v, --verbose` | Enable debug output | false |

## Auto-Start on Boot

The Pi is configured to auto-start the GUI on boot:

1. **Auto-login** - `/etc/systemd/system/getty@tty1.service.d/autologin.conf`
2. **Start X** - `~/.bash_profile` runs `startx ~/start-ui.sh`
3. **Launch app** - `~/start-ui.sh` starts the JavaFX app

## Boot Splash Screen

Custom Plymouth theme with:
- Hysteresis loop logo (glowing green B-H curve)
- "HYSTERESIS LOOP DEVICE" title
- Animated progress bar

To reinstall: `~/splash/setup-splash.sh`

## Output Specifications

**AD9833:**
- Amplitude: ~0.6V peak-to-peak
- DC Offset: ~0.3V
- Frequency Range: 0.1 Hz to 12.5 MHz

**MCP3208:**
- Resolution: 12-bit (0-4095)
- Input Range: 0 to VREF (3.3V)
- Sample Rate: ~100 ksps max

## Troubleshooting

**AD9833 - No output (0V):**
- Check AGND is connected to GND
- Verify 100nF capacitor is installed
- Check SPI wiring

**MCP3208 - No readings:**
- Check both AGND and DGND connected
- Verify 100nF capacitors on VDD and VREF
- Ensure SPI1 is enabled in config.txt

**pigpiod errors:**
- `pigs t` should return a number
- Start with `sudo pigpiod`

**Auto-start pigpiod:**
```bash
sudo systemctl enable pigpiod
sudo systemctl start pigpiod
```
