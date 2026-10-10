/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * When the push process ends: only in the push process, only after the service was destroyed, and
 * not when the service was created again before the posted exit ran; or while it runs, once its
 * thread count passes the runaway limit, which only the push process counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FbnsProcessTest {
    private final List<Runnable> posted = new ArrayList<>();
    private final List<Runnable> repeated = new ArrayList<>();
    private int exits;
    private int threads = 55;
    private String name = "com.instagram.barcelona:fbns";

    private Supplier<String> savedName;
    private Consumer<Runnable> savedPost;
    private Runnable savedExit;
    private IntSupplier savedThreadCount;
    private Consumer<Runnable> savedRepeat;

    @Before
    public void seams() {
        savedName = FbnsProcess.processName;
        savedPost = FbnsProcess.post;
        savedExit = FbnsProcess.exit;
        savedThreadCount = FbnsProcess.threadCount;
        savedRepeat = FbnsProcess.repeat;
        FbnsProcess.processName = () -> name;
        FbnsProcess.post = posted::add;
        FbnsProcess.exit = () -> exits++;
        FbnsProcess.threadCount = () -> threads;
        FbnsProcess.repeat = repeated::add;
        FbnsProcess.watching.set(false);
    }

    @After
    public void restore() {
        FbnsProcess.serviceCreated();
        FbnsProcess.processName = savedName;
        FbnsProcess.post = savedPost;
        FbnsProcess.exit = savedExit;
        FbnsProcess.threadCount = savedThreadCount;
        FbnsProcess.repeat = savedRepeat;
        FbnsProcess.watching.set(false);
    }

    private void runPosted() {
        List<Runnable> tasks = new ArrayList<>(posted);
        posted.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    public void aStoppedServiceEndsThePushProcessAfterOnDestroy() {
        FbnsProcess.serviceCreated();
        FbnsProcess.serviceDestroyed();
        assertEquals("ended inside onDestroy rather than after it", 0, exits);
        runPosted();
        assertEquals(1, exits);
    }

    @Test
    public void aCloneOfThreadsCountsToo() {
        name = "com.instagram.barcelona.clone:fbns";
        FbnsProcess.serviceDestroyed();
        runPosted();
        assertEquals(1, exits);
    }

    @Test
    public void aServiceCreatedAgainBeforeTheExitRunsKeepsTheProcess() {
        FbnsProcess.serviceDestroyed();
        FbnsProcess.serviceCreated();
        runPosted();
        assertEquals(0, exits);

        // And the next stop still ends it.
        FbnsProcess.serviceDestroyed();
        runPosted();
        assertEquals(1, exits);
    }

    @Test
    public void noOtherProcessEverEnds() {
        for (String other : new String[] {"com.instagram.barcelona", "com.instagram.barcelona:mqtt", "fbns", null}) {
            name = other;
            FbnsProcess.serviceDestroyed();
        }
        assertTrue("posted an exit outside the push process", posted.isEmpty());
        assertEquals(0, exits);
    }

    @Test
    public void aFailedLookupLeavesTheProcessAlone() {
        FbnsProcess.processName = () -> {
            throw new IllegalStateException("no process name");
        };
        FbnsProcess.serviceCreated();
        FbnsProcess.serviceDestroyed();
        assertTrue(posted.isEmpty());
        assertTrue("started a thread count without knowing the process", repeated.isEmpty());
        assertEquals(0, exits);
    }

    @Test
    public void theFirstStartInThePushProcessStartsOneThreadCount() {
        FbnsProcess.serviceCreated();
        FbnsProcess.serviceDestroyed();
        FbnsProcess.serviceCreated();
        assertEquals(1, repeated.size());
    }

    @Test
    public void noOtherProcessCountsItsThreads() {
        for (String other : new String[] {"com.instagram.barcelona", "com.instagram.barcelona:mqtt", null}) {
            name = other;
            FbnsProcess.serviceCreated();
        }
        assertTrue(repeated.isEmpty());
    }

    @Test
    public void aRunawayEndsTheProcessWhileTheServiceRuns() {
        FbnsProcess.serviceCreated();
        Runnable count = repeated.get(0);

        threads = FbnsProcess.RUNAWAY_THREADS;
        count.run();
        assertEquals("ended at the limit itself", 0, exits);

        threads = 8_000;
        count.run();
        assertEquals(1, exits);
    }

    @Test
    public void anUnreadableCountNeverEndsTheProcess() {
        FbnsProcess.serviceCreated();
        threads = 0;
        repeated.get(0).run();
        assertEquals(0, exits);
    }
}
