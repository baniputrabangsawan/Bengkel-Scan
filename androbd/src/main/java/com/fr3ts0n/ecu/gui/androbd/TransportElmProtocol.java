package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.prot.ProtUtils;
import com.fr3ts0n.prot.TelegramWriter;

import java.util.Arrays;
import java.util.logging.Level;

/**
 * Publish a writer snapshot only when connections change. Never hold a registry lock during socket
 * I/O: a concurrent disconnect holds the session lock and must be able to remove its writer.
 * The library parser remains unchanged; sending allocates neither a snapshot nor an iterator.
 */
class TransportElmProtocol extends ElmProt {
    private volatile TelegramWriter[] writers = new TelegramWriter[0];

    @Override public synchronized boolean addTelegramWriter(TelegramWriter writer) {
        TelegramWriter[] next = Arrays.copyOf(writers, writers.length + 1);
        next[next.length - 1] = writer;
        writers = next;
        return true;
    }

    @Override public synchronized boolean removeTelegramWriter(TelegramWriter writer) {
        TelegramWriter[] previous = writers;
        for (int index = 0; index < previous.length; index++) {
            if (writer == null ? previous[index] != null : !writer.equals(previous[index])) continue;
            TelegramWriter[] next = new TelegramWriter[previous.length - 1];
            System.arraycopy(previous, 0, next, 0, index);
            System.arraycopy(previous, index + 1, next, index, next.length - index);
            writers = next;
            return true;
        }
        return false;
    }

    @Override public void sendTelegram(char[] buffer) {
        if (log.isLoggable(Level.FINER)) log.finer(this + " TX:" + ProtUtils.hexDumpBuffer(buffer));
        for (TelegramWriter writer : writers) {
            if (writer != null) writer.writeTelegram(buffer, 0, null);
        }
    }
}
