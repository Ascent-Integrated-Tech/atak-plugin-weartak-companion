package com.weartak.atak.weartak_companion.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CompanionDiagnosticLoggerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String METADATA = "Plugin version: test-plugin\nATAK version: test-atak"
            + "\nDevice model: test-device\nAndroid version: test-android";

    private File start(CompanionDiagnosticLogger logger) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> result = new AtomicReference<>();
        AtomicReference<IOException> error = new AtomicReference<>();
        logger.start(() -> METADATA, (file, failure) -> {
            result.set(file);
            error.set(failure);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        return result.get();
    }

    private File stop(CompanionDiagnosticLogger logger) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> result = new AtomicReference<>();
        AtomicReference<IOException> error = new AtomicReference<>();
        logger.stop((file, failure) -> {
            result.set(file);
            error.set(failure);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        return result.get();
    }

    private void shutdown(CompanionDiagnosticLogger logger) throws Exception {
        logger.shutdown();
        assertTrue(logger.awaitTermination(5, TimeUnit.SECONDS));
    }

    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void optInCaptureIncludesMetadataUtcAndPluginEventsOnly() throws Exception {
        File directory = new File(temp.getRoot(), "companion-logs");
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(() -> directory, error -> fail(error.toString()));
        boolean enabled = LogX.ENABLED;
        try {
            LogX.ENABLED = false;
            LogX.attach(logger);
            LogX.i("WTK/BleClient", "before-start");
            assertFalse(directory.exists());
            File file = start(logger);
            LogX.i("WTK/BleClient", "SCAN: started");
            LogX.i("WTK/BleClient", "CONNECT: requested");
            LogX.d("WTK/BleCotBridge", "dispatchCot(PLI): dispatched INTERNAL");
            LogX.w("WTK/BleClient", "DISCONNECT");
            LogX.e("WTK/Plugin", "failure example", new IOException("bounded stack"));
            LogX.i("AndroidSystem", "must-not-capture");
            assertEquals(file, stop(logger));
            LogX.i("WTK/BleClient", "after-stop");
            String text = read(file);
            assertTrue(text.contains(METADATA));
            assertTrue(text.matches("(?s).*Export UTC: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z\\n.*"));
            assertFalse(text.contains("not sent"));
            assertTrue(text.contains("SCAN: started"));
            assertTrue(text.contains("CONNECT: requested"));
            assertTrue(text.contains("dispatchCot(PLI)"));
            assertTrue(text.contains("DISCONNECT"));
            assertTrue(text.contains("IOException: bounded stack"));
            assertTrue(text.indexOf("SCAN:") < text.indexOf("CONNECT:"));
            assertFalse(text.contains("before-start"));
            assertFalse(text.contains("after-stop"));
            assertFalse(text.contains("must-not-capture"));
            assertTrue(text.contains("Dropped records (queue full): 0"));
        } finally {
            LogX.detach(logger);
            LogX.ENABLED = enabled;
            shutdown(logger);
        }
    }

    @Test public void restartPreservesFilesAndRetainsOnlyThreeSessions() throws Exception {
        File unrelated = temp.newFile("keep.txt");
        for (int i = 0; i < 5; i++) {
            CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot, error -> fail(error.toString()));
            try {
                start(logger);
                logger.record("I", "WTK/BleClient", "session-" + i);
                stop(logger);
            } finally { shutdown(logger); }
        }
        File[] logs = temp.getRoot().listFiles((dir, name) -> name.startsWith("companion-log-"));
        assertNotNull(logs);
        assertEquals(CompanionDiagnosticLogger.FILE_COUNT, logs.length);
        StringBuilder retained = new StringBuilder();
        for (File file : logs) retained.append(read(file));
        assertTrue(retained.toString().contains("session-4"));
        assertFalse(retained.toString().contains("session-0"));
        assertFalse(retained.toString().contains("session-1"));
        assertTrue(unrelated.exists());
    }

    @Test public void productionSizeCapKeepsValidUtf8AndReportsPartialCapture() throws Exception {
        AtomicReference<IOException> notice = new AtomicReference<>();
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot, notice::set);
        try {
            start(logger);
            String large = String.join("", Collections.nCopies(4096, "\u6f22"));
            for (int i = 0; i < 3000; i++) logger.record("D", "WTK/Test", large);
            File file = stop(logger);
            assertEquals(2 * 1024 * 1024, CompanionDiagnosticLogger.FILE_BYTES);
            assertTrue(file.length() <= CompanionDiagnosticLogger.FILE_BYTES);
            assertTrue(file.length() > CompanionDiagnosticLogger.FILE_BYTES - 32 * 1024);
            String text = read(file);
            assertFalse(text.contains("\ufffd"));
            assertTrue(text.contains("Size limit reached: true"));
            assertNotNull(notice.get());
            assertTrue(notice.get().getMessage().contains("Use Send"));
        } finally { shutdown(logger); }
    }

    @Test public void fullQueueDoesNotBlockStopAndDroppedRecordsAreExplicit() throws Exception {
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot, error -> {
            workerBlocked.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, 4096, 2);
        try {
            start(logger);
            logger.record("D", "WTK/Test", String.join("", Collections.nCopies(4096, "x")));
            assertTrue(workerBlocked.await(5, TimeUnit.SECONDS));
            logger.record("I", "WTK/Test", "one");
            logger.record("I", "WTK/Test", "two");
            logger.record("I", "WTK/Test", "three");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<File> result = new AtomicReference<>();
            logger.stop((file, error) -> {
                assertNull(error);
                result.set(file);
                finished.countDown();
            });
            assertEquals(1, finished.getCount());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(read(result.get()).contains("Dropped records (queue full): 2"));
        } finally {
            release.countDown();
            shutdown(logger);
        }
    }

    @Test public void startAndShutdownNeverWaitForStorage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            return temp.getRoot();
        }, error -> fail(error.toString()));
        AtomicReference<File> result = new AtomicReference<>();
        logger.start(() -> METADATA, (file, error) -> result.set(file));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(logger.isBusy());
        logger.shutdown();
        release.countDown();
        assertTrue(logger.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(logger.isLogging());
        assertTrue(read(result.get()).contains("Capture finished UTC:"));
        assertTrue(read(result.get()).contains("Export UTC: not sent"));
    }

    @Test public void startFailureIsReportedAndCanBeRetried() throws Exception {
        File notDirectory = temp.newFile("blocked");
        AtomicReference<File> directory = new AtomicReference<>(notDirectory);
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(directory::get, error -> fail(error.toString()));
        try {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<IOException> result = new AtomicReference<>();
            logger.start(() -> METADATA, (file, error) -> {
                result.set(error);
                failed.countDown();
            });
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertNotNull(result.get());
            assertFalse(logger.isLogging());
            directory.set(temp.getRoot());
            start(logger);
            stop(logger);
        } finally { shutdown(logger); }
    }

    @Test public void boundedRecordsDoNotSplitSurrogatePairsAndUseUtc() {
        assertEquals("1970-01-01T00:00:00.000Z", CompanionDiagnosticLogger.utc(0));
        String longText = String.join("", Collections.nCopies(3000, "\ud83d\ude00"));
        String bounded = CompanionDiagnosticLogger.limit(longText, 2048);
        assertTrue(bounded.length() <= 2048);
        assertTrue(bounded.endsWith(" [truncated]"));
        assertFalse(new String(bounded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).contains("?"));
    }
}
