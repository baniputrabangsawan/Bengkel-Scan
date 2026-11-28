package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.prot.TelegramWriter;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TransportElmProtocolTest {
    @Test public void disconnectDoesNotWaitForBlockedWriterOrMutateSendSnapshot() throws Exception {
        TransportElmProtocol protocol = new TransportElmProtocol();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger nextWriterCalls = new AtomicInteger();
        TelegramWriter blocking = new TelegramWriter() {
            @Override public int writeTelegram(char[] buffer) { return writeTelegram(buffer, 0, null); }
            @Override public int writeTelegram(char[] buffer, int type, Object id) {
                sending.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                return buffer.length;
            }
        };
        protocol.addTelegramWriter(blocking);
        protocol.addTelegramWriter(new TelegramWriter() {
            @Override public int writeTelegram(char[] buffer) { return writeTelegram(buffer, 0, null); }
            @Override public int writeTelegram(char[] buffer, int type, Object id) {
                nextWriterCalls.incrementAndGet();
                return buffer.length;
            }
        });
        Thread sender = new Thread(() -> {
            try { protocol.sendTelegram("ATI".toCharArray()); }
            catch (Throwable ex) { error.set(ex); }
        });
        Thread disconnect = new Thread(() -> {
            removing.countDown();
            protocol.removeTelegramWriter(blocking);
            removed.countDown();
        });
        try {
            sender.start();
            assertTrue(sending.await(2, TimeUnit.SECONDS));
            disconnect.start();
            assertTrue(removing.await(2, TimeUnit.SECONDS));
            assertTrue("Disconnect must not wait for socket I/O while holding its session lock", removed.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            sender.join(2_000);
            disconnect.join(2_000);
        }
        assertFalse(sender.isAlive());
        assertFalse(disconnect.isAlive());
        assertNull(error.get());
        assertEquals(1, nextWriterCalls.get());
        assertEquals(0, removed.getCount());
        protocol.sendTelegram("010C".toCharArray());
        assertEquals(2, nextWriterCalls.get());
    }
}
