package net.kawaismp.authserver;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.route.Route;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class Main {

    public static void main(String[] args) {
        YamlDocument config;
        try {
            config = YamlDocument.create(new File("config.yml"), Objects.requireNonNull(Main.class.getClassLoader().getResourceAsStream("config.yml")));
            config.save();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        System.setProperty("minestom.tps", String.valueOf(5));
        System.setProperty("minestom.chunk-view-distance", String.valueOf(1));
        System.setProperty("minestom.entity-view-distance", String.valueOf(0));
        System.setProperty("minestom.packet-per-tick", String.valueOf(25));

        // Initialize the server
        MinecraftServer minecraftServer = MinecraftServer.init();

        // Instance and world setup
        InstanceManager instanceManager = MinecraftServer.getInstanceManager();
        InstanceContainer instanceContainer = instanceManager.createInstanceContainer();

        instanceContainer.setGenerator(unit -> unit.modifier().fillHeight(0, 1, Block.BARRIER));
        instanceContainer.setChunkSupplier(LightingChunk::new);
        instanceContainer.setTimeRate(0);

        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();

        globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(instanceContainer);
            player.setRespawnPoint(new Pos(0, 1, 0));
        });

        globalEventHandler.addListener(PlayerMoveEvent.class, event -> event.setCancelled(true));

        globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
            final Player player = event.getPlayer();
            player.addEffect(new Potion(PotionEffect.BLINDNESS, 0, 999999));
            player.setInvisible(true);
        });

        // Start the server
        minecraftServer.start(config.getString(Route.from("address")), config.getInt(Route.from("port")));
    }
}