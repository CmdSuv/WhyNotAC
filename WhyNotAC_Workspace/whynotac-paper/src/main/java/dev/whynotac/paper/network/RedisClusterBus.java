package dev.whynotac.paper.network;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zero-dependency pure-Java RESP2 Redis Pub/Sub cluster control bus ({@code network-bridge.redis-uri}, PH23-03).
 *
 * <p>Transports HMAC-SHA256-signed {@link dev.whynotac.protocol.network.NetworkEnvelope} frames across all
 * backend servers on channel {@value #DEFAULT_CHANNEL} even when a backend currently has zero online players
 * (where Minecraft plugin-messaging channels cannot carry frames). Filters local echoes using {@code serverId},
 * and reconnects with bounded exponential backoff ({@code 1 s .. 30 s}) if the Redis broker restarts.
 */
public final class RedisClusterBus implements AutoCloseable {

    public static final String DEFAULT_CHANNEL = "whynotac:cluster";
    private static final int DEFAULT_PORT = 6379;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final long MIN_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final String host;
    private final int port;
    private final String password;
    private final String channel;
    private final String serverId;
    private final Consumer<byte[]> inboundHandler;
    private final Logger logger;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong reconnectCount = new AtomicLong();
    private final Object publishLock = new Object();

    private Socket pubSocket;
    private OutputStream pubOut;
    private BufferedInputStream pubIn;
    private volatile Socket subSocket;
    private final Thread subscriberThread;

    public RedisClusterBus(
            String redisUri,
            String serverId,
            Consumer<byte[]> inboundHandler,
            Logger logger) {
        this(redisUri, DEFAULT_CHANNEL, serverId, inboundHandler, logger);
    }

    public RedisClusterBus(
            String redisUri,
            String channel,
            String serverId,
            Consumer<byte[]> inboundHandler,
            Logger logger) {
        URI uri = parseRedisUri(redisUri);
        this.host = uri.getHost();
        this.port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        this.password = extractPassword(uri.getUserInfo());
        this.channel = (channel == null || channel.isBlank()) ? DEFAULT_CHANNEL : channel.trim();
        this.serverId = (serverId == null || serverId.isBlank()) ? UUID.randomUUID().toString() : serverId.trim();
        this.inboundHandler = Objects.requireNonNull(inboundHandler, "inboundHandler");
        this.logger = logger;
        this.subscriberThread = new Thread(this::subscribeLoop, "WhyNotAC-RedisSub");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    static URI parseRedisUri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("redisUri must not be blank");
        }
        URI uri = URI.create(raw.trim());
        if (!"redis".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported Redis URI scheme: " + uri.getScheme());
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Redis URI missing host: " + raw);
        }
        return uri;
    }

    private static String extractPassword(String userInfo) {
        if (userInfo == null || userInfo.isEmpty()) {
            return null;
        }
        int colon = userInfo.indexOf(':');
        return colon >= 0 ? userInfo.substring(colon + 1) : userInfo;
    }

    /** Publishes one signed {@code NetworkEnvelope} binary frame to all peers on the Redis Pub/Sub channel. */
    public boolean publish(byte[] frame) {
        if (closed.get() || frame == null || frame.length == 0) {
            return false;
        }
        String payload = serverId + ":" + Base64.getEncoder().encodeToString(frame);
        synchronized (publishLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    ensurePublisherConnected();
                    writeCommand(pubOut, "PUBLISH", channel, payload);
                    readRespValue(pubIn);
                    publishedCount.incrementAndGet();
                    return true;
                } catch (IOException e) {
                    closePublisherQuietly();
                    if (attempt == 1 && logger != null) {
                        logger.log(Level.WARNING, "WhyNotAC Redis publish failed (" + host + ":" + port + "): " + e.getMessage());
                    }
                }
            }
            return false;
        }
    }

    private void ensurePublisherConnected() throws IOException {
        if (pubSocket != null && pubSocket.isConnected() && !pubSocket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(5_000);
        s.setTcpNoDelay(true);
        OutputStream out = s.getOutputStream();
        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        if (password != null && !password.isEmpty()) {
            writeCommand(out, "AUTH", password);
            Object authResp = readRespValue(in);
            if (authResp instanceof RespError err) {
                s.close();
                throw new IOException("Redis AUTH failed: " + err.message());
            }
        }
        pubSocket = s;
        pubOut = out;
        pubIn = in;
    }

    private void closePublisherQuietly() {
        if (pubSocket != null) {
            try {
                pubSocket.close();
            } catch (IOException ignored) { }
            pubSocket = null;
            pubOut = null;
            pubIn = null;
        }
    }

    private void subscribeLoop() {
        long backoffMs = MIN_BACKOFF_MS;
        boolean firstConnect = true;
        while (!closed.get()) {
            try (Socket s = new Socket()) {
                this.subSocket = s;
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                OutputStream out = s.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                if (password != null && !password.isEmpty()) {
                    writeCommand(out, "AUTH", password);
                    Object authResp = readRespValue(in);
                    if (authResp instanceof RespError err) {
                        throw new IOException("Redis subscriber AUTH failed: " + err.message());
                    }
                }
                writeCommand(out, "SUBSCRIBE", channel);
                if (!firstConnect) {
                    reconnectCount.incrementAndGet();
                }
                firstConnect = false;
                backoffMs = MIN_BACKOFF_MS;
                while (!closed.get()) {
                    Object msg = readRespValue(in);
                    if (msg instanceof List<?> list && list.size() >= 3 && "message".equals(list.get(0))
                            && channel.equals(list.get(1)) && list.get(2) instanceof String text) {
                        handleMessage(text);
                    }
                }
            } catch (IOException e) {
                if (closed.get()) {
                    return;
                }
                if (logger != null) {
                    logger.log(Level.WARNING, "WhyNotAC Redis subscriber disconnected from " + host + ":" + port
                            + " (reconnecting in " + backoffMs + " ms): " + e.getMessage());
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2L);
            } finally {
                this.subSocket = null;
            }
        }
    }

    private void handleMessage(String text) {
        int sep = text.indexOf(':');
        if (sep <= 0 || sep + 1 >= text.length()) {
            return;
        }
        String senderId = text.substring(0, sep);
        if (serverId.equals(senderId)) {
            return; // filter self echo
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(text.substring(sep + 1));
            receivedCount.incrementAndGet();
            inboundHandler.accept(decoded);
        } catch (IllegalArgumentException ignored) {
            // malformed base64 frame ignored cleanly
        }
    }

    public String serverId() {
        return serverId;
    }

    public String channel() {
        return channel;
    }

    public long publishedCount() {
        return publishedCount.get();
    }

    public long receivedCount() {
        return receivedCount.get();
    }

    public long reconnectCount() {
        return reconnectCount.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket sub = this.subSocket;
        if (sub != null) {
            try {
                sub.close();
            } catch (IOException ignored) { }
        }
        subscriberThread.interrupt();
        synchronized (publishLock) {
            closePublisherQuietly();
        }
    }

    // --- Minimal RESP2 wire protocol ---

    record RespError(String message) {}

    static void writeCommand(OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] utf8 = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(utf8.length).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            sb.setLength(0);
            out.write(utf8);
            out.write('\r');
            out.write('\n');
        }
        out.flush();
    }

    static Object readRespValue(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix < 0) {
            throw new IOException("EOF reading RESP2 stream");
        }
        return switch (prefix) {
            case '+' -> readLine(in);
            case '-' -> new RespError(readLine(in));
            case ':' -> Long.parseLong(readLine(in));
            case '$' -> {
                int len = Integer.parseInt(readLine(in));
                if (len < 0) {
                    yield null;
                }
                byte[] buf = in.readNBytes(len);
                if (buf.length < len) {
                    throw new IOException("Truncated RESP2 bulk string");
                }
                int cr = in.read();
                int lf = in.read();
                if (cr != '\r' || lf != '\n') {
                    throw new IOException("Missing CRLF after RESP2 bulk string");
                }
                yield new String(buf, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(readLine(in));
                if (count < 0) {
                    yield null;
                }
                List<Object> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readRespValue(in));
                }
                yield items;
            }
            default -> throw new IOException("Unknown RESP2 prefix: " + (char) prefix);
        };
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("EOF reading RESP2 line");
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = baos.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            baos.write(b);
            prev = b;
        }
    }
}
