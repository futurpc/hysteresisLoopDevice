package com.ad9833.ui;

/**
 * Launcher class to work around JavaFX module issues with shaded JARs
 * Now launches the main menu for routing between modules
 */
public class Launcher {
    public static void main(String[] args) {
        MainMenuApp.main(args);
    }
}
