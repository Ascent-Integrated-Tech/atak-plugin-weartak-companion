package com.weartak.atak.weartak_companion.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CompanionDiagnosticLoggerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String METADATA = "Plugin version: test-plugin\nATAK version: test-atak"
            + "\nAndroid API: 35";

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

    @Test public void capturePersistsOnlyPrivacySafeEventCategories() throws Exception {
        File directory = new File(temp.getRoot(), "companion-logs");
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(() -> directory, error -> fail(error.toString()));
        try {
            logger.record("I", "WTK/BleClient", "SCAN: before-start");
            assertFalse(directory.exists());
            File file = start(logger);
            logger.record("I", "WTK/BleClient", "SCAN: started");
            logger.record("I", "WTK/BleClient", "CONNECT: requested");
            logger.record("D", "WTK/BleCotBridge",
                    "dispatchCot(PLI): dispatched INTERNAL lat=37.4219983 lon=-122.084");
            logger.record("W", "WTK/BleClient", "DISCONNECT");
            logger.record("E", "WTK/Plugin", "failure example: bounded stack", true);
            logger.record("I", "AndroidSystem", "must-not-capture");
            assertEquals(file, stop(logger));
            logger.record("I", "WTK/BleClient", "SCAN: after-stop");
            String text = read(file);
            assertTrue(text.contains(METADATA));
            assertTrue(text.matches("(?s).*Capture started UTC: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z\\n.*"));
            assertTrue(text.contains("event=transport_scan"));
            assertTrue(text.contains("event=transport_connect"));
            assertTrue(text.contains("event=report"));
            assertTrue(text.contains("event=transport_disconnect"));
            assertTrue(text.contains("event=failure"));
            assertTrue(text.indexOf("event=transport_scan") < text.indexOf("event=transport_connect"));
            assertFalse(text.contains("before-start"));
            assertFalse(text.contains("after-stop"));
            assertFalse(text.contains("must-not-capture"));
            assertFalse(text.contains("SCAN: started"));
            assertFalse(text.contains("CONNECT: requested"));
            assertFalse(text.contains("37.4219983"));
            assertFalse(text.contains("-122.084"));
            assertFalse(text.contains("failure example"));
            assertFalse(text.contains("bounded stack"));
            assertTrue(text.contains("Dropped records (queue full): 0"));
        } finally {
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
        assertTrue(retained.toString().contains("event=transport"));
        assertFalse(retained.toString().contains("session-0"));
        assertTrue(unrelated.exists());
    }

    @Test public void rotatingLogsStayWithinFileAndRetentionBounds() throws Exception {
        AtomicReference<IOException> notice = new AtomicReference<>();
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot, notice::set, 4096, 256);
        try {
            start(logger);
            for (int i = 0; i < 1000; i++)
                logger.record("D", "WTK/Test" + i, "SCAN lat=37.4219983 lon=-122.084");
            stop(logger);
            File[] files = logger.logs();
            assertTrue(files.length <= CompanionDiagnosticLogger.FILE_COUNT);
            assertTrue(files.length > 1);
            for (File file : files) {
                assertTrue(file.length() <= 4096);
                String text = read(file);
                assertFalse(text.contains("37.4219983"));
                assertFalse(text.contains("-122.084"));
                assertTrue(text.contains("event=transport_scan"));
            }
            assertNull(notice.get());
        } finally { shutdown(logger); }
    }

    @Test public void eventQueueIsBoundedAndOverflowIsReported() throws Exception {
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot,
                error -> fail(error.toString()), 4096, 2);
        try {
            start(logger);
            for (int i = 0; i < 1000; i++)
                logger.record("I", "WTK/Test" + i, "SCAN started");
            File file = stop(logger);
            assertTrue(read(file).matches("(?s).*Dropped records \\(queue full\\): [1-9][0-9]*\\n.*"));
        } finally {
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
        assertFalse(read(result.get()).contains("not sent"));
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

    @Test public void repeatedCategoriesAreRateLimitedAndUseUtc() throws Exception {
        assertEquals("1970-01-01T00:00:00.000Z", CompanionDiagnosticLogger.utc(0));
        CompanionDiagnosticLogger logger = new CompanionDiagnosticLogger(temp::getRoot,
                error -> fail(error.toString()));
        try {
            start(logger);
            for (int i = 0; i < 20; i++)
                logger.record("I", "WTK/BleClient", "SCAN request address=secret");
            File file = stop(logger);
            String text = read(file);
            assertEquals(1, occurrences(text, "event=transport_scan"));
            assertTrue(text.contains("Rate-limited duplicate events: 19"));
            assertFalse(text.contains("address=secret"));
        } finally {
            shutdown(logger);
        }
    }

    private static int occurrences(String text, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
