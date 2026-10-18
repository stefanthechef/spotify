package com.spothud;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;

/**
 * Draws the HUD and handles dragging / resizing / buttons.
 * Interaction only happens while the chat screen is open (that's when the cursor is free).
 */
public final class HudRenderer {
    // design size in GUI pixels at scale 1.0
    private static final int W = 200, H = 60;
    private static final int GREEN = 0xFF1DB954;
    private static final int BTN_CY = 37;
    private static final int PREV_CX = 100, PLAY_CX = 126, NEXT_CX = 152;

    private static final Identifier ART_ID = Identifier.fromNamespaceAndPath("spothud", "album_art");

    private final HudConfig cfg;
    private final MediaBridge bridge;

    private float alpha = 0f;
    private long lastNanos = System.nanoTime();

    private boolean dragging, prevDown;
    private double dragDX, dragDY;

    // last drawn bounds in GUI pixels (used by scroll handler)
    private boolean boundsValid;
    private float bx, by, bw, bh;

    private int artLoaded = -1;
    private boolean hasArtTexture;

    public HudRenderer(HudConfig cfg, MediaBridge bridge) {
        this.cfg = cfg;
        this.bridge = bridge;
    }

    // ------------------------------------------------------------------ render

    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        boundsValid = false;
        if (!cfg.enabled || mc.options.hideGui || mc.player == null) return;

        MediaBridge.Snapshot s = bridge.snapshot();
        boolean editing = mc.screen instanceof ChatScreen;

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float target = (s.active() || editing) ? 1f : 0f;
        alpha += (target - alpha) * Math.min(1f, dt * 10f);
        if (alpha < 0.01f) {
            dragging = false;
            return;
        }

        Window win = mc.getWindow();
        int sw = win.getGuiScaledWidth(), sh = win.getGuiScaledHeight();
        float scale = cfg.scale;
        float pw = W * scale, ph = H * scale;
        float x = clamp(cfg.x * sw, 0, Math.max(0, sw - pw));
        float y = clamp(cfg.y * sh, 0, Math.max(0, sh - ph));

        double mx = -1000, my = -1000;
        boolean down = false;
        if (editing) {
            mx = mc.mouseHandler.xpos() * sw / win.getScreenWidth();
            my = mc.mouseHandler.ypos() * sh / win.getScreenHeight();
            down = GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        }
        boolean over = mx >= x && mx <= x + pw && my >= y && my <= y + ph;
        double lx = (mx - x) / scale, ly = (my - y) / scale;

        // press: button click or start dragging
        if (down && !prevDown && over) {
            int hit = s.active() ? buttonAt(lx, ly) : 0;
            if (hit == 1) bridge.previous();
            else if (hit == 2) bridge.playPause();
            else if (hit == 3) bridge.next();
            else {
                dragging = true;
                dragDX = mx - x;
                dragDY = my - y;
            }
        }
        if (!down && dragging) {
            dragging = false;
            cfg.save();
        }
        if (dragging && down) {
            x = clamp((float) (mx - dragDX), 0, Math.max(0, sw - pw));
            y = clamp((float) (my - dragDY), 0, Math.max(0, sh - ph));
            cfg.x = x / sw;
            cfg.y = y / sh;
        }
        prevDown = down;

        bx = x;
        by = y;
        bw = pw;
        bh = ph;
        boundsValid = true;

        syncArt(mc);
        int hoverBtn = (editing && over && s.active()) ? buttonAt(lx, ly) : 0;
        draw(g, mc.font, s, x, y, scale, hoverBtn, editing, over || dragging);
    }

    private void draw(GuiGraphics g, Font font, MediaBridge.Snapshot s, float x, float y, float scale,
                      int hoverBtn, boolean editing, boolean highlight) {
        float al = alpha;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);

        // soft shadow
        for (int i = 1; i <= 4; i++) {
            rrect(g, -i, -i + 2, W + 2 * i, H + 2 * i, 7 + i, a(0x0D000000, al));
        }
        // border + body
        int border = highlight && editing ? a(0xAA1DB954, al) : a(0x30FFFFFF, al);
        rrect(g, 0, 0, W, H, 8, border);
        rrect(g, 1, 1, W - 2, H - 2, 7, a(0xEA101013, al));

        // album art (or placeholder)
        rrect(g, 8, 8, 44, 44, 4, a(0xFF26262B, al));
        if (hasArtTexture && alpha > 0.95f) {
            g.blit(RenderPipelines.GUI_TEXTURED, ART_ID, 8, 8, 0, 0, 44, 44,
                    MediaBridge.ART_SIZE, MediaBridge.ART_SIZE, MediaBridge.ART_SIZE, MediaBridge.ART_SIZE);
        }

        // text
        String title = s.active() ? s.title() : "Nothing playing";
        String sub;
        if (s.active()) sub = s.artist().isEmpty() ? s.app() : s.artist();
        else if (!bridge.isSupported()) sub = "Windows only";
        else sub = "Open Spotify or play something";
        g.drawString(font, fit(font, title, 132), 60, 8, a(0xFFFFFFFF, al), false);
        g.drawString(font, fit(font, sub, 132), 60, 18, a(0xFFA7A7A7, al), false);

        // buttons
        int idle = a(0xFFB3B3B3, al), hot = a(0xFFFFFFFF, al);
        // prev
        int pc = hoverBtn == 1 ? hot : idle;
        g.fill(PREV_CX - 6, BTN_CY - 4, PREV_CX - 4, BTN_CY + 5, pc);
        tri(g, PREV_CX - 3, BTN_CY, 8, 9, false, pc);
        // next
        int nc = hoverBtn == 3 ? hot : idle;
        tri(g, NEXT_CX - 5, BTN_CY, 8, 9, true, nc);
        g.fill(NEXT_CX + 4, BTN_CY - 4, NEXT_CX + 6, BTN_CY + 5, nc);
        // play / pause
        int r = hoverBtn == 2 ? 10 : 9;
        rrect(g, PLAY_CX - r, BTN_CY - r, r * 2, r * 2, r, a(hoverBtn == 2 ? 0xFFFFFFFF : 0xFFE8E8E8, al));
        int dark = a(0xFF101013, al);
        if (s.playing()) {
            g.fill(PLAY_CX - 4, BTN_CY - 4, PLAY_CX - 1, BTN_CY + 5, dark);
            g.fill(PLAY_CX + 1, BTN_CY - 4, PLAY_CX + 4, BTN_CY + 5, dark);
        } else {
            tri(g, PLAY_CX - 3, BTN_CY, 7, 9, true, dark);
        }

        // progress bar
        int px = 60, pwid = 132, py = 52;
        rrect(g, px, py, pwid, 2, 1, a(0x33FFFFFF, al));
        if (s.active() && s.dur() > 0) {
            float f = (float) Math.max(0, Math.min(1, s.livePos() / s.dur()));
            int fw = Math.round(pwid * f);
            if (fw > 0) rrect(g, px, py, fw, 2, 1, a(GREEN, al));
        }

        // hint while hovering in chat
        if (editing && highlight) {
            g.drawString(font, "drag to move  |  scroll to resize", 4, H + 5, a(0xFFC8C8C8, al), true);
        }

        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ scroll resize

    /** Called from the chat screen's scroll event. Returns true if the scroll was used on the HUD. */
    public boolean onScroll(double mx, double my, double vertical) {
        if (!cfg.enabled || !boundsValid || vertical == 0) return false;
        if (mx < bx || mx > bx + bw || my < by || my > by + bh) return false;
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();

        float old = cfg.scale;
        float ns = Math.round((old + (vertical > 0 ? 0.1f : -0.1f)) * 100f) / 100f;
        ns = clamp(ns, 0.5f, 3.0f);
        if (ns == old) return true;

        // keep the HUD's centre where it is while resizing
        float cx = bx + bw / 2f, cy = by + bh / 2f;
        float npw = W * ns, nph = H * ns;
        float nx = clamp(cx - npw / 2f, 0, Math.max(0, sw - npw));
        float ny = clamp(cy - nph / 2f, 0, Math.max(0, sh - nph));
        cfg.scale = ns;
        cfg.x = nx / sw;
        cfg.y = ny / sh;
        cfg.save();
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static int buttonAt(double lx, double ly) {
        if (Math.abs(ly - BTN_CY) > 11) return 0;
        if (Math.abs(lx - PREV_CX) <= 9) return 1;
        if (Math.abs(lx - PLAY_CX) <= 11) return 2;
        if (Math.abs(lx - NEXT_CX) <= 9) return 3;
        return 0;
    }

    private void syncArt(Minecraft mc) {
        int v = bridge.artVersion();
        if (v == artLoaded) return;
        artLoaded = v;
        TextureManager tm = mc.getTextureManager();
        if (hasArtTexture) {
            tm.release(ART_ID);
            hasArtTexture = false;
        }
        byte[] png = bridge.artPng();
        if (png == null) return;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
            tm.register(ART_ID, new DynamicTexture(() -> "spothud_album_art", img));
            hasArtTexture = true;
        } catch (Exception e) {
            System.err.println("[SpotHud] Album art failed: " + e);
        }
    }

    private static String fit(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, maxW - font.width("...")) + "...";
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int a(int argb, float mult) {
        int al = Math.max(0, Math.min(255, (int) (((argb >>> 24) & 0xFF) * mult)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** Rounded rectangle built from 1px-tall rows. */
    private static void rrect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
        if (h - 2 * r > 0) g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** Solid triangle drawn as 1px columns. */
    private static void tri(GuiGraphics g, int x, int cy, int w, int h, boolean pointsRight, int color) {
        for (int i = 0; i < w; i++) {
            float f = pointsRight ? 1f - (float) i / w : (float) (i + 1) / w;
            int hh = Math.max(1, Math.round(h * f));
            int top = cy - hh / 2;
            g.fill(x + i, top, x + i + 1, top + hh, color);
        }
    }
}
