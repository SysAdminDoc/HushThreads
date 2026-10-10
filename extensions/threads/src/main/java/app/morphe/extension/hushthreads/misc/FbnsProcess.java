/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.misc;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.function.Consumer;
import java.util.function.Supplier;

import app.morphe.extension.shared.Logger;

/**
 * Ends Threads' push process once Android has destroyed the in-app push service it exists for.
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
 */
public final class FbnsProcess {

    private FbnsProcess() {}

    /** The push process's name ends with this, under Threads' package or a clone's. */
    private static final String SUFFIX = ":fbns";

    /** Whether the service is created and not destroyed since. */
    private static volatile boolean alive;

    // Seams for the tests.
    static volatile Supplier<String> processName = Application::getProcessName;
    static volatile Consumer<Runnable> post = task -> new Handler(Looper.getMainLooper()).post(task);
    static volatile Runnable exit = () -> Process.killProcess(Process.myPid());

    /** The service's onCreate: an exit still waiting from the last stop is called off. */
    public static void serviceCreated() {
        alive = true;
    }

    /** The service's onDestroy: in the push process, its exit is posted. */
    public static void serviceDestroyed() {
        alive = false;
        try {
            String name = processName.get();
            if (name == null || !name.endsWith(SUFFIX)) return;
            post.accept(FbnsProcess::exitIfStopped);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not schedule the push process's exit", failure);
        }
    }

    static void exitIfStopped() {
        if (alive) return;
        Logger.printInfo(() -> "Push service stopped, ending its process");
        exit.run();
    }
}
