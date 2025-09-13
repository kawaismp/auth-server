package net.kawaismp.authserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Server;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.server.ServerAdapter;
import org.geysermc.mcprotocollib.network.event.server.ServerClosedEvent;
import org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent;
import org.geysermc.mcprotocollib.network.event.server.SessionRemovedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
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
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.auth.GameProfile;

import java.util.Collections;

public class Main {
    private static final boolean ENCRYPT_CONNECTION = false;
    private static final boolean SHOULD_AUTHENTICATE = false;
    private static final int SERVER_PORT = 25565;

    private static final SingletonPalette AIR_PALETTE = new SingletonPalette(0);
    private static final Component SERVER_NAME = Component.text("Auth Server");
    private static final BlockEntityInfo[] EMPTY_BLOCK_ENTITIES = new BlockEntityInfo[0];
    private static final LightUpdateData EMPTY_LIGHT_UPDATE = new LightUpdateData(
            new java.util.BitSet(), new java.util.BitSet(), new java.util.BitSet(), new java.util.BitSet(),
            Collections.emptyList(), Collections.emptyList()
    );
    private static final Key[] WORLD_KEYS = new Key[]{Key.key("minecraft:world")};
    private static final ServerStatusInfo SERVER_STATUS_INFO = new ServerStatusInfo(SERVER_NAME, new PlayerInfo(100, 0, Collections.emptyList()), new VersionInfo(MinecraftCodec.CODEC.getMinecraftVersion(), MinecraftCodec.CODEC.getProtocolVersion()), null, false);
    private static final byte[] PREGENERATED_CHUNK_COLUMN = buildSerializedChunkColumn();

    // Prebuilt immutable packet instances (reused across sessions)
    private static final ClientboundLoginPacket LOGIN_PACKET;
    private static final ClientboundLevelChunkWithLightPacket PREGENERATED_CHUNK_PACKET;
    private static final ClientboundSetTimePacket SET_TIME_PACKET = new ClientboundSetTimePacket(0, 18000);
    private static final ClientboundGameEventPacket LOAD_START_PACKET = new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null);
    private static final ClientboundSetDefaultSpawnPositionPacket SET_SPAWN_PACKET = new ClientboundSetDefaultSpawnPositionPacket(Vector3i.from(8, 17, 8), 0);
    private static final ClientboundPlayerPositionPacket FORCE_TELEPORT_PACKET = new ClientboundPlayerPositionPacket(8, 17, 8, 0, 0, 0);
    private static final ClientboundUpdateMobEffectPacket BLINDNESS_PACKET = new ClientboundUpdateMobEffectPacket(0, Effect.BLINDNESS, 10, 999999, false, false, false, false);

    static {
        LOGIN_PACKET = new ClientboundLoginPacket(
                0,
                false,
                WORLD_KEYS,
                100,
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
        );

        PREGENERATED_CHUNK_PACKET = new ClientboundLevelChunkWithLightPacket(
                0,
                0,
                PREGENERATED_CHUNK_COLUMN,
                NbtMap.EMPTY,
                EMPTY_BLOCK_ENTITIES,
                EMPTY_LIGHT_UPDATE
        );
    }

    /**
     * Create a fully prefilled empty section instance and a spawn section instance and serialize them
     * using a pooled direct buffer so we avoid heap allocations during runtime.
     */
    private static byte[] buildSerializedChunkColumn() {
        MinecraftCodecHelper helper = MinecraftCodec.CODEC.getHelperFactory().get();
        ByteBuf buf = Unpooled.buffer();
        try {
            ChunkSection emptySection = createEmptySection();
            ChunkSection spawnSection = createSpawnSection();

            for (int i = 0; i < 16; i++) {
                helper.writeChunkSection(buf, (i == 5) ? spawnSection : emptySection);
            }

            return buf.array();
        } finally {
            buf.release();
        }
    }

    /**
     * Build one empty section with AIR palette
     */
    private static ChunkSection createEmptySection() {
        ChunkSection section = new ChunkSection();
        section.getChunkData().setPalette(AIR_PALETTE);
        BitStorage blocks = section.getChunkData().getStorage();
        int size = blocks.getSize();
        for (int i = 0; i < size; i++) blocks.set(i, 0);

        section.getBiomeData().setPalette(AIR_PALETTE);
        BitStorage biomes = section.getBiomeData().getStorage();
        int bsize = biomes.getSize();
        for (int i = 0; i < bsize; i++) biomes.set(i, 0);
        return section;
    }

    /**
     * Faster spawn section creation: flat loop for index -> (x,y,z) with minimal arithmetic and branch-free
     */
    private static ChunkSection createSpawnSection() {
        ChunkSection section = new ChunkSection();
        // flatten 16*16*16 entries
        final int total = 4096;
        for (int i = 0; i < total; i++) {
            int y = (i >> 8) & 15; // y is the lowest significant in our mapping
            int x = (i >> 4) & 15;
            int z = i & 15;
            // as in 1.21, block state 10366 is barrier[waterlogged=false]
            section.setBlock(x, y, z, (y == 0) ? 10366 : 0);
        }

        section.getBiomeData().setPalette(AIR_PALETTE);
        BitStorage biomes = section.getBiomeData().getStorage();
        int bsize = biomes.getSize();
        for (int i = 0; i < bsize; i++) biomes.set(i, 0);
        return section;
    }

    public static void main(String[] args) {
        SessionService sessionService = new SessionService();

        Server server = new TcpServer("0.0.0.0", SERVER_PORT, MinecraftProtocol::new);
        server.setGlobalFlag(MinecraftConstants.SESSION_SERVICE_KEY, sessionService);
        server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, ENCRYPT_CONNECTION);
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, SHOULD_AUTHENTICATE);
        server.setGlobalFlag(MinecraftConstants.SERVER_INFO_BUILDER_KEY, session -> SERVER_STATUS_INFO);
        server.setGlobalFlag(MinecraftConstants.SERVER_LOGIN_HANDLER_KEY, session -> {
            session.send(LOGIN_PACKET);
            session.send(PREGENERATED_CHUNK_PACKET);
            session.send(SET_TIME_PACKET);
            session.send(LOAD_START_PACKET);
            session.send(SET_SPAWN_PACKET);
            session.send(FORCE_TELEPORT_PACKET);
            session.send(BLINDNESS_PACKET);
        });
        server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, -1);

        server.addListener(new ServerAdapter() {
            @Override
            public void serverClosed(ServerClosedEvent event) {
                System.out.println("Server closed.");
            }

            @Override
            public void sessionAdded(SessionAddedEvent event) {
                event.getSession().addListener(new PerSessionPacketListener());
            }

            @Override
            public void sessionRemoved(SessionRemovedEvent event) {
                GameProfile profile = event.getSession().getFlag(MinecraftConstants.PROFILE_KEY);
                if (profile != null) {
                    System.out.println(profile.getName() + " disconnected.");
                }
            }
        });

        System.out.println("Server started on " + server.getHost());
        server.bind();
    }

    private static final class PerSessionPacketListener extends SessionAdapter implements SessionListener {
        @Override
        public void packetReceived(Session session, Packet packet) {
            if (packet instanceof ServerboundMovePlayerPosPacket move) {
                if (move.getX() != 8 || move.getY() != 17 || move.getZ() != 8) {
                    session.send(FORCE_TELEPORT_PACKET);
                }
            }
        }
    }
}