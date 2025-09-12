package net.kawaismp.authserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Server;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.server.ServerAdapter;
import org.geysermc.mcprotocollib.network.event.server.ServerClosedEvent;
import org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent;
import org.geysermc.mcprotocollib.network.event.server.SessionRemovedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.BitStorage;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.SingletonPalette;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.Collections;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final boolean ENCRYPT_CONNECTION = false;
    private static final boolean SHOULD_AUTHENTICATE = false;
    private static final int SERVER_PORT = 25565;

    private static final SingletonPalette AIR_PALETTE = new SingletonPalette(0);
    private static final Component SERVER_NAME = Component.text("Auth Server");
    private static final BlockEntityInfo[] EMPTY_BLOCK_ENTITIES = new BlockEntityInfo[0];
    private static final LightUpdateData EMPTY_LIGHT_UPDATE = new LightUpdateData(
            new BitSet(), new BitSet(), new BitSet(), new BitSet(),
            Collections.emptyList(), Collections.emptyList()
    );

    // Pre-serialized chunk column (0,0)
    private static final byte[] PREGENERATED_CHUNK_COLUMN = buildSerializedChunkColumn();

    /**
     * Build one empty section with AIR palette
     */
    private static ChunkSection createEmptySection() {
        ChunkSection section = new ChunkSection();
        section.getChunkData().setPalette(AIR_PALETTE);
        BitStorage blocks = section.getChunkData().getStorage();
        for (int i = 0; i < blocks.getSize(); i++) blocks.set(i, 0);

        section.getBiomeData().setPalette(AIR_PALETTE);
        BitStorage biomes = section.getBiomeData().getStorage();
        for (int i = 0; i < biomes.getSize(); i++) biomes.set(i, 0);

        return section;
    }

    /**
     * Spawn section with bedrock layer at y=0
     */
    private static ChunkSection createSpawnSection() {
        ChunkSection section = new ChunkSection();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    section.setBlock(x, y, z, y == 0 ? 14 : 0);
                }
            }
        }
        section.getBiomeData().setPalette(AIR_PALETTE);
        BitStorage biomes = section.getBiomeData().getStorage();
        for (int i = 0; i < biomes.getSize(); i++) biomes.set(i, 0);
        return section;
    }

    /**
     * Build pre-serialized chunk column, discarding section objects afterwards
     */
    private static byte[] buildSerializedChunkColumn() {
        MinecraftCodecHelper helper = MinecraftCodec.CODEC.getHelperFactory().get();
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 16; i++) {
                ChunkSection section = (i == 5) ? createSpawnSection() : createEmptySection();
                helper.writeChunkSection(buf, section);
            }
            return buf.array();
        } finally {
            buf.release();
        }
    }

    public static void main(String[] args) {
        SessionService sessionService = new SessionService();

        Server server = new TcpServer("0.0.0.0", SERVER_PORT, MinecraftProtocol::new);
        server.setGlobalFlag(MinecraftConstants.SESSION_SERVICE_KEY, sessionService);
        server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, ENCRYPT_CONNECTION);
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, SHOULD_AUTHENTICATE);
        server.setGlobalFlag(MinecraftConstants.SERVER_INFO_BUILDER_KEY, session ->
                new ServerStatusInfo(
                        SERVER_NAME,
                        new PlayerInfo(100, 0, Collections.emptyList()),
                        new VersionInfo(MinecraftCodec.CODEC.getMinecraftVersion(), MinecraftCodec.CODEC.getProtocolVersion()),
                        null,
                        false
                )
        );

        server.setGlobalFlag(MinecraftConstants.SERVER_LOGIN_HANDLER_KEY, session -> {
            session.send(new ClientboundLoginPacket(
                    0,
                    false,
                    new Key[]{Key.key("minecraft:world")},
                    0,
                    2,
                    2,
                    false,
                    false,
                    false,
                    new PlayerSpawnInfo(
                            0,
                            Key.key("minecraft:world"),
                            100,
                            GameMode.ADVENTURE,
                            GameMode.ADVENTURE,
                            false,
                            false,
                            null,
                            100
                    ),
                    false
            ));

            session.send(new ClientboundLevelChunkWithLightPacket(
                    0, 0, PREGENERATED_CHUNK_COLUMN, NbtMap.EMPTY,
                    EMPTY_BLOCK_ENTITIES,
                    EMPTY_LIGHT_UPDATE
            ));

            session.send(new ClientboundSetTimePacket(0, 18000));
            session.send(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
            session.send(new ClientboundSetDefaultSpawnPositionPacket(Vector3i.from(8, 17, 8), 0));
            session.send(new ClientboundPlayerPositionPacket(8, 17, 8, 0, 0, 0));
            session.send(new ClientboundUpdateMobEffectPacket(0, Effect.BLINDNESS, 10, 999999, false, false, false, false));
        });

        server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, -1);
        server.addListener(new ServerAdapter() {
            @Override
            public void serverClosed(ServerClosedEvent event) {
                log.info("Server closed.");
            }

            @Override
            public void sessionAdded(SessionAddedEvent event) {
                event.getSession().addListener(new SessionAdapter() {
                    @Override
                    public void packetReceived(Session session, Packet packet) {
                        if (packet instanceof ServerboundMovePlayerPosPacket move) {
                            if (move.getX() != 8 || move.getY() != 17 || move.getZ() != 8) {
                                session.send(new ClientboundPlayerPositionPacket(8, 17, 8, 0, 0, 0));
                            }
                        } else if (packet instanceof ServerboundMovePlayerPosRotPacket moveRot) {
                            if (moveRot.getYaw() != 0 || moveRot.getPitch() != 0) {
                                session.send(new ClientboundPlayerPositionPacket(8, 17, 8, 0, 0, 0));
                            }
                        }
                    }
                });
            }

            @Override
            public void sessionRemoved(SessionRemovedEvent event) {
                GameProfile profile = event.getSession().getFlag(MinecraftConstants.PROFILE_KEY);
                if (profile != null) {
                    log.info("{} disconnected.", profile.getName());
                }
            }
        });

        log.info("Server started on {}", server.getHost());
        server.bind();
    }
}