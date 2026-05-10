package com.tuandev.fbsbarcode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = BuildConfig.class.getResourceAsStream("/app.properties")) {
            if (is != null) {
                PROPS.load(is);
            }
        } catch (IOException ignored) {
            // use defaults
        }
    }

    public static String getAppVersion() {
        return PROPS.getProperty("app.version", "0.0.0");
    }

    public static String getUpdateUrl() {
        return PROPS.getProperty("app.update.url", "https://github.com/tuanworlddev/FBSBarcode");
    }

    private BuildConfig() {}
}
