package com.spothud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to .minecraft/config/spothud.json. x/y are fractions of the screen so it survives resizes. */
public final class HudConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public float x = 0.02f;
    public float y = 0.05f;
    public float scale = 1.0f;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("spothud.json");
    }

    public static HudConfig load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                HudConfig c = GSON.fromJson(Files.readString(p), HudConfig.class);
                if (c != null) return c;
            }
        } catch (Exception ignored) {
        }
        return new HudConfig();
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (Exception ignored) {
        }
    }

    public void reset() {
        x = 0.02f;
        y = 0.05f;
        scale = 1.0f;
    }
}
