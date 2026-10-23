package com.weartak.atak.weartak_companion.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
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
    private static final String EXPORT_PREFIX = "WearTAK Companion diagnostics\nExport UTC: ";
    private static final String NOT_SENT = "not sent                ";

    interface Storage { File directory() throws IOException; }
    interface CompletionListener { void onComplete(File file, IOException error); }
    interface FailureListener { void onFailure(IOException error); }
    private enum State { IDLE, STARTING, RECORDING, FINISHING, CLOSED }

    private final Storage storage;
    private final FailureListener failureListener;
    private final int fileBytes;
    private final Semaphore slots;
    private final AtomicLong dropped = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "WearTAK-logs");
        thread.setDaemon(true);
        return thread;
    });
    private volatile State state = State.IDLE;
    private FileOutputStream writer;
    private File logFile;
    private IOException writeFailure;
    private int written;
    private boolean capped;

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
                File[] previous = directory.listFiles((dir, name) ->
                        name.startsWith("companion-log-") && name.endsWith(".txt"));
                if (previous == null) throw new IOException("Cannot list Companion logs");
                Arrays.sort(previous, Comparator.comparing(File::getName));
                for (int i = 0; i <= previous.length - FILE_COUNT; i++) {
                    if (!previous[i].delete()) throw new IOException("Cannot remove oldest Companion log");
                }
                String stamp = utc(System.currentTimeMillis()).replace("-", "").replace(":", "");
                logFile = File.createTempFile("companion-log-" + stamp + "-", ".txt", directory);
                writer = new FileOutputStream(logFile);
                written = 0;
                capped = false;
                writeFailure = null;
                dropped.set(0);
                byte[] header = (EXPORT_PREFIX + NOT_SENT + "\n" + metadata.get()
                        + "\nCapture started UTC: " + utc(System.currentTimeMillis())
                        + "\nOnly WTK/* plugin logs; may contain operational data.\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                if (header.length > fileBytes - FOOTER_RESERVE)
                    throw new IOException("Diagnostic metadata exceeds the log limit");
                write(header);
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
        if (state != State.RECORDING || tag == null || !tag.startsWith("WTK/")) return;
        if (!slots.tryAcquire()) {
            dropped.incrementAndGet();
            return;
        }
        long time = System.currentTimeMillis();
        String boundedTag = limit(tag, 128);
        String boundedMessage = limit(message, 4096);
        worker.execute(() -> {
            try {
                if (writeFailure != null || capped) return;
                byte[] line = (utc(time) + " " + level + "/" + boundedTag + " "
                        + boundedMessage + "\n").getBytes(StandardCharsets.UTF_8);
                if (written + line.length > fileBytes - FOOTER_RESERVE) {
                    capped = true;
                    failureListener.onFailure(new IOException(
                            "Companion log size limit reached. Use Send to send captured diagnostics."));
                    return;
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
        long exportTime = System.currentTimeMillis();
        worker.execute(() -> {
            IOException error = finish(exportTime);
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
            IOException error = finish(null);
            if (error != null) failureListener.onFailure(error);
        });
        worker.shutdown();
    }

    private IOException finish(Long exportTime) {
        if (writer == null) return null;
        IOException error = writeFailure;
        try {
            write(("\nCapture finished UTC: " + utc(System.currentTimeMillis())
                    + "\nDropped records (queue full): " + dropped.get()
                    + "\nSize limit reached: " + capped + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            error = appendError(error, failure);
        }
        error = close(error);
        if (exportTime != null) {
            try (RandomAccessFile file = new RandomAccessFile(logFile, "rw")) {
                file.seek(EXPORT_PREFIX.getBytes(StandardCharsets.UTF_8).length);
                file.write(utc(exportTime).getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                error = appendError(error, failure);
            }
        }
        return error;
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

    static String limit(String value, int chars) {
        if (value == null) return "null";
        if (value.length() <= chars) return value;
        int end = chars - 12;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + " [truncated]";
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
