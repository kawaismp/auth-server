package net.kawaismp.authserver;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;

public class Main {

    public static void main(String[] args) {
        // Initialize the server
        MinecraftServer minecraftServer = MinecraftServer.init();

        // Instance and world setup
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 1, Block.BARRIER));
        instanceContainer.setChunkSupplier(LightingChunk::new);

        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();

        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 1, 0));
        });

        globalEventHandler.addListener(PlayerMoveEvent.class, event -> {
            event.setCancelled(true);
        });

        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            final Player player = event.getPlayer();
            player.addEffect(new Potion(PotionEffect.BLINDNESS, 10, 999999));
        });

        // Start the server
        minecraftServer.start("0.0.0.0", 25565);
    }
}