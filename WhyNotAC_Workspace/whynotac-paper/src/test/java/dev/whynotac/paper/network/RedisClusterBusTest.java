package dev.whynotac.paper.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.ai.AccountBaselineModel;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class RedisClusterBusTest {

    @Test
    void deliversSignedFramesAcrossEmptyBackendsFiltersEchoAndReconnects() throws Exception {
        byte[] secret = "redis-test-secret-key-256bits!!!".getBytes(StandardCharsets.UTF_8);
        try (MockResp2Broker broker = new MockResp2Broker("s3cr3t")) {
            String uri = "redis://:s3cr3t@127.0.0.1:" + broker.port();

            AccountBaselineModel baselineA = new AccountBaselineModel();
            AccountBaselineModel baselineB = new AccountBaselineModel();
            CountDownLatch erasureLatchB = new CountDownLatch(1);
            CountDownLatch reconnectErasureLatchB = new CountDownLatch(1);
            CopyOnWriteArrayList<UUID> erasedOnA = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<UUID> erasedOnB = new CopyOnWriteArrayList<>();

            // Backends with ZERO online players (empty velocity frameSender)
            PaperNetworkBridge bridgeA = new PaperNetworkBridge(
                    secret, 60_000L, id -> Optional.empty(), baselineA, null,
                    frame -> { }, System::currentTimeMillis, Logger.getLogger("bridgeA"));
            PaperNetworkBridge bridgeB = new PaperNetworkBridge(
                    secret, 60_000L, id -> Optional.empty(), baselineB, null,
                    frame -> { }, System::currentTimeMillis, Logger.getLogger("bridgeB"));

            bridgeA.setRemoteErasureHandler(erasedOnA::add);
            bridgeB.setRemoteErasureHandler(id -> {
                erasedOnB.add(id);
                if (erasedOnB.size() == 1) {
                    erasureLatchB.countDown();
                } else if (erasedOnB.size() >= 2) {
                    reconnectErasureLatchB.countDown();
                }
            });

            try (RedisClusterBus busA = new RedisClusterBus(uri, "node-A", bridgeA::handleInboundFrame, Logger.getLogger("busA"));
                 RedisClusterBus busB = new RedisClusterBus(uri, "node-B", bridgeB::handleInboundFrame, Logger.getLogger("busB"))) {
                bridgeA.attachClusterBus(busA);
                bridgeB.attachClusterBus(busB);

                assertTrue(broker.awaitSubscribers(2, 5, TimeUnit.SECONDS), "Both nodes must AUTH and SUBSCRIBE");

                // 1. Broadcast ProfileSummary + GDPR_FORGET from node-A -> arrives on node-B, filtered on node-A (no echo)
                UUID subject1 = UUID.randomUUID();
                RunningMoments moments = RunningMoments.fromSummary(25, 0.42, 0.01);
                bridgeA.sendProfileSummary(subject1, Identity.AUTHENTICATED_BY_PROXY, "aim_speed", moments, 0);
                bridgeA.sendEnforcementNotice(subject1, "GDPR_FORGET", "", "", 0L, "subject erasure");

                assertTrue(erasureLatchB.await(5, TimeUnit.SECONDS), "GDPR_FORGET must arrive at node-B via Redis Pub/Sub");
                assertEquals(List.of(subject1), erasedOnB);
                assertTrue(erasedOnA.isEmpty(), "Self-echo on node-A must be filtered out by serverId");
                assertTrue(bridgeB.receivedValid() >= 2);
                assertEquals(0L, bridgeB.rejectedInvalid());

                // 2. Drop all broker subscriber sockets to force automatic reconnect
                broker.dropAllSubscribers();
                assertTrue(broker.awaitTotalSubscriptions(4, 5, TimeUnit.SECONDS),
                        "Both subscribers must automatically reconnect after broker disconnect");

                UUID subject2 = UUID.randomUUID();
                bridgeA.sendEnforcementNotice(subject2, "GDPR_FORGET", "", "", 0L, "post-reconnect erasure");
                assertTrue(reconnectErasureLatchB.await(5, TimeUnit.SECONDS),
                        "Frame must be delivered on node-B after automatic reconnect");
                assertEquals(2, erasedOnB.size());
                assertEquals(subject2, erasedOnB.get(1));
                assertTrue(busB.reconnectCount() >= 1L);
            }
        }
    }

    /** Minimal embedded RESP2 TCP Pub/Sub broker supporting AUTH, SUBSCRIBE, and PUBLISH. */
    private static final class MockResp2Broker implements AutoCloseable {
        private final ServerSocket server;
        private final String requiredPassword;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CopyOnWriteArrayList<ClientSub> subscribers = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Socket> allSockets = new CopyOnWriteArrayList<>();
        private volatile int totalSubscriptionEvents;
        private final Object subMonitor = new Object();

        private record ClientSub(String channel, Socket socket, OutputStream out) {}

        MockResp2Broker(String requiredPassword) throws IOException {
            this.requiredPassword = requiredPassword;
            this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread accept = new Thread(this::acceptLoop, "MockResp2-Accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return server.getLocalPort();
        }

        boolean awaitSubscribers(int minCount, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (subMonitor) {
                while (subscribers.size() < minCount && System.nanoTime() < deadline) {
                    subMonitor.wait(50);
                }
                return subscribers.size() >= minCount;
            }
        }

        boolean awaitTotalSubscriptions(int minTotal, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (subMonitor) {
                while (totalSubscriptionEvents < minTotal && System.nanoTime() < deadline) {
                    subMonitor.wait(50);
                }
                return totalSubscriptionEvents >= minTotal;
            }
        }

        void dropAllSubscribers() {
            List<ClientSub> copy = List.copyOf(subscribers);
            subscribers.clear();
            for (ClientSub sub : copy) {
                try {
                    sub.socket().close();
                } catch (IOException ignored) { }
            }
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket sock = server.accept();
                    allSockets.add(sock);
                    Thread t = new Thread(() -> handleClient(sock), "MockResp2-Client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    break;
                }
            }
        }

        private void handleClient(Socket sock) {
            boolean authed = requiredPassword == null || requiredPassword.isEmpty();
            ClientSub registeredSub = null;
            try (Socket s = sock;
                 BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                 OutputStream out = s.getOutputStream()) {
                while (!closed.get() && !s.isClosed()) {
                    List<String> cmd = readRespArray(in);
                    if (cmd == null || cmd.isEmpty()) {
                        break;
                    }
                    String op = cmd.get(0).toUpperCase();
                    switch (op) {
                        case "AUTH" -> {
                            if (cmd.size() >= 2 && cmd.get(1).equals(requiredPassword)) {
                                authed = true;
                                out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                            } else {
                                out.write("-ERR invalid password\r\n".getBytes(StandardCharsets.UTF_8));
                            }
                            out.flush();
                        }
                        case "SUBSCRIBE" -> {
                            if (!authed) {
                                out.write("-NOAUTH Authentication required\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                break;
                            }
                            String ch = cmd.get(1);
                            registeredSub = new ClientSub(ch, s, out);
                            subscribers.add(registeredSub);
                            synchronized (subMonitor) {
                                totalSubscriptionEvents++;
                                subMonitor.notifyAll();
                            }
                            String ack = "*3\r\n$9\r\nsubscribe\r\n$" + ch.length() + "\r\n" + ch + "\r\n:1\r\n";
                            out.write(ack.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        case "PUBLISH" -> {
                            if (!authed) {
                                out.write("-NOAUTH Authentication required\r\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                break;
                            }
                            String ch = cmd.get(1);
                            String payload = cmd.get(2);
                            byte[] chBytes = ch.getBytes(StandardCharsets.UTF_8);
                            byte[] pBytes = payload.getBytes(StandardCharsets.UTF_8);
                            ByteArrayOutputStream frame = new ByteArrayOutputStream();
                            frame.write(("*3\r\n$7\r\nmessage\r\n$" + chBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                            frame.write(chBytes);
                            frame.write(("\r\n$" + pBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                            frame.write(pBytes);
                            frame.write("\r\n".getBytes(StandardCharsets.UTF_8));
                            byte[] msgBytes = frame.toByteArray();
                            int delivered = 0;
                            for (ClientSub sub : subscribers) {
                                if (sub.channel().equals(ch)) {
                                    synchronized (sub.out()) {
                                        try {
                                            sub.out().write(msgBytes);
                                            sub.out().flush();
                                            delivered++;
                                        } catch (IOException ignored) { }
                                    }
                                }
                            }
                            out.write((":" + delivered + "\r\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        default -> {
                            out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (registeredSub != null) {
                    subscribers.remove(registeredSub);
                }
            }
        }

        private static List<String> readRespArray(InputStream in) throws IOException {
            int prefix = in.read();
            if (prefix < 0) {
                return null;
            }
            if (prefix != '*') {
                return null;
            }
            int count = Integer.parseInt(readLine(in));
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int p = in.read();
                if (p != '$') {
                    return null;
                }
                int len = Integer.parseInt(readLine(in));
                byte[] buf = in.readNBytes(len);
                if (buf.length < len) {
                    return null;
                }
                in.read(); // \r
                in.read(); // \n
                out.add(new String(buf, StandardCharsets.UTF_8));
            }
            return out;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\r') {
                    in.read(); // \n
                    break;
                }
                buf.write(b);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            server.close();
            for (Socket s : allSockets) {
                try {
                    s.close();
                } catch (IOException ignored) { }
            }
        }
    }
}
