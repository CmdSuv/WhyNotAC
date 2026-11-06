package dev.whynotac.recording.format;

/**
 * Constants of the recording format. Tags are part of the on-disk format: never renumber, only
 * append. {@code FormatStabilityTest} pins the exact bytes of every version.
 */
public final class RecordingFormat {

    private RecordingFormat() {}

    /** PNG-style magic: a high byte catches 7-bit transfers, CR LF catches newline translation. */
    public static final byte[] MAGIC = {(byte) 0x89, 'W', 'N', 'R', 'E', 'C', 0x0D, 0x0A};
    /**
     * Version written. 2: a string-table definition carries its index ({@code 0, index, utf8}), so a
     * reader that skips a record it does not know (written by a newer WhyNotAC) loses only the strings
     * defined inside that record, never the alignment of every later reference. 1: implicit indices.
     */
    public static final int VERSION = 2;
    /** Oldest version this reader still reads (old recordings are evidence and must stay readable). */
    public static final int MIN_READABLE_VERSION = 1;
    /** Text yielded for a reference to a string defined in a skipped record (version 2 and later). */
    public static final String UNKNOWN_STRING_PREFIX = "whynotac:unknown-recorded-string/";
    public static final String FILE_SUFFIX = ".wnrec";
    public static final String PARTIAL_SUFFIX = ".wnrec.part";

    /** Block sync marker "WNBK". */
    public static final byte[] BLOCK_MARKER = {'W', 'N', 'B', 'K'};
    /** Marker + 2*u32 lengths + 3*u64 bases + u32 events + u64 lost + u32 CRC. */
    public static final int BLOCK_HEADER_BYTES = 4 + 4 + 4 + 8 + 8 + 8 + 4 + 8 + 4;
    /** Upper bound for a block's raw size; anything larger is treated as corruption. */
    public static final int MAX_BLOCK_BYTES = 16 * 1024 * 1024;

    // --- record tags -----------------------------------------------------------------------------
    // 1..63: timeline events. Every event body starts with (varint dSeq, zigzag dNanos), so a reader
    //        can stay in sync across event kinds it does not know.
    // 64..127: metadata records with their own bodies.
    public static final int TAG_INBOUND = 1;
    public static final int TAG_OUTBOUND = 2;
    public static final int TAG_ACKNOWLEDGED = 3;
    public static final int TAG_BOUND = 4;
    public static final int TAG_GAP = 5;
    public static final int TAG_ANOMALY = 6;
    public static final int TAG_DISCONNECTED = 7;
    public static final int TAG_OBSERVATION = 8;
    public static final int TAG_INBOUND_DIVERGENCE = 9;
    public static final int TAG_OUTBOUND_DIVERGENCE = 10;
    public static final int TAG_CLIENT_FACT = 11;
    public static final int MAX_EVENT_TAG = 63;
    public static final int TAG_END = 127;

    // --- serverbound packet tags ---------------------------------------------------------------
    public static final int SB_MOVE_PLAYER = 1;
    public static final int SB_PLAYER_INPUT = 2;
    public static final int SB_PLAYER_COMMAND = 3;
    public static final int SB_PLAYER_ACTION = 4;
    public static final int SB_INTERACT_ENTITY = 5;
    public static final int SB_SWING = 6;
    public static final int SB_USE_ITEM = 7;
    public static final int SB_USE_ITEM_ON = 8;
    public static final int SB_SET_CARRIED_ITEM = 9;
    public static final int SB_CLIENT_TICK_END = 10;
    public static final int SB_PONG = 11;
    public static final int SB_KEEP_ALIVE = 12;
    public static final int SB_ACCEPT_TELEPORTATION = 13;
    public static final int SB_PLAYER_ABILITIES = 14;
    public static final int SB_MOVE_VEHICLE = 15;
    public static final int SB_PADDLE_BOAT = 16;
    public static final int SB_CONTAINER_CLICK = 17;
    public static final int SB_CONTAINER_CLOSE = 18;
    public static final int SB_PLAYER_LOADED = 19;
    public static final int SB_CHUNK_BATCH_RECEIVED = 20;
    public static final int SB_UNMODELED = 21;
    public static final int SB_UNTRANSLATABLE = 22;
    public static final int SB_HANDSHAKE = 23;
    public static final int SB_CLIENT_INFORMATION = 24;
    public static final int SB_CLIENT_BRAND = 25;
    public static final int SB_CHANNEL_REGISTRATION = 26;
    public static final int SB_CUSTOM_PAYLOAD = 27;
    /** ContainerClick with the predicted slots and carried stack (SB_CONTAINER_CLICK: timing only). */
    public static final int SB_CONTAINER_CLICK_ITEMS = 28;
    public static final int SB_SET_CREATIVE_MODE_SLOT = 29;

    // --- clientbound packet tags ---------------------------------------------------------------
    public static final int CB_PING = 1;
    public static final int CB_KEEP_ALIVE = 2;
    public static final int CB_LOGIN = 3;
    public static final int CB_RESPAWN = 4;
    public static final int CB_SET_ENTITY_MOTION = 5;
    public static final int CB_EXPLOSION = 6;
    public static final int CB_PLAYER_POSITION = 7;
    public static final int CB_PLAYER_ROTATION = 8;
    public static final int CB_UPDATE_ATTRIBUTES = 9;
    public static final int CB_MOB_EFFECT = 10;
    public static final int CB_REMOVE_MOB_EFFECT = 11;
    public static final int CB_PLAYER_ABILITIES = 12;
    public static final int CB_GAME_EVENT = 13;
    public static final int CB_UNMODELED = 14;
    public static final int CB_UNTRANSLATABLE = 15;
    /** SetEntityMotion with the client's exact value and, from the game's view, the server's (format 2). */
    public static final int CB_SET_ENTITY_MOTION_EXACT = 16;
    public static final int CB_TELEPORT_ENTITY = 17;
    /** Login with the chunk radius and the dimension (CB_LOGIN: entity id only). */
    public static final int CB_LOGIN_WORLD = 18;
    /** Respawn with the dimension (CB_RESPAWN: no fields). */
    public static final int CB_RESPAWN_WORLD = 19;
    public static final int CB_LEVEL_CHUNK = 20;
    public static final int CB_FORGET_LEVEL_CHUNK = 21;
    public static final int CB_BLOCK_UPDATE = 22;
    public static final int CB_SECTION_BLOCKS_UPDATE = 23;
    public static final int CB_BLOCK_CHANGED_ACK = 24;
    public static final int CB_CHUNK_CACHE_CENTER = 25;
    public static final int CB_CHUNK_CACHE_RADIUS = 26;
    /** TeleportEntity with position, velocity, rotation, relatives and onGround (CB_TELEPORT_ENTITY: entity id only). */
    public static final int CB_TELEPORT_ENTITY_FULL = 27;
    public static final int CB_ADD_ENTITY = 28;
    public static final int CB_REMOVE_ENTITIES = 29;
    public static final int CB_MOVE_ENTITY = 30;
    public static final int CB_ENTITY_POSITION_SYNC = 31;
    public static final int CB_ROTATE_HEAD = 32;
    public static final int CB_SET_PASSENGERS = 33;
    public static final int CB_MOVE_VEHICLE = 34;
    public static final int CB_SET_ENTITY_DATA = 35;
    public static final int CB_TICKING_STATE = 36;
    public static final int CB_TICKING_STEP = 37;
    public static final int CB_PLAYER_INFO_UPDATE = 38;
    public static final int CB_PLAYER_INFO_REMOVE = 39;
    public static final int CB_CONTAINER_SET_CONTENT = 40;
    public static final int CB_CONTAINER_SET_SLOT = 41;
    public static final int CB_SET_CURSOR_ITEM = 42;
    public static final int CB_SET_PLAYER_INVENTORY = 43;
    public static final int CB_SET_HELD_SLOT = 44;
    public static final int CB_OPEN_SCREEN = 45;
    public static final int CB_MOUNT_SCREEN_OPEN = 46;
    public static final int CB_CONTAINER_CLOSE = 47;
    /** Login with the chunk radius, the dimension and the game mode. */
    public static final int CB_LOGIN_MODE = 48;
    /** Respawn with the dimension and the game mode. */
    public static final int CB_RESPAWN_MODE = 49;
    public static final int CB_ENTITY_EVENT = 50;
    /** Respawn with the dimension, the game mode and what the client keeps. */
    public static final int CB_RESPAWN_KEEP = 51;
    public static final int CB_SET_CAMERA = 52;
    public static final int CB_SET_HEALTH = 53;
    public static final int CB_INITIALIZE_BORDER = 54;
    public static final int CB_SET_BORDER_CENTER = 55;
    public static final int CB_SET_BORDER_LERP_SIZE = 56;
    public static final int CB_SET_BORDER_SIZE = 57;
    public static final int CB_SET_PLAYER_TEAM = 58;
    /** Login with the chunk radius, the dimension (with its lava speed) and the game mode. */
    public static final int CB_LOGIN_LAVA = 59;
    /** Respawn with the dimension (with its lava speed), the game mode and what the client keeps. */
    public static final int CB_RESPAWN_LAVA = 60;
    /** Every registry sent, each with its tags and their ids. */
    public static final int CB_UPDATE_TAGS = 61;
    /** An entity's equipment: the entity, then each slot's name and stack. */
    public static final int CB_SET_EQUIPMENT = 62;
    /** The level's time: game time, day time, whether the day time runs. */
    public static final int CB_SET_TIME = 63;
    /**
     * A chunk whose body is kept for later repeats: x, z, the body's ordinal in this recording (one more than
     * the last stored body's), the body. Written for every chunk with a body since CB_LEVEL_CHUNK (still read).
     */
    public static final int CB_LEVEL_CHUNK_STORED = 64;
    /**
     * A chunk whose body is byte for byte (SHA-256) the stored body of that ordinal: x, z, the ordinal. The
     * ordinal is one of the last {@link #CHUNK_WINDOW} stored, so a reader holds at most that many bodies.
     */
    public static final int CB_LEVEL_CHUNK_REPEAT = 65;

    /**
     * Stored chunk bodies a repeat may refer to: part of the format, never smaller (a reader keeps this
     * many). A chunk is resent whenever the client walks back into it; most resends carry the same bytes.
     */
    public static final int CHUNK_WINDOW = 512;

    /**
     * Type id given to a recorded packet whose tag this reader does not know (written by a newer
     * WhyNotAC). The packet is the last field of its record, so the reader skips it and keeps the
     * event, timestamp and order intact.
     */
    public static final String UNKNOWN_PACKET_PREFIX = "whynotac:unknown-recorded-packet/";
}
