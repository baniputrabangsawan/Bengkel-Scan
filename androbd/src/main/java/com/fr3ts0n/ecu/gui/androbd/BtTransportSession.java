package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.prot.StreamHandler;
import com.fr3ts0n.prot.TelegramListener;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Socket ownership around the existing ELM protocol, independent of the Android Bluetooth API. */
final class BtTransportSession {
    enum Failure { CONNECTION, STREAMS, PERMISSION, TIMEOUT }

    interface Socket {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void close() throws IOException;
    }

    interface SocketFactory { Socket create(boolean fallback) throws IOException; }
    interface Listener {
        void connected();
        void failed(Failure failure);
        void disconnected();
    }
    interface Cancellation { void cancel(); }
    interface Scheduler { Cancellation schedule(Runnable task, long delayMs); }

    private static final Logger LOG = Logger.getLogger(BtTransportSession.class.getSimpleName());
    private static final Timer DEADLINES = new Timer("OBD Bluetooth deadlines", true);
    static final Scheduler SCHEDULER = (task, delayMs) -> {
        TimerTask timerTask = new TimerTask() {
            @Override public void run() { task.run(); }
        };
        DEADLINES.schedule(timerTask, delayMs);
        return () -> { timerTask.cancel(); DEADLINES.purge(); };
    };

    private final Object lock;
    private final ElmProt protocol;
    private final TelegramListener receiver;
    private final Listener listener;
    private final Scheduler scheduler;
    private final long timeoutMs;
    private final long settleMs;
    private Attempt attempt;
    private Worker worker;

    BtTransportSession(Object lock, ElmProt protocol, Listener listener,
                       Scheduler scheduler, long timeoutMs, long settleMs) {
        this(lock, protocol, listener, scheduler, timeoutMs, settleMs, protocol::handleTelegram);
    }

    BtTransportSession(Object lock, ElmProt protocol, Listener listener,
                       Scheduler scheduler, long timeoutMs, long settleMs, TelegramListener receiver) {
        this.receiver = receiver;
        this.lock = lock;
        this.protocol = protocol;
        this.listener = listener;
        this.scheduler = scheduler;
        this.timeoutMs = timeoutMs;
        this.settleMs = settleMs;
    }

    void connect(SocketFactory factory) {
        synchronized (lock) {
            stopLocked();
            Attempt next = new Attempt(factory);
            attempt = next;
            next.deadline = scheduler.schedule(() -> fail(next, Failure.TIMEOUT), timeoutMs);
            next.start();
        }
    }

    void stop() {
        synchronized (lock) { stopLocked(); }
    }

    void write(byte[] bytes) {
        synchronized (lock) {
            if (worker != null) worker.stream.writeTelegram(new String(bytes,
                    java.nio.charset.StandardCharsets.US_ASCII).toCharArray());
        }
    }

    private void stopLocked() {
        Attempt oldAttempt = attempt;
        attempt = null;
        if (oldAttempt != null) {
            if (oldAttempt.deadline != null) oldAttempt.deadline.cancel();
            oldAttempt.interrupt();
            close(oldAttempt.socket);
        }
        Worker oldWorker = worker;
        worker = null;
        if (oldWorker != null) {
            protocol.removeTelegramWriter(oldWorker.stream);
            oldWorker.interrupt();
            close(oldWorker.socket);
        }
    }

    private void fail(Attempt source, Failure failure) {
        synchronized (lock) {
            if (attempt != source) return;
            stopLocked();
            listener.failed(failure);
        }
    }

    private boolean current(Worker source) {
        synchronized (lock) { return worker == source; }
    }

    private void workerEnded(Worker source, Failure failure) {
        synchronized (lock) {
            if (worker != source) return;
            stopLocked();
            if (failure == Failure.PERMISSION) listener.failed(failure);
            else listener.disconnected();
        }
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); }
        catch (IOException | SecurityException ex) { LOG.log(Level.FINE, "Closing Bluetooth socket", ex); }
    }

    private final class Attempt extends Thread {
        final SocketFactory factory;
        Socket socket;
        Cancellation deadline;

        Attempt(SocketFactory factory) {
            super("OBD Bluetooth connect");
            this.factory = factory;
            setDaemon(true);
        }

        private boolean active() {
            synchronized (lock) { return attempt == this; }
        }

        private Socket open(boolean fallback) throws IOException {
            // Creation may itself block in a vendor implementation. Never hold the lifecycle lock.
            if (!active()) return null;
            Socket created = factory.create(fallback);
            synchronized (lock) {
                if (attempt != this) { close(created); return null; }
                if (created == null) throw new IOException("Bluetooth socket creation returned null");
                socket = created;
                return created;
            }
        }

        @Override public void run() {
            Socket connected = null;
            for (int stage = 0; stage < 2 && active(); stage++) {
                try {
                    connected = open(stage == 1);
                    if (connected == null) return;
                    connected.connect();
                    break;
                } catch (IllegalStateException ex) {
                    fail(this, Failure.CONNECTION);
                    return;
                } catch (SecurityException ex) {
                    LOG.log(Level.WARNING, "Bluetooth permission denied", ex);
                    fail(this, Failure.PERMISSION);
                    return;
                } catch (IOException ex) {
                    LOG.log(Level.FINE, "Bluetooth connection attempt failed", ex);
                    synchronized (lock) {
                        if (attempt != this) return;
                        close(socket);
                        socket = null;
                    }
                    connected = null;
                }
            }
            if (!active()) return;
            if (connected == null) { fail(this, Failure.CONNECTION); return; }
            try {
                // Preserve the adapter settling workaround for AndrOBD/#233, but keep it cancelable.
                if (settleMs > 0) Thread.sleep(settleMs);
                if (!active()) return;
                InputStream input = connected.input();
                OutputStream output = connected.output();
                if (input == null || output == null) throw new IOException("Missing Bluetooth streams");
                synchronized (lock) {
                    if (attempt != this) return;
                    Worker next = new Worker(connected, input, output);
                    deadline.cancel();
                    attempt = null;
                    worker = next;
                    // A new StreamHandler is attached for every session; stopped writers never survive.
                    protocol.addTelegramWriter(next.stream);
                    listener.connected();
                    if (worker == next) next.start();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail(this, Failure.CONNECTION);
            } catch (IllegalStateException ex) {
                fail(this, Failure.STREAMS);
            } catch (SecurityException ex) {
                fail(this, Failure.PERMISSION);
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Opening Bluetooth streams", ex);
                fail(this, Failure.STREAMS);
            }
        }
    }

    private final class Worker extends Thread {
        final Socket socket;
        final StreamHandler stream;

        Worker(Socket socket, InputStream input, OutputStream output) {
            super("OBD Bluetooth receive");
            setDaemon(true);
            this.socket = socket;
            stream = new StreamHandler(new SessionInput(input), new SessionOutput(output));
            stream.setMessageHandler(message -> {
                synchronized (lock) {
                    // Prevent a late receive callback from contaminating a replacement session.
                    return worker == this ? receiver.handleTelegram(message) : 0;
                }
            });
        }

        @Override public void run() {
            try { stream.run(); }
            finally { workerEnded(this, Failure.CONNECTION); }
        }

        private void requireCurrent() throws IOException {
            if (!current(this)) throw new IOException("Bluetooth session canceled");
        }

        /**
         * StreamHandler polls available(), which cannot distinguish idle from EOF. Prefetch one byte
         * on its receive thread so a remote EOF is observable without changing the library parser.
         * Socket.close() and interruption unblock this read on cancellation.
         */
        private final class SessionInput extends InputStream {
            final InputStream input;
            int next = -1;
            SessionInput(InputStream input) { this.input = input; }
            @Override public int available() throws IOException {
                requireCurrent();
                if (next < 0) {
                    try { next = input.read(); }
                    catch (SecurityException ex) {
                        workerEnded(Worker.this, Failure.PERMISSION);
                        throw new IOException("Bluetooth permission revoked", ex);
                    }
                    if (next < 0) throw new EOFException("Bluetooth adapter disconnected");
                }
                requireCurrent();
                return 1;
            }
            @Override public int read() throws IOException {
                if (available() == 0) return -1;
                int result = next;
                next = -1;
                return result;
            }
        }

        /** Report asynchronous StreamHandler write errors instead of leaving an apparently live link. */
        private final class SessionOutput extends OutputStream {
            final OutputStream output;
            SessionOutput(OutputStream output) { this.output = output; }
            @Override public void write(int value) throws IOException {
                requireCurrent();
                try { output.write(value); }
                catch (IOException ex) { workerEnded(Worker.this, Failure.CONNECTION); throw ex; }
                catch (SecurityException ex) {
                    workerEnded(Worker.this, Failure.PERMISSION);
                    throw new IOException("Bluetooth permission revoked", ex);
                }
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                requireCurrent();
                try { output.write(bytes, offset, length); }
                catch (IOException ex) { workerEnded(Worker.this, Failure.CONNECTION); throw ex; }
                catch (SecurityException ex) {
                    workerEnded(Worker.this, Failure.PERMISSION);
                    throw new IOException("Bluetooth permission revoked", ex);
                }
            }
            @Override public void flush() throws IOException {
                requireCurrent();
                try { output.flush(); }
                catch (IOException ex) { workerEnded(Worker.this, Failure.CONNECTION); throw ex; }
                catch (SecurityException ex) {
                    workerEnded(Worker.this, Failure.PERMISSION);
                    throw new IOException("Bluetooth permission revoked", ex);
                }
            }
        }
    }
}
