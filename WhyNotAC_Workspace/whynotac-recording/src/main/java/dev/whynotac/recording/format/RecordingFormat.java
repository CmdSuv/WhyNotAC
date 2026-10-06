package dev.whynotac.recording.format;

/**
 * Constants of recording format version 1. Tags are part of the on-disk format: never renumber,
 * only append. {@code FormatStabilityTest} pins the exact bytes.
 */
public final class RecordingFormat {

    private RecordingFormat() {}

    /** PNG-style magic: a high byte catches 7-bit transfers, CR LF catches newline translation. */
    public static final byte[] MAGIC = {(byte) 0x89, 'W', 'N', 'R', 'E', 'C', 0x0D, 0x0A};
    public static final int VERSION = 1;
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

    /**
     * Type id given to a recorded packet whose tag this reader does not know (written by a newer
     * WhyNotAC). The packet is the last field of its record, so the reader skips it and keeps the
     * event, timestamp and order intact.
     */
    public static final String UNKNOWN_PACKET_PREFIX = "whynotac:unknown-recorded-packet/";
}
