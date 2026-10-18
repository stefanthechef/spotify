package com.spothud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Talks to Windows' System Media Transport Controls through a small hidden PowerShell process.
 * That's the same thing that powers the media overlay in Windows, so it sees Spotify, YouTube in
 * Chrome/Edge/Firefox, etc. with no API keys or logins.
 */
public final class MediaBridge {
    public static final int ART_SIZE = 88;

    public record Snapshot(boolean active, String title, String artist, String app,
                           boolean playing, double pos, double dur, long stamp) {
        static final Snapshot NONE = new Snapshot(false, "", "", "", false, 0, 0, 0);

        public double livePos() {
            if (!playing) return pos;
            double p = pos + (System.nanoTime() - stamp) / 1e9;
            return dur > 0 ? Math.min(p, dur) : p;
        }
    }

    private volatile Snapshot snap = Snapshot.NONE;
    private volatile byte[] artPng;
    private volatile int artVersion;
    private volatile long holdUntil;
    private volatile boolean holdPlaying;

    private final boolean supported = System.getProperty("os.name", "").toLowerCase().contains("win");
    private Process process;
    private Path dir;
    private Path cmdFile;
    private int lastArt = 0;

    public boolean isSupported() {
        return supported;
    }

    public Snapshot snapshot() {
        return snap;
    }

    public byte[] artPng() {
        return artPng;
    }

    public int artVersion() {
        return artVersion;
    }

    public void start() {
        if (!supported) return;
        try {
            dir = FabricLoader.getInstance().getConfigDir().resolve("spothud");
            Files.createDirectories(dir);
            cmdFile = dir.resolve("cmd.txt");
            Files.deleteIfExists(cmdFile);
            Path script = dir.resolve("media.ps1");
            try (InputStream in = MediaBridge.class.getClassLoader().getResourceAsStream("assets/spothud/media.ps1")) {
                if (in == null) throw new IllegalStateException("media.ps1 missing from jar");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                    "-ParentPid", String.valueOf(ProcessHandle.current().pid()),
                    "-Dir", dir.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            Thread t = new Thread(this::readLoop, "SpotHud-Media");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            System.err.println("[SpotHud] Could not start media bridge: " + e);
        }
    }

    public void stop() {
        if (process != null) process.destroy();
    }

    private void readLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (!line.startsWith("{")) continue;
                try {
                    handle(JsonParser.parseString(line).getAsJsonObject());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        snap = Snapshot.NONE;
    }

    private void handle(JsonObject o) {
        if (!o.get("a").getAsBoolean()) {
            snap = Snapshot.NONE;
            return;
        }
        boolean playing = o.get("p").getAsBoolean();
        if (System.nanoTime() < holdUntil) playing = holdPlaying; // keep optimistic state briefly after a click
        int art = o.get("art").getAsInt();
        if (art != lastArt) {
            lastArt = art;
            loadArt(art);
        }
        snap = new Snapshot(true, str(o, "t"), str(o, "r"), str(o, "app"), playing,
                o.get("pos").getAsDouble(), o.get("dur").getAsDouble(), System.nanoTime());
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private void loadArt(int id) {
        byte[] result = null;
        if (id > 0) {
            Path f = dir.resolve("art_" + id + ".bin");
            try {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(f)));
                if (src != null) {
                    int s = Math.min(src.getWidth(), src.getHeight());
                    BufferedImage crop = src.getSubimage((src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
                    Image scaled = crop.getScaledInstance(ART_SIZE, ART_SIZE, Image.SCALE_SMOOTH);
                    BufferedImage out = new BufferedImage(ART_SIZE, ART_SIZE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = out.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(scaled, 0, 0, null);
                    g.dispose();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(out, "png", baos);
                    result = baos.toByteArray();
                }
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {
            }
        }
        artPng = result;
        artVersion++;
    }

    // ---- controls -------------------------------------------------------------------------

    private void send(String cmd) {
        if (cmdFile == null) return;
        try {
            Files.writeString(cmdFile, cmd);
        } catch (Exception ignored) {
        }
    }

    public void playPause() {
        Snapshot s = snap;
        if (!s.active()) return;
        holdPlaying = !s.playing();
        holdUntil = System.nanoTime() + 900_000_000L;
        snap = new Snapshot(true, s.title(), s.artist(), s.app(), !s.playing(), s.livePos(), s.dur(), System.nanoTime());
        send("toggle");
    }

    public void next() {
        send("next");
    }

    public void previous() {
        send("prev");
    }
}
