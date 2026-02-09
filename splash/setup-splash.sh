#!/bin/bash
# Setup Hysteresis Loop Device boot splash

set -e

echo "Installing Plymouth..."
sudo apt-get update
sudo apt-get install -y plymouth plymouth-themes imagemagick librsvg2-bin

echo "Creating theme directory..."
sudo mkdir -p /usr/share/plymouth/themes/hysteresis

echo "Converting SVG logo to PNG..."
rsvg-convert -w 400 -h 300 ~/splash/hysteresis-logo.svg -o ~/splash/logo.png

echo "Creating progress bar images..."
# Progress bar background (dark gray rounded rectangle)
convert -size 308x28 xc:none \
    -fill '#222244' \
    -draw "roundrectangle 0,0 307,27 8,8" \
    -stroke '#00ff88' -strokewidth 2 \
    -draw "roundrectangle 0,0 307,27 8,8" \
    ~/splash/progress-bg.png

# Progress bar fill (gradient green)
convert -size 300x20 xc:none \
    -fill 'gradient:#00ff88-#00ffcc' \
    -draw "roundrectangle 0,0 299,19 6,6" \
    ~/splash/progress-bar.png

echo "Installing theme files..."
sudo cp ~/splash/logo.png /usr/share/plymouth/themes/hysteresis/
sudo cp ~/splash/progress-bg.png /usr/share/plymouth/themes/hysteresis/
sudo cp ~/splash/progress-bar.png /usr/share/plymouth/themes/hysteresis/
sudo cp ~/splash/hysteresis.plymouth /usr/share/plymouth/themes/hysteresis/
sudo cp ~/splash/hysteresis.script /usr/share/plymouth/themes/hysteresis/

echo "Setting theme as default..."
sudo plymouth-set-default-theme hysteresis

echo "Updating initramfs..."
sudo update-initramfs -u

echo "Configuring boot for splash..."
# Add splash to cmdline.txt if not present
if ! grep -q "splash" /boot/firmware/cmdline.txt 2>/dev/null; then
    sudo sed -i 's/$/ quiet splash plymouth.ignore-serial-consoles/' /boot/firmware/cmdline.txt
elif ! grep -q "splash" /boot/cmdline.txt 2>/dev/null; then
    sudo sed -i 's/$/ quiet splash plymouth.ignore-serial-consoles/' /boot/cmdline.txt
fi

echo ""
echo "=========================================="
echo "Boot splash installed successfully!"
echo "Reboot to see the hysteresis logo splash."
echo "=========================================="
