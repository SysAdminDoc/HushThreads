/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.misc;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import app.morphe.extension.shared.Logger;

/**
 * Ends Threads' push process once Android has destroyed the in-app push service it exists for, or
 * once its threads run away.
 *
 * <p>Android stops that service about a minute after Threads goes to the background, and the
 * process stays cached. When Threads comes back the service starts again in the same process, and
 * every start builds a new broadcast thread, a new scheduler and a new gateway client with its own
 * event loop and DNS threads, while the stopped service's are never released. Each return after a
 * minute away left one more set behind (#6). The manifest gives the process to this one service, which
 * the patch checks, so ending it once the service is gone is what Android's low-memory killer does
 * anyway, and the next start gets a clean process.
 *
 * <p>Both calls run on the main thread, where Android delivers a service's lifecycle. The exit is
 * posted, so it runs after onDestroy returns and Android has heard the service is gone, and it's
 * called off when the service was created again in between.
 *
 * <p>The report in #6 also showed about 8,000 threads in this process after 46 seconds, almost all
 * of them gateway event loop and DNS threads, and a phone whose system server the load restarted.
 * The push process needs about 60. From the first start on, a daemon thread counts them every few
 * seconds and ends the process past {@link #RUNAWAY_THREADS}, whatever builds them. Android then
 * restarts the service later, with its own backoff for a process that died.
 */
public final class FbnsProcess {

    private FbnsProcess() {}

    /** The push process's name ends with this, under Threads' package or a clone's. */
    private static final String SUFFIX = ":fbns";

    /** Ten times what the process runs with; a leak of a few sets a day stays far below it. */
    static final int RUNAWAY_THREADS = 600;

    private static final long CHECK_MILLIS = 2_000;

    /** Whether the service is created and not destroyed since. */
    private static volatile boolean alive;

    /** Whether the thread count has started in this process. */
    static final AtomicBoolean watching = new AtomicBoolean();

    // Seams for the tests.
    static volatile Supplier<String> processName = Application::getProcessName;
    static volatile Consumer<Runnable> post = task -> new Handler(Looper.getMainLooper()).post(task);
    static volatile Runnable exit = () -> Process.killProcess(Process.myPid());
    static volatile IntSupplier threadCount = FbnsProcess::liveThreads;
    static volatile Consumer<Runnable> repeat = FbnsProcess::everyFewSeconds;

    /**
     * The service's onCreate: an exit still waiting from the last stop is called off, and the
     * first start in the push process starts the thread count.
     */
    public static void serviceCreated() {
        alive = true;
        try {
            if (inPushProcess() && watching.compareAndSet(false, true)) {
                repeat.accept(FbnsProcess::exitIfRunaway);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not start watching the push process's threads", failure);
        }
    }

    /** The service's onDestroy: in the push process, its exit is posted. */
    public static void serviceDestroyed() {
        alive = false;
        try {
            if (!inPushProcess()) return;
            post.accept(FbnsProcess::exitIfStopped);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not schedule the push process's exit", failure);
        }
    }

    private static boolean inPushProcess() {
        String name = processName.get();
        return name != null && name.endsWith(SUFFIX);
    }

    static void exitIfStopped() {
        if (alive) return;
        Logger.printInfo(() -> "Push service stopped, ending its process");
        exit.run();
    }

    static void exitIfRunaway() {
        int count = threadCount.getAsInt();
        if (count <= RUNAWAY_THREADS) return;
        Logger.printException(() -> "Push process has " + count + " threads (" + busiestNames() + "), ending it");
        exit.run();
    }

    /** The threads of this process, from the kernel's list, or 0 when it can't be read. */
    private static int liveThreads() {
        String[] tasks = new File("/proc/self/task").list();
        return tasks == null ? 0 : tasks.length;
    }

    /**
     * The most common thread names, for the log, so a runaway says what it was made of. Read from
     * the kernel, since the gateway's threads are native and Java's own list doesn't have them.
     */
    private static String busiestNames() {
        try {
            String[] tasks = new File("/proc/self/task").list();
            if (tasks == null) return "names unreadable";
            Map<String, Integer> names = new HashMap<>();
            for (int i = 0; i < tasks.length && i < 3_000; i++) {
                String name;
                try (BufferedReader comm = new BufferedReader(new FileReader("/proc/self/task/" + tasks[i] + "/comm"))) {
                    name = comm.readLine();
                } catch (IOException gone) {
                    continue;
                }
                // Pool threads differ only by their numbers.
                if (name != null) names.merge(name.replaceAll("\\d+", "#"), 1, Integer::sum);
            }
            return names.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(4)
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .collect(Collectors.joining(", "));
        } catch (Throwable failure) {
            return "names unreadable";
        }
    }

    private static void everyFewSeconds(Runnable task) {
        Thread watcher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CHECK_MILLIS);
                    task.run();
                } catch (InterruptedException stop) {
                    return;
                } catch (Throwable failure) {
                    Logger.printException(() -> "Push process thread count failed", failure);
                    return;
                }
            }
        }, "HushThreadsFbnsWatch");
        watcher.setDaemon(true);
        watcher.start();
    }
}
