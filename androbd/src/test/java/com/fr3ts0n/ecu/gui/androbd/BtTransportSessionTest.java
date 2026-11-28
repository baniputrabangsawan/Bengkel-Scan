package com.fr3ts0n.ecu.gui.androbd;


import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the production socket lifecycle and the real StreamHandler/ElmProt boundary. */
public class BtTransportSessionTest {
    @Test public void reconnectAfterStopRegistersOnlyTheNewWriter() throws Exception {
        Fixture f = new Fixture();
        try {
            FakeSocket first = new FakeSocket();
            f.connect(first);
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals("ATI\r", first.output.takeWrite());
            f.session.stop();
            FakeSocket second = new FakeSocket();
            f.connect(second);
            f.protocol.sendTelegram("010C".toCharArray());
            assertEquals("010C\r", second.output.takeWrite());
            assertEquals("ATI\r", first.output.text());
            assertEquals(2, f.events.connected.get());
            assertEquals(0, f.events.lost.get());
        } finally { f.session.stop(); }
    }

    @Test public void midSessionEofDisconnectsAndAllowsReconnect() throws Exception {
        Fixture f = new Fixture();
        try {
            FakeSocket first = new FakeSocket();
            f.connect(first);
            first.input.offer("410C1AF8\r>");
            assertEquals("410C1AF8", f.protocol.received.poll(2, TimeUnit.SECONDS));
            assertEquals(">", f.protocol.received.poll(2, TimeUnit.SECONDS));
            first.input.end();
            assertTrue(f.events.disconnected.await(2, TimeUnit.SECONDS));
            assertTrue(first.closed);
            assertEquals(1, f.events.lost.get());
            FakeSocket second = new FakeSocket();
            f.connect(second);
            f.protocol.sendTelegram("0105".toCharArray());
            assertEquals("0105\r", second.output.takeWrite());
        } finally { f.session.stop(); }
    }

    @Test public void canceledBlockedConnectCannotFallbackOrDisconnectReplacement() throws Exception {
        Fixture f = new Fixture();
        BlockingSocket old = new BlockingSocket();
        AtomicInteger fallbackCalls = new AtomicInteger();
        try {
            f.session.connect(fallback -> {
                if (fallback) fallbackCalls.incrementAndGet();
                return old;
            });
            assertTrue(old.entered.await(2, TimeUnit.SECONDS));
            f.session.stop();
            FakeSocket replacement = new FakeSocket();
            f.connect(replacement);
            old.release.countDown();
            assertTrue(old.finished.await(2, TimeUnit.SECONDS));
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals("ATI\r", replacement.output.takeWrite());
            assertEquals(0, fallbackCalls.get());
            assertEquals(0, f.events.failures.size());
            assertEquals(0, f.events.lost.get());
        } finally { old.release.countDown(); f.session.stop(); }
    }

    @Test public void cancellationDuringSocketCreationClosesItWithoutConnecting() throws Exception {
        Fixture f = new Fixture();
        FakeSocket socket = new FakeSocket();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        try {
            f.session.connect(fallback -> {
                creating.countDown();
                awaitUninterruptibly(release);
                returned.countDown();
                return socket;
            });
            assertTrue(creating.await(2, TimeUnit.SECONDS));
            f.session.stop();
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(socket.closedLatch.await(2, TimeUnit.SECONDS));
            assertEquals(0, socket.connects.get());
            assertEquals(0, f.events.connected.get());
        } finally { release.countDown(); f.session.stop(); }
    }

    @Test public void failedPrimaryUsesExactlyOneCloneFallback() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger creations = new AtomicInteger();
        FakeSocket fallbackSocket = new FakeSocket();
        try {
            f.session.connect(fallback -> {
                creations.incrementAndGet();
                if (!fallback) throw new IOException("Broken SDP implementation");
                return fallbackSocket;
            });
            assertTrue(f.events.established.poll(2, TimeUnit.SECONDS) != null);
            assertEquals(2, creations.get());
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals("ATI\r", fallbackSocket.output.takeWrite());
        } finally { f.session.stop(); }
    }

    @Test public void deniedPermissionDoesNotRunCloneFallback() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger creations = new AtomicInteger();
        try {
            f.session.connect(fallback -> {
                creations.incrementAndGet();
                throw new SecurityException("revoked");
            });
            assertEquals(BtTransportSession.Failure.PERMISSION,
                    f.events.failures.poll(2, TimeUnit.SECONDS));
            assertEquals(1, creations.get());
            assertEquals(0, f.events.connected.get());
        } finally { f.session.stop(); }
    }

    @Test public void nullOrFailedStreamsNeverEstablishAConnection() throws Exception {
        for (boolean throwsIo : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            FakeSocket socket = new FakeSocket() {
                @Override public OutputStream output() throws IOException {
                    if (throwsIo) throw new IOException("stream creation failed");
                    return null;
                }
            };
            try {
                f.session.connect(fallback -> socket);
                assertEquals(BtTransportSession.Failure.STREAMS,
                        f.events.failures.poll(2, TimeUnit.SECONDS));
                assertEquals(0, f.events.connected.get());
                assertTrue(socket.closed);
            } finally { f.session.stop(); }
        }
    }

    @Test public void deadlineClosesHungConnectAndLateSuccessIsIgnored() throws Exception {
        Fixture f = new Fixture();
        BlockingSocket socket = new BlockingSocket();
        try {
            f.session.connect(fallback -> socket);
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS));
            f.scheduler.expire();
            assertEquals(BtTransportSession.Failure.TIMEOUT,
                    f.events.failures.poll(2, TimeUnit.SECONDS));
            assertTrue(socket.closed);
            socket.release.countDown();
            assertTrue(socket.finished.await(2, TimeUnit.SECONDS));
            assertEquals(0, f.events.connected.get());
            assertEquals(0, f.events.lost.get());
        } finally { socket.release.countDown(); f.session.stop(); }
    }

    @Test public void outputFailureIsReportedInsteadOfLeavingDeadWriter() throws Exception {
        Fixture f = new Fixture();
        FakeSocket socket = new FakeSocket() {
            @Override public OutputStream output() {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        throw new IOException("adapter disconnected during write");
                    }
                };
            }
        };
        try {
            f.connect(socket);
            f.protocol.sendTelegram("ATI".toCharArray());
            assertTrue(f.events.disconnected.await(2, TimeUnit.SECONDS));
            assertEquals(1, f.events.lost.get());
            FakeSocket next = new FakeSocket();
            f.connect(next);
            f.protocol.sendTelegram("010C".toCharArray());
            assertEquals("010C\r", next.output.takeWrite());
        } finally { f.session.stop(); }
    }

    @Test public void staleReceiveCannotAffectReplacement() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        FakeSocket old = new FakeSocket() {
            @Override public InputStream input() {
                return new InputStream() {
                    @Override public int read() {
                        reading.countDown();
                        awaitUninterruptibly(release);
                        returned.countDown();
                        return '>';
                    }
                };
            }
        };
        try {
            f.connect(old);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            FakeSocket replacement = new FakeSocket();
            f.connect(replacement);
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            replacement.input.offer("OK\r");
            assertEquals("OK", f.protocol.received.poll(2, TimeUnit.SECONDS));
            assertNull(f.protocol.received.poll(50, TimeUnit.MILLISECONDS));
            assertEquals(0, f.events.lost.get());
            assertEquals(0, f.events.failures.size());
        } finally { release.countDown(); f.session.stop(); }
    }

    @Test public void expiredOldConnectDeadlineDoesNotCloseReplacement() throws Exception {
        Fixture f = new Fixture();
        BlockingSocket old = new BlockingSocket();
        try {
            f.session.connect(fallback -> old);
            assertTrue(old.entered.await(2, TimeUnit.SECONDS));
            Runnable oldDeadline = f.scheduler.deadline;
            FakeSocket next = new FakeSocket();
            f.connect(next);
            oldDeadline.run();
            old.release.countDown();
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals("ATI\r", next.output.takeWrite());
            assertFalse(next.closed);
            assertEquals(0, f.events.failures.size());
        } finally { old.release.countDown(); f.session.stop(); }
    }

    @Test public void nullSocketCreationFailsCleanlyAfterBoundedFallback() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        try {
            f.session.connect(fallback -> { calls.incrementAndGet(); return null; });
            assertEquals(BtTransportSession.Failure.CONNECTION,
                    f.events.failures.poll(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals(0, f.events.connected.get());
        } finally { f.session.stop(); }
    }

    @Test public void observerOnlyRunsAfterSuccessfulCurrentPacketParsing() throws Exception {
        LinkedBlockingQueue<String> observed = new LinkedBlockingQueue<>();
        TransportElmProtocol protocol = new TransportElmProtocol() {
            @Override public int handleTelegram(char[] packet) {
                if (new String(packet).equals("BAD")) throw new IllegalArgumentException("invalid packet");
                return 7;
            }
        };
        Events events = new Events();
        BtTransportSession session = new BtTransportSession(new Object(), protocol, events,
                new ManualScheduler(), 20_000, 0, packet -> {
                    int result = protocol.handleTelegram(packet);
                    observed.offer(new String(packet));
                    return result;
                });
        FakeSocket socket = new FakeSocket();
        try {
            session.connect(fallback -> socket);
            assertNotNull(events.established.poll(2, TimeUnit.SECONDS));
            socket.input.offer("BAD\rGOOD\r");
            assertEquals("GOOD", observed.poll(2, TimeUnit.SECONDS));
            assertNull(observed.poll(50, TimeUnit.MILLISECONDS));
        } finally { session.stop(); }
    }

    @Test public void permissionRevokedDuringOutputEndsSessionCleanly() throws Exception {
        Fixture f = new Fixture();
        FakeSocket socket = new FakeSocket() {
            @Override public OutputStream output() {
                return new OutputStream() {
                    @Override public void write(int value) { throw new SecurityException("revoked"); }
                };
            }
        };
        try {
            f.connect(socket);
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals(BtTransportSession.Failure.PERMISSION,
                    f.events.failures.poll(2, TimeUnit.SECONDS));
            assertTrue(socket.closed);
            assertEquals(0, f.events.lost.get());
            FakeSocket replacement = new FakeSocket();
            f.connect(replacement);
            f.protocol.sendTelegram("010C".toCharArray());
            assertEquals("010C\r", replacement.output.takeWrite());
        } finally { f.session.stop(); }
    }

    @Test public void canceledConnectFailureDoesNotRunFallbackOnNewSession() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FakeSocket old = new FakeSocket() {
            @Override public void connect() throws IOException {
                entered.countDown();
                awaitUninterruptibly(release);
                finished.countDown();
                throw new IOException("old connection failed after cancel");
            }
        };
        try {
            f.session.connect(fallback -> {
                if (fallback) fallbackCalls.incrementAndGet();
                return old;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            FakeSocket replacement = new FakeSocket();
            f.connect(replacement);
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            f.protocol.sendTelegram("ATI".toCharArray());
            assertEquals("ATI\r", replacement.output.takeWrite());
            assertEquals(0, fallbackCalls.get());
            assertEquals(0, f.events.lost.get());
            assertNull(f.events.failures.poll(50, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); f.session.stop(); }
    }

    private static final class Fixture {
        final RecordingElm protocol = new RecordingElm();
        final Events events = new Events();
        final ManualScheduler scheduler = new ManualScheduler();
        final BtTransportSession session = new BtTransportSession(
                new Object(), protocol, events, scheduler, 20_000, 0);
        void connect(FakeSocket socket) throws Exception {
            session.connect(fallback -> socket);
            assertNotNull(events.established.poll(2, TimeUnit.SECONDS));
        }
    }

    private static final class RecordingElm extends TransportElmProtocol {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        @Override public int handleTelegram(char[] message) {
            received.offer(new String(message));
            return 0;
        }
    }

    private static final class Events implements BtTransportSession.Listener {
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger lost = new AtomicInteger();
        final LinkedBlockingQueue<Boolean> established = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<BtTransportSession.Failure> failures = new LinkedBlockingQueue<>();
        final CountDownLatch disconnected = new CountDownLatch(1);
        @Override public void connected() { connected.incrementAndGet(); established.offer(true); }
        @Override public void failed(BtTransportSession.Failure failure) { failures.offer(failure); }
        @Override public void disconnected() { lost.incrementAndGet(); disconnected.countDown(); }
    }

    private static final class ManualScheduler implements BtTransportSession.Scheduler {
        Runnable deadline;
        @Override public synchronized BtTransportSession.Cancellation schedule(Runnable task, long delayMs) {
            deadline = task;
            return () -> { synchronized (this) { if (deadline == task) deadline = null; } };
        }
        void expire() {
            Runnable task;
            synchronized (this) { task = deadline; }
            assertNotNull(task);
            task.run();
        }
    }

    private static class FakeSocket implements BtTransportSession.Socket {
        final FakeInput input = new FakeInput();
        final CapturingOutput output = new CapturingOutput();
        final AtomicInteger connects = new AtomicInteger();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile boolean closed;
        @Override public void connect() throws IOException { connects.incrementAndGet(); }
        @Override public InputStream input() throws IOException { return input; }
        @Override public OutputStream output() throws IOException { return output; }
        @Override public void close() {
            closed = true;
            input.end();
            closedLatch.countDown();
        }
    }

    private static final class BlockingSocket extends FakeSocket {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public void connect() {
            entered.countDown();
            awaitUninterruptibly(release);
            finished.countDown();
            // Deliberately simulates a vendor stack returning success after cancellation.
        }
    }

    private static final class FakeInput extends InputStream {
        final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
        void offer(String text) { for (char ch : text.toCharArray()) bytes.offer((int) ch); }
        void end() { bytes.offer(-1); }
        @Override public int available() { return Math.max(0, bytes.size()); }
        @Override public int read() throws IOException {
            try { return bytes.take(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
        }
    }

    private static final class CapturingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final LinkedBlockingQueue<String> writes = new LinkedBlockingQueue<>();
        final StringBuilder command = new StringBuilder();
        @Override public synchronized void write(int value) {
            bytes.write(value);
            command.append((char) value);
            if (value == '\r') { writes.offer(command.toString()); command.setLength(0); }
        }
        synchronized String text() { return bytes.toString(); }
        String takeWrite() throws Exception {
            String result = writes.poll(2, TimeUnit.SECONDS);
            assertNotNull(result);
            return result;
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
