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
import java.util.function.Supplier;

/**
 * When the push process ends: only in the push process, only after the service was destroyed, and
 * not when the service was created again before the posted exit ran.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FbnsProcessTest {
    private final List<Runnable> posted = new ArrayList<>();
    private int exits;
    private String name = "com.instagram.barcelona:fbns";

    private Supplier<String> savedName;
    private Consumer<Runnable> savedPost;
    private Runnable savedExit;

    @Before
    public void seams() {
        savedName = FbnsProcess.processName;
        savedPost = FbnsProcess.post;
        savedExit = FbnsProcess.exit;
        FbnsProcess.processName = () -> name;
        FbnsProcess.post = posted::add;
        FbnsProcess.exit = () -> exits++;
    }

    @After
    public void restore() {
        FbnsProcess.processName = savedName;
        FbnsProcess.post = savedPost;
        FbnsProcess.exit = savedExit;
        FbnsProcess.serviceCreated();
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
        FbnsProcess.serviceDestroyed();
        assertTrue(posted.isEmpty());
        assertEquals(0, exits);
    }
}
