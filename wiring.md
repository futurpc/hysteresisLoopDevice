# Raspberry Pi Wiring

## Power Rails

| Source | Connect to |
|--------|------------|
| 3.3V   | power line (+) |
| GND    | ground line (-) |

## AD9833 Waveform Generator (SPI0)

| Raspberry Pi | Connect to | Purpose |
|-------------|------------|---------|
| SPI MOSI (GPIO 10) | SDATA | SPI data |
| SPI SCLK (GPIO 11) | SCLK | SPI clock |
| SPI CE0 (GPIO 8) | FSYNC | Chip select |

| AD9833 Pin | Connect to | Purpose |
|-----------|------------|---------|
| VCC | power line (+) | Power |
| DGND | ground line (-) | Digital ground |
| AGND | ground line (-) | Analog ground |
| OUT | CH0 MCP3208 | Signal output |
| 100nF cap | VCC to ground line (-) | Decoupling |

## MCP3208 ADC (SPI1)

| Raspberry Pi | MCP3208 Pin | Purpose |
|-------------|-------------|---------|
| GPIO 18 | pin 10 (CS/SHDN) | SPI1 chip select |
| GPIO 19 | pin 12 (DOUT) | SPI1 MISO |
| GPIO 20 | pin 11 (DIN) | SPI1 MOSI |
| GPIO 21 | pin 13 (CLK) | SPI1 clock |

| MCP3208 Pin | Connect to | Purpose |
|------------|------------|---------|
| pin 9 (DGND) | ground line (-) | Digital ground |
| pin 14 (AGND) | ground line (-) | Analog ground |
| pin 15 (VREF) | power line (+) | Reference voltage |
| pin 16 (VDD) | power line (+) | Power |

## Decoupling Capacitors

| Capacitor | Between | Purpose |
|-----------|---------|---------|
| 100nF | pin 15 (VREF) MCP3208 ↔ ground line (-) | VREF decoupling |
| 100nF | pin 16 (VDD) MCP3208 ↔ ground line (-) | VDD decoupling |