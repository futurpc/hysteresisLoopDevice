package com.ad9833.ui;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Simple Properties-based configuration persistence.
 * Stores settings in ~/hysteresis-device.properties.
 */
public class ConfigPersistence {

    private static final String PATH =
            System.getProperty("user.home") + "/hysteresis-device.properties";
    private static final Properties props = new Properties();

    static {
        try (FileInputStream in = new FileInputStream(PATH)) {
            props.load(in);
        } catch (Exception ignored) {}
    }

    public static String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key));
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        return v != null ? Boolean.parseBoolean(v) : def;
    }

    public static void put(String key, Object val) {
        props.setProperty(key, String.valueOf(val));
    }

    public static void save() {
        try (FileOutputStream out = new FileOutputStream(PATH)) {
            props.store(out, "Hysteresis Loop Device");
        } catch (Exception ignored) {}
    }
}
