package com.weartak.atak.weartak_companion.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Opt-in single-writer capture. No producer holds a lock used for file I/O. */
final class CompanionDiagnosticLogger {
    static final int FILE_BYTES = 2 * 1024 * 1024;
    static final int FILE_COUNT = 3;
    static final int QUEUE_CAPACITY = 256;
    private static final int FOOTER_RESERVE = 1024;
    private static final String HEADER = "WearTAK Companion diagnostics\n";
    private static final long RATE_LIMIT_MS = 5000L;

    interface Storage { File directory() throws IOException; }
    interface CompletionListener { void onComplete(File file, IOException error); }
    interface FailureListener { void onFailure(IOException error); }
    private enum State { IDLE, STARTING, RECORDING, FINISHING, CLOSED }

    private final Storage storage;
    private final FailureListener failureListener;
    private final int fileBytes;
    private final Semaphore slots;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final Map<String, Long> lastEventTimes = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "WearTAK-logs");
        thread.setDaemon(true);
        return thread;
    });
    private volatile State state = State.IDLE;
    private FileOutputStream writer;
    private File logFile;
    private String metadataText;
    private IOException writeFailure;
    private int written;

    CompanionDiagnosticLogger(Storage storage, FailureListener failureListener) {
        this(storage, failureListener, FILE_BYTES, QUEUE_CAPACITY);
    }

    CompanionDiagnosticLogger(Storage storage, FailureListener failureListener,
                              int fileBytes, int queueCapacity) {
        this.storage = storage;
        this.failureListener = failureListener;
        this.fileBytes = fileBytes;
        slots = new Semaphore(queueCapacity);
    }

    synchronized void start(Supplier<String> metadata, CompletionListener listener) {
        if (state != State.IDLE) {
            listener.onComplete(null, new IOException("A log is already open or the logger is stopped"));
            return;
        }
        state = State.STARTING;
        worker.execute(() -> {
            IOException failure = null;
            try {
                File directory = storage.directory();
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                    throw new IOException("Cannot create Companion log directory");
                pruneLogs(directory);
                metadataText = metadata.get();
                openLogFile(directory, false);
                writeFailure = null;
                dropped.set(0);
                rateLimited.set(0);
                synchronized (lastEventTimes) { lastEventTimes.clear(); }
            } catch (IOException | RuntimeException error) {
                failure = asIo(error);
                failure = close(failure);
            }
            synchronized (this) {
                if (state == State.STARTING) state = failure == null ? State.RECORDING : State.IDLE;
            }
            listener.onComplete(logFile, failure);
        });
    }

    boolean isLogging() { return state == State.RECORDING; }
    boolean isBusy() { return state == State.STARTING || state == State.FINISHING; }

    synchronized void record(String level, String tag, String message) {
        record(level, tag, message, false);
    }

    synchronized void record(String level, String tag, String message, boolean hasError) {
        if (state != State.RECORDING) return;
        String component = safeComponent(tag);
        if (component == null) return;
        String event = safeEvent(level, tag, message, hasError);
        if (event == null) return;
        long time = System.currentTimeMillis();
        String rateKey = component + "/" + safeLevel(level) + "/" + event;
        synchronized (lastEventTimes) {
            Long previous = lastEventTimes.get(rateKey);
            if (previous != null && time - previous < RATE_LIMIT_MS) {
                rateLimited.incrementAndGet();
                return;
            }
            if (lastEventTimes.size() >= 128) lastEventTimes.clear();
            lastEventTimes.put(rateKey, time);
        }
        if (!slots.tryAcquire()) {
            dropped.incrementAndGet();
            return;
        }
        worker.execute(() -> {
            try {
                if (writeFailure != null) return;
                byte[] line = (utc(time) + " " + safeLevel(level) + "/" + component
                        + " event=" + event + "\n").getBytes(StandardCharsets.UTF_8);
                if (written + line.length > fileBytes - FOOTER_RESERVE) {
                    rotateLog();
                }
                write(line);
            } catch (IOException error) {
                writeFailure = error;
                failureListener.onFailure(error);
            } finally {
                slots.release();
            }
        });
    }

    synchronized void stop(CompletionListener listener) {
        if (state != State.RECORDING) {
            listener.onComplete(null, new IOException("There is no Companion log to finish"));
            return;
        }
        state = State.FINISHING;
        worker.execute(() -> {
            IOException error = finish();
            synchronized (this) {
                if (state == State.FINISHING) state = State.IDLE;
            }
            listener.onComplete(logFile, error);
        });
    }

    synchronized void shutdown() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        worker.execute(() -> {
            IOException error = finish();
            if (error != null) failureListener.onFailure(error);
        });
        worker.shutdown();
    }

    private IOException finish() {
        if (writer == null) return null;
        IOException error = writeFailure;
        try {
            write(("\nCapture finished UTC: " + utc(System.currentTimeMillis())
                    + "\nDropped records (queue full): " + dropped.get()
                    + "\nRate-limited duplicate events: " + rateLimited.get() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            error = appendError(error, failure);
        }
        error = close(error);
        return error;
    }

    synchronized File[] logs() throws IOException {
        File directory = storage.directory();
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("companion-log-") && name.endsWith(".txt"));
        if (files == null) throw new IOException("Cannot list Companion logs");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private void rotateLog() throws IOException {
        File directory = storage.directory();
        write(("\nCapture segment continued in the next retained log.\n")
                .getBytes(StandardCharsets.UTF_8));
        IOException error = close(null);
        if (error != null) throw error;
        pruneLogs(directory);
        openLogFile(directory, true);
    }

    private void openLogFile(File directory, boolean continuation) throws IOException {
        String stamp = utc(System.currentTimeMillis()).replace("-", "").replace(":", "");
        logFile = File.createTempFile("companion-log-" + stamp + "-", ".txt", directory);
        writer = new FileOutputStream(logFile);
        written = 0;
        byte[] header = (HEADER + metadataText
                + "\nCapture " + (continuation ? "continued" : "started") + " UTC: "
                + utc(System.currentTimeMillis())
                + "\nPrivacy-safe WTK events only; message content and identifiers are excluded.\n\n")
                .getBytes(StandardCharsets.UTF_8);
        if (header.length > fileBytes - FOOTER_RESERVE)
            throw new IOException("Diagnostic metadata exceeds the log limit");
        write(header);
    }

    private void pruneLogs(File directory) throws IOException {
        File[] previous = directory.listFiles((dir, name) ->
                name.startsWith("companion-log-") && name.endsWith(".txt"));
        if (previous == null) throw new IOException("Cannot list Companion logs");
        Arrays.sort(previous, Comparator.comparing(File::getName));
        int remove = Math.max(0, previous.length - FILE_COUNT + 1);
        for (int i = 0; i < remove; i++) {
            if (!previous[i].delete()) throw new IOException("Cannot remove oldest Companion log");
        }
    }

    private void write(byte[] bytes) throws IOException {
        writer.write(bytes);
        writer.flush();
        written += bytes.length;
    }

    private IOException close(IOException error) {
        if (writer != null) {
            try { writer.close(); }
            catch (IOException failure) { error = appendError(error, failure); }
            writer = null;
        }
        return error;
    }

    private static IOException asIo(Exception error) {
        return error instanceof IOException ? (IOException) error : new IOException(error);
    }

    private static IOException appendError(IOException first, IOException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static String safeComponent(String tag) {
        if (tag == null || !tag.startsWith("WTK/")) return null;
        String component = tag.substring(4);
        if (component.length() == 0 || component.length() > 48) return "Plugin";
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return "Plugin";
        }
        return component;
    }

    private static String safeLevel(String level) {
        if ("E".equals(level)) return "E";
        if ("W".equals(level)) return "W";
        return "I";
    }

    private static String safeEvent(String level, String tag, String message, boolean hasError) {
        String text = message == null ? "" : message.toLowerCase(Locale.US);
        if (hasError || "E".equals(level)) return "failure";
        if (text.contains("scan")) return "transport_scan";
        if (text.contains("disconnect")) return "transport_disconnect";
        if (text.contains("connect")) return "transport_connect";
        if (text.contains("location") || text.contains("gnss") || text.contains("self-pli"))
            return "location";
        if (text.contains("emergency") || text.contains("marker") || text.contains("cot"))
            return "report";
        if (text.contains("negotiat") || text.contains("capabilit") ||
                text.contains("handshake") || text.contains("ready"))
            return "negotiation";
        if (text.contains("sync") || text.contains("settings") || text.contains("identity"))
            return "sync";
        if (text.contains("command")) return "command";
        if (text.contains("battery") || text.contains("power")) return "power";
        if (text.contains("start") || text.contains("stop") || text.contains("lifecycle"))
            return "lifecycle";
        if (tag != null && (tag.contains("Location") || tag.contains("Gnss") ||
                tag.contains("Position") || tag.contains("SelfPli"))) return "location";
        if (tag != null && (tag.contains("Ble") || tag.contains("Garmin") ||
                tag.contains("Gateway"))) return "transport";
        if (tag != null && (tag.contains("Settings") || tag.contains("Sync") ||
                tag.contains("Identity"))) return "sync";
        if (tag != null && (tag.contains("Plugin") || tag.contains("Lifecycle")))
            return "lifecycle";
        if ("W".equals(level)) return "warning";
        return null;
    }

    static String utc(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(time));
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }
}
