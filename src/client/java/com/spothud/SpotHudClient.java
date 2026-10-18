package com.spothud;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class SpotHudClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HudConfig cfg = HudConfig.load();
        MediaBridge bridge = new MediaBridge();
        bridge.start();
        HudRenderer renderer = new HudRenderer(cfg, bridge);

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("spothud", "now_playing"), renderer::render);

        // scroll wheel resize (only while chat is open, because that's when the cursor is free)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseScroll(screen).register(
                        (s, mouseX, mouseY, horizontal, vertical) -> !renderer.onScroll(mouseX, mouseY, vertical));
            }
        });

        // /spot off | on | reset
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("spot")
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            cfg.enabled = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Spot HUD off. Use /spot on to bring it back."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            cfg.enabled = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Spot HUD on."));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            cfg.reset();
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("Spot HUD position and size reset."));
                            return Command.SINGLE_SUCCESS;
                        }))));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bridge.stop());
    }
}
