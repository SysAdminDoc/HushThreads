/*
 * Forked from https://github.com/SysAdminDoc/HushGram at 539b646 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.extension.hushthreads.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * A save the person can watch and stop: from the moment it starts, a notification with how far it
 * has got and a Cancel button; gone when the save ends; and after a cancel, or after Android ended
 * the process half way, nothing left in the cache or the gallery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveProgressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long MIB = 1024L * 1024L;

    /** An ftyp box the sniff reads as an MP4 video; the made-up bytes that follow don't matter. */
    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private final CountDownLatch release = new CountDownLatch(1);
    private LocalServer server;
    private MediaUrlPolicy policy;
    private Context context;
    private Gallery gallery;
    private final CountingStream published = new CountingStream();

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        // Android 9 saves only with the storage permission, and a save that can't reach the gallery
        // never starts its fetch. Android 10 and later never ask for it.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        // A save of one file reads its policy through MediaSave, as every save does.
        MediaSave.policyForTests = policy;
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        LogBufferManager.clearLogBuffer();
        // Every save here is the first of its process, as a save after a restart would be.
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() throws Exception {
        release.countDown();
        server.close();
        MediaSave.policyForTests = null;
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        SaveLeftovers.forgetSweepForTests();
    }

    /** A save on the feature's own worker, as a tap starts one, of [path] into the gallery. */
    private Thread save(String path) {
        return save(path, new CompletableFuture<>());
    }

    /** The same, completing [ended] with the save's result as it ends. */
    private Thread save(String path, CompletableFuture<Downloader.Result> ended) {
        return saveOf(server.origin() + path, ended);
    }

    /** The same for the address [url], wherever it is. Null when no save slot was free. */
    private Thread saveOf(String url, CompletableFuture<Downloader.Result> ended) {
        MediaSave.Job job = MediaSave.fileJob(context, url, Downloader.Kind.VIDEO);
        return MediaSave.start(context, true, (writer, progress) -> {
            Downloader.Result result = job.run(writer, progress);
            ended.complete(result);
            return result;
        });
    }

    private void finish(Thread worker) throws InterruptedException {
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static NotificationManager notifications() {
        return RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
    }

    /** The save's notification, or null when none is showing. */
    private static Notification saveNotification() {
        for (Notification shown : Shadows.shadowOf(notifications()).getAllNotifications()) {
            if (SaveControl.CHANNEL.equals(shown.getChannelId())) return shown;
        }
        return null;
    }

    private static Notification waitForNotification(Predicate<Notification> wanted, String what)
            throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            Notification shown = saveNotification();
            if (shown != null && wanted.test(shown)) return shown;
            Thread.sleep(50);
        }
        throw new AssertionError("the notification never showed " + what + ": " + describe(saveNotification()));
    }

    private static String describe(Notification shown) {
        if (shown == null) return "none";
        return shown.extras.getCharSequence(Notification.EXTRA_TITLE) + " | "
                + shown.extras.getCharSequence(Notification.EXTRA_TEXT) + " | "
                + shown.extras.getInt(Notification.EXTRA_PROGRESS) + "/" + shown.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                + (shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ? " indeterminate" : "");
    }

    private static boolean moving(Notification shown) {
        return !shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                && shown.extras.getInt(Notification.EXTRA_PROGRESS) > 0;
    }

    private int workFiles() {
        String[] left = DashSave.workFolder(context).list();
        return left == null ? 0 : left.length;
    }

    private static int saveId(Notification shown) {
        Intent sent = Shadows.shadowOf(shown.actions[0].actionIntent).getSavedIntent();
        return sent.getIntExtra(SaveControl.EXTRA_ID, -1);
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void aSaveShowsItsProgressAtOnceAndCancelStopsIt() throws Exception {
        cancelFromTheNotification();
    }

    /** From Android 13 the receiver isn't exported: the button still reaches it. */
    @Test
    @Config(sdk = {34, 37})
    public void cancelReachesTheSaveOnAndroid14Too() throws Exception {
        cancelFromTheNotification();
    }

    private void cancelFromTheNotification() throws Exception {
        server.serveGenerated("/big.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread worker = save("/big.mp4");

        // Straight away, before a byte arrives: nothing queues a save.
        Notification started = saveNotification();
        assertNotNull("no notification when the save started", started);
        assertEquals("Saving a video", String.valueOf(started.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals(1, started.actions.length);
        assertEquals("Cancel", String.valueOf(started.actions[0].title));

        // Then how far it has got, against the 100 MiB the server announced.
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        String text = String.valueOf(moving.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.endsWith(" of 100 MB"));
        assertEquals(100, moving.extras.getInt(Notification.EXTRA_PROGRESS_MAX));

        // A Cancel without this process's token is somebody else's broadcast and changes nothing.
        int id = saveId(moving);
        context.sendBroadcast(new Intent(SaveControl.ACTION_CANCEL).setPackage(context.getPackageName())
                .putExtra(SaveControl.EXTRA_ID, id));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(400);
        assertTrue("a Cancel with no token stopped the save", worker.isAlive());

        // The button.
        moving.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        finish(worker);

        assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save: " + describe(saveNotification()), saveNotification());
        assertEquals("a cancelled save made a gallery row", 0, gallery.inserts.size());
        assertEquals("a cancelled save left work files", 0, workFiles());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("save " + id + " cancelled from its notification"));
        assertTrue(report, report.contains("save finished: CANCELLED"));
        assertFalse("a cancel was logged as an error:\n" + report, report.contains("ERROR | save finished: CANCELLED"));
    }

    /**
     * Each save's button stops that save. Given one request code for every button, Android updates
     * the one intent to the newest save's number, and the first notification's Cancel stops the
     * second save while the first goes on.
     */
    @Test
    public void cancelOnOneOfTwoSavesStopsThatOne() throws Exception {
        server.serveGenerated("/one.mp4", "video/mp4", MP4_HEAD, 16 * MIB, MIB, release);
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 16 * MIB, MIB, release);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), published);
        CompletableFuture<Downloader.Result> firstEnded = new CompletableFuture<>();
        CompletableFuture<Downloader.Result> secondEnded = new CompletableFuture<>();
        Thread first = save("/one.mp4", firstEnded);
        Thread second = save("/two.mp4", secondEnded);
        List<Integer> ids = new ArrayList<>();
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(up.getTag())) ids.add(up.getId());
        }
        java.util.Collections.sort(ids);
        assertEquals("two saves, two notifications: " + ids, 2, ids.size());
        Notification firstShown = Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(0));
        assertEquals("the first save's button names another save", (int) ids.get(0), saveId(firstShown));
        assertEquals((int) ids.get(1),
                saveId(Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(1))));

        firstShown.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(shown(SaveControl.TAG, ids.get(0)));
        assertTrue("the second save's notification went with the first", shown(SaveControl.TAG, ids.get(1)));

        // The server sends everything it held back, so a save that Cancel didn't stop runs to the
        // end and saves. What decides the test is how each save ended, not how soon: a join of a
        // fixed length once failed a save that did stop, only later than a busy machine allowed.
        release.countDown();
        assertEquals("Cancel on the first save's notification didn't stop it",
                Downloader.Status.CANCELLED, firstEnded.get(60, TimeUnit.SECONDS).status);
        assertEquals("Cancel on the first save's notification stopped the second",
                Downloader.Status.OK, secondEnded.get(60, TimeUnit.SECONDS).status);
        finish(first);
        finish(second);
    }

    /**
     * A Cancel pressed as the save opens its connection. Its close came before the connection
     * existed and closed nothing, on the JDK and on Android alike, so the save went on to wait for
     * an answer for as long as the read timeout allowed. The test server answers one connection at
     * a time, and here it's busy with another fetch, the way it was when two saves ran at once and
     * the second got in first.
     */
    @Test
    public void aCancelAsTheConnectionOpensStopsASaveTheServerHasNotAnswered() throws Exception {
        server.serveHeld("/busy.mp4", "video/mp4", MP4_HEAD, 2 * MIB, MIB, release);
        server.serveGenerated("/late.mp4", "video/mp4", MP4_HEAD, 2 * MIB, Long.MAX_VALUE, null);
        // No read timeout: a save that waits for the answer waits until the server is free.
        Downloader.readTimeoutMs = 0;
        File other = File.createTempFile("busy", ".part", context.getCacheDir());
        Thread busy = new Thread(() -> Downloader.fetch(server.origin() + "/busy.mp4", Downloader.Kind.VIDEO, other,
                policy, Downloader.MAX_BYTES, Downloader.SILENT));
        busy.setDaemon(true);
        try {
            busy.start();
            long until = System.currentTimeMillis() + 20_000;
            while (server.hits("/busy.mp4") == 0 && System.currentTimeMillis() < until) Thread.sleep(20);
            assertEquals("the server never took the other fetch", 1, server.hits("/busy.mp4"));

            CompletableFuture<Downloader.Result> ended = new CompletableFuture<>();
            MediaSave.Job job = MediaSave.fileJob(context, server.origin() + "/late.mp4", Downloader.Kind.VIDEO);
            Thread worker = MediaSave.start(context, true, (writer, progress) -> {
                SaveControl.Save save = (SaveControl.Save) progress;
                CountDownLatch closed = new CountDownLatch(1);
                Downloader.Result result = job.run(writer, new Downloader.Progress() {
                    @Override public void transferred(long done, long total) {
                        save.transferred(done, total);
                    }

                    // Cancel is pressed here, and its close runs before the connection opens.
                    @Override public void reading(Runnable close) {
                        save.reading(() -> {
                            close.run();
                            closed.countDown();
                        });
                        SaveControl.cancel(save.id);
                        try {
                            closed.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override public boolean cancelled() {
                        return save.cancelled();
                    }
                });
                ended.complete(result);
                return result;
            });

            assertEquals("a save cancelled as its connection opened waited for the server",
                    Downloader.Status.CANCELLED, ended.get(30, TimeUnit.SECONDS).status);
            finish(worker);
            assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, gallery.inserts.size());
        } finally {
            release.countDown();
            busy.join(30_000);
            Downloader.readTimeoutMs = 20_000;
            Downloader.delete(other);
        }
    }

    /**
     * From Android 13 the Cancel receiver is registered as not exported. Threads targets a newer
     * Android than 14, where a receiver registered with no flag throws, and every Cancel button
     * then did nothing.
     *
     * <p>Android 13 itself is checked by setting SDK_INT here rather than with an sdk 33 config:
     * that sandbox is ColdStartHooksTest's alone, and a class that shares it sets the context
     * before that test runs.
     */
    @Test
    @Config(sdk = 34)
    public void fromAndroid13TheCancelReceiverIsNotExported() {
        assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 33);
            assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    @Test @Config(sdk = 37)
    public void onAndroid17TheCancelReceiverIsNotExported() {
        assertEquals(37, android.os.Build.VERSION.SDK_INT);
        assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
    }

    /** Below Android 13 there's no flag to give, and the token keeps other apps' broadcasts out. */
    @Test
    @Config(sdk = {28, 30})
    public void belowAndroid13TheCancelReceiverIsRegisteredWithNoFlag() {
        assertEquals(java.util.Collections.singletonList(-1), receiverRegistrations());
    }

    /** The flags each receiver a save registers is given, -1 for a registration with none. */
    private List<Integer> receiverRegistrations() {
        List<Integer> flags = new ArrayList<>();
        Context recording = new android.content.ContextWrapper(context) {
            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
                flags.add(-1);
                return super.registerReceiver(receiver, filter);
            }

            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter,
                    int given) {
                flags.add(given);
                return super.registerReceiver(receiver, filter, given);
            }
        };
        SaveControl.begin(recording, true).end();
        return flags;
    }

    @Test
    @Config(sdk = {30, 37})
    public void aFinishedSaveTakesItsNotificationAwayAndLeavesNoRowPending() throws Exception {
        long size = 8 * MIB;
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, size, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        finish(save("/whole.mp4"));

        assertEquals("Saved to " + L10n.isolate("Movies/Threads"), ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save", saveNotification());
        assertEquals(size, published.count.get());
        assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertTrue("a published row is still on the pending list", pendingList().isEmpty());
        assertEquals(0, workFiles());
    }

    /**
     * A proxy on the network that asks for a password gets none: the save stops after its one
     * request and says why, with nothing in the gallery. Off that proxy, the same save goes through.
     */
    @Test
    public void aProxyThatAsksForAPasswordStopsTheSaveAndANormalSaveStillWorks() throws Exception {
        long size = 256 * 1024;
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, size, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        java.net.ProxySelector saved = java.net.ProxySelector.getDefault();
        try (LocalProxy proxy = new LocalProxy(java.net.Proxy.Type.HTTP, true)) {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override public List<java.net.Proxy> select(java.net.URI uri) {
                    return java.util.Collections.singletonList(proxy.proxy());
                }

                @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress address, IOException failure) {
                }
            });
            CompletableFuture<Downloader.Result> refused = new CompletableFuture<>();
            finish(save("/whole.mp4", refused));

            assertEquals(Downloader.Status.PROXY_AUTH, refused.get(5, TimeUnit.SECONDS).status);
            assertEquals("Not saved: the proxy wants a password. Try another network.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals(1, proxy.requests().size());
            assertFalse(proxy.requests().get(0), proxy.requests().get(0).toLowerCase(java.util.Locale.US)
                    .contains("proxy-authorization"));
            assertEquals(0, server.hits("/whole.mp4"));
            assertTrue("a refused save made a gallery row", gallery.inserts.isEmpty());
            assertNull("the notification outlived the save", saveNotification());
            assertEquals(0, workFiles());
        } finally {
            java.net.ProxySelector.setDefault(saved);
        }

        CompletableFuture<Downloader.Result> ended = new CompletableFuture<>();
        finish(save("/whole.mp4", ended));

        assertEquals(Downloader.Status.OK, ended.get(5, TimeUnit.SECONDS).status);
        assertEquals("Saved to " + L10n.isolate("Movies/Threads"), ShadowToast.getTextOfLatestToast());
        assertEquals(size, published.count.get());
        assertEquals(1, server.hits("/whole.mp4"));
        assertTrue("a published row is still on the pending list", pendingList().isEmpty());
    }

    @Test
    public void withNotificationsOffASaveRunsWithItsToasts() throws Exception {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, 256 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        Thread worker = save("/whole.mp4");
        assertNull("a notification was posted with notifications off", saveNotification());
        finish(worker);

        assertEquals("Saved to " + L10n.isolate("Movies/Threads"), ShadowToast.getTextOfLatestToast());
        assertEquals(0, Shadows.shadowOf(notifications()).getAllNotifications().size());
    }

    /**
     * A save that can't show its notification says where else it can be cancelled as it starts:
     * with Threads' notifications off, or with only the saves channel switched off. With its
     * notification showing, the start says only that it's saving.
     */
    @Test
    public void aSaveWithNoNotificationSaysWhereToCancelIt() throws Exception {
        String elsewhere = "Saving... To cancel, open Downloads in HushThreads settings.";
        assertEquals("Saving...", startMessage());

        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        assertEquals(elsewhere, startMessage());

        Shadows.shadowOf(notifications()).setNotificationsEnabled(true);
        notifications().createNotificationChannel(new NotificationChannel(SaveControl.CHANNEL, "HushThreads saves",
                NotificationManager.IMPORTANCE_NONE));
        assertEquals(elsewhere, startMessage());
    }

    /** What a save says as it starts. It runs until that's been read, then ends cancelled. */
    private String startMessage() throws InterruptedException {
        ShadowToast.reset();
        CountDownLatch read = new CountDownLatch(1);
        Thread worker = MediaSave.start(context, true, (writer, progress) -> {
            try {
                read.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled");
        });
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        String said = ShadowToast.getTextOfLatestToast();
        read.countDown();
        finish(worker);
        return said;
    }

    /**
     * What a process Android ended mid-save leaves: work files, a row still pending, and a row it
     * had published but not yet crossed off. The next process's first save removes the first two
     * and keeps the finished file; a later save in the same process doesn't sweep again.
     */
    @Test
    public void whatAStoppedSaveLeftIsRemovedByTheNextOne() throws Exception {
        File folder = DashSave.workFolder(context);
        assertTrue(new File(folder, "video1.part").createNewFile());
        assertTrue(new File(folder, "joined2.mp4").createNewFile());
        Uri pending = row(1);
        Uri finished = row(0);
        SaveLeftovers.pending(context, pending);
        SaveLeftovers.pending(context, finished);
        // Retire an allocated id so it cannot be reused by the next save. A hard-coded 42 can
        // equal the process counter after other tests, replacing this notice before the sweep.
        SaveControl.Save stopped = SaveControl.begin(context, true);
        int stoppedId = stopped.id;
        stopped.end();
        // The stopped save's notification, which Android leaves up when it ends the process, and
        // one of Threads' own under another tag, which isn't this sweep's to touch.
        notifications().notify(SaveControl.TAG, stoppedId, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a video")
                .setOngoing(true).build());
        notifications().notify("instagram", stoppedId, new Notification.Builder(context, "instagram")
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("A message").build());

        server.serveGenerated("/next.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(3), published);
        finish(save("/next.mp4"));

        assertFalse("the pending row a stopped save left is still there", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue("a finished file was taken for a leftover", gallery.rows.containsKey(ContentUris.parseId(finished)));
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
        assertFalse("the stopped save's notification is still up", shown(SaveControl.TAG, stoppedId));
        assertTrue("Threads' own notification was taken down", shown("instagram", stoppedId));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("removed what a stopped save left: 2 work file(s), 1 pending gallery "
                + "row(s), 1 notification(s)"));

        // Once per process: a file there now belongs to this process's own saves.
        assertTrue(new File(folder, "video9.part").createNewFile());
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(4), published);
        finish(save("/next.mp4"));
        assertTrue("a second save in the same process swept again", new File(folder, "video9.part").exists());
    }

    /**
     * Android ended Threads in the middle of a save, and Threads is opened again with no save
     * after it. Its start ({@link SaveLeftovers#sweepAfterStart}, which the application's start
     * calls) removes what that save left, on a worker: the start doesn't wait on the
     * gallery, a save tapped meanwhile waits for the sweep, and that save's own notification and
     * work file stay, through a second start too. A finished file, the rest of the cache and a
     * start in another of Threads' processes are left alone.
     */
    @Test
    @Config(sdk = {30, 37})
    public void startingThreadsRemovesWhatAStoppedSaveLeft() throws Exception {
        File folder = DashSave.workFolder(context);
        File partial = new File(folder, "video1.part");
        File joined = new File(folder, "joined2.mp4");
        assertTrue(partial.createNewFile());
        assertTrue(joined.createNewFile());
        File unrelated = new File(context.getCacheDir(), "image_cache.bin");
        assertTrue(unrelated.createNewFile());
        Uri pending = row(1);
        Uri finished = row(0);
        SaveLeftovers.pending(context, pending);
        SaveLeftovers.pending(context, finished);
        SaveControl.Save stopped = SaveControl.begin(context, true);
        int stoppedId = stopped.id;
        stopped.end();
        notifications().notify(SaveControl.TAG, stoppedId, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a video")
                .setOngoing(true).build());

        // A start in another of Threads' processes leaves it all: only the main one saves.
        android.content.pm.ApplicationInfo info = context.getApplicationInfo();
        String mainProcess = info.processName;
        info.processName = context.getPackageName() + ":adnw";
        try {
            SaveLeftovers.sweepAfterStart(context);
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        } finally {
            info.processName = mainProcess;
        }
        assertTrue("a start in another process swept", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue(partial.exists());

        gallery.holdDeletes = new CountDownLatch(1);
        long before = System.nanoTime();
        SaveLeftovers.sweepAfterStart(context);
        long tookMs = (System.nanoTime() - before) / 1_000_000;
        assertTrue("starting Threads left what the stopped save left",
                gallery.deleteAsked.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("Threads' start waited " + tookMs + " ms on the gallery", tookMs < 2_000);

        server.serveGenerated("/next.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread worker = save("/next.mp4");
        int runningId = -1;
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(up.getTag()) && up.getId() != stoppedId) runningId = up.getId();
        }
        assertNotEquals("the new save showed no notification", -1, runningId);
        Thread.sleep(300);
        assertEquals("a save tapped during the sweep didn't wait for it", 0, server.hits("/next.mp4"));

        gallery.holdDeletes.countDown();
        long until = System.currentTimeMillis() + 20_000;
        while (server.hits("/next.mp4") == 0 && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals("the save never went on after the sweep", 1, server.hits("/next.mp4"));

        assertFalse("the pending row a stopped save left is still there", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue("a finished file was taken for a leftover", gallery.rows.containsKey(ContentUris.parseId(finished)));
        assertFalse(partial.exists());
        assertFalse(joined.exists());
        assertTrue("a cache file of Threads' was removed", unrelated.exists());
        assertTrue(pendingList().isEmpty());
        assertFalse("the stopped save's notification is still up", shown(SaveControl.TAG, stoppedId));
        assertTrue("the running save's notification was taken for a leftover", shown(SaveControl.TAG, runningId));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("removed what a stopped save left: 2 work file(s), 1 pending gallery "
                + "row(s), 1 notification(s)"));

        // A second start in the same process sweeps nothing: what's there now is the running save's.
        until = System.currentTimeMillis() + 20_000;
        while (workFiles() == 0 && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals(1, workFiles());
        SaveLeftovers.sweepAfterStart(context);
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertEquals("a second start took the running save's work file", 1, workFiles());
        assertTrue("a second start took the running save's notification", shown(SaveControl.TAG, runningId));

        assertTrue(SaveControl.cancel(runningId));
        finish(worker);
        assertEquals(0, workFiles());
    }

    /** Whether a notification with [tag] and [id] is up. */
    private static boolean shown(String tag, int id) {
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (tag.equals(up.getTag()) && up.getId() == id) return true;
        }
        return false;
    }
    /** A row as a stopped save's writer would have left it. */
    private Uri row(int pendingFlag) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "TH_VID_20260925_010203.mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, pendingFlag);
        return context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private java.util.Set<String> pendingList() {
        return context.getSharedPreferences("hushthreads_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new java.util.HashSet<>());
    }

    /** A cancel while the finished file is copied into the gallery removes the row it opened. */
    @Test
    public void aCancelDuringTheCopyIntoTheGalleryLeavesNoRow() throws IOException {
        File file = File.createTempFile("whole", ".mp4", DashSave.workFolder(context));
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(new byte[512 * 1024]);
        }
        AtomicInteger asked = new AtomicInteger();
        Downloader.Progress cancelsOnceCopying = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            // The first ask is before the gallery opens; the second is after the first block.
            @Override public boolean cancelled() {
                return asked.incrementAndGet() > 1;
            }
        };
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.publish(file, "video/mp4", sink, cancelsOnceCopying);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertTrue("the gallery was never opened, so nothing was tested", sink.opened);
        assertTrue("the opened row was left behind", sink.abandoned);
        assertFalse(sink.committed);
        assertTrue(file.delete());
    }

    /**
     * A DASH save that's cancelled stays cancelled. It used to be a failed DASH save like any other,
     * and the single file behind it started the save over.
     */
    @Test
    public void aCancelledDashSaveDoesNotFallBackToTheSingleFile() throws Exception {
        MediaSave.policyForTests = policy;
        server.serveGenerated("/track.mp4", "video/mp4", MP4_HEAD, 50 * MIB, 2 * MIB, release);
        server.serveGenerated("/single.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        DashManifest.Track track = new DashManifest.Track("video/mp4", "avc1.64001f", 1920, 1080, 4_000_000,
                server.origin() + "/track.mp4");

        Thread worker = MediaSave.start(context, true,
                MediaSave.dashJob(context, track, null, server.origin() + "/single.mp4"));
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        assertTrue(SaveControl.cancel(saveId(moving)));
        finish(worker);

        assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
        assertEquals("the single file was fetched after the cancel", 0, server.hits("/single.mp4"));
        // The fallback would reuse the cancelled progress and stop before a request, so the log
        // is what shows whether it was tried at all.
        String report = LogBufferManager.buildExportText();
        assertFalse("a cancelled DASH save went on to the single file:\n" + report,
                report.contains("the DASH save ended with"));
        assertEquals(0, gallery.inserts.size());
        assertEquals(0, workFiles());
    }

    /**
     * A save whose answer trickles in too slowly ends at the fetch's time and says so in words of
     * its own, not as a network failure a retry would mend at once. Nothing is left behind.
     */
    @Test
    public void aSaveThatRunsOutOfTimeSaysSoAndLeavesNothing() throws Exception {
        server.serveGenerated("/slow.mp4", "video/mp4", MP4_HEAD, 100 * MIB, MIB, release);
        Downloader.budgetMs = 1_500;
        try {
            CompletableFuture<Downloader.Result> ended = new CompletableFuture<>();
            Thread worker = save("/slow.mp4", ended);
            assertEquals(Downloader.Status.TIMED_OUT, ended.get(20, TimeUnit.SECONDS).status);
            finish(worker);
        } finally {
            Downloader.budgetMs = Downloader.BUDGET_MS;
        }

        assertEquals("Not saved: the download was too slow. Try a faster connection.",
                ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save", saveNotification());
        assertEquals(0, gallery.inserts.size());
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
        assertEquals(0, MediaSave.savesInFlight());
    }

    /**
     * A DASH track that runs out of time is a failed DASH save like any other: the single file
     * behind it is fetched with time of its own, and saves.
     */
    @Test
    public void aDashTrackThatRunsOutOfTimeFallsBackToTheSingleFile() throws Exception {
        server.serveGenerated("/track.mp4", "video/mp4", MP4_HEAD, 50 * MIB, MIB, release);
        server.serveGenerated("/single.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        DashManifest.Track track = new DashManifest.Track("video/mp4", "avc1.64001f", 1920, 1080, 4_000_000,
                server.origin() + "/track.mp4");
        MediaSave.Job job = MediaSave.dashJob(context, track, null, server.origin() + "/single.mp4");
        Downloader.budgetMs = 1_500;
        try {
            CompletableFuture<Downloader.Result> ended = new CompletableFuture<>();
            Thread worker = MediaSave.start(context, true, (writer, progress) -> {
                Downloader.Result result = job.run(writer, progress);
                ended.complete(result);
                return result;
            });
            assertEquals(Downloader.Status.OK, ended.get(30, TimeUnit.SECONDS).status);
            finish(worker);
        } finally {
            Downloader.budgetMs = Downloader.BUDGET_MS;
        }

        assertEquals(1, server.hits("/single.mp4"));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the DASH save ended with TIMED_OUT"));
        assertEquals(1, gallery.inserts.size());
        assertEquals(0, workFiles());
        String told = ShadowToast.getTextOfLatestToast();
        assertTrue(told, told.startsWith("Saved to "));
    }

    /**
     * Three saves whose host lookups never answer fill every save slot before any connection
     * exists to close. Cancel still ends all three within five seconds and frees their slots,
     * with nothing left behind. While the resolver keeps every lookup thread, the next save waits
     * for no longer than the lookup's limit and fails, without a thread of its own. Once the
     * resolver answers, a save goes through.
     */
    @Test
    public void threeSavesHeldInTheirLookupCanAllBeCancelled() throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger asked = new AtomicInteger();
        int port = server.port();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> {
            asked.incrementAndGet();
            try {
                answer.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // A private address, so a lookup that answers late is refused and nothing leaves the machine.
            return new InetAddress[] { InetAddress.getByName("10.9.8.7") };
        }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        List<CompletableFuture<Downloader.Result>> ended = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        try {
            for (int i = 1; i <= 3; i++) {
                CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
                ended.add(result);
                workers.add(saveOf("https://scontent.cdninstagram.com/v/t66/held" + i + ".mp4", result));
            }
            long until = System.currentTimeMillis() + 10_000;
            while (asked.get() < 3 && System.currentTimeMillis() < until) Thread.sleep(10);
            assertEquals("three saves, three lookups", 3, asked.get());
            assertEquals(3, MediaSave.savesInFlight());
            assertNull("a fourth save was let in beside three",
                    saveOf(server.origin() + "/never.mp4", new CompletableFuture<>()));

            long before = System.nanoTime();
            for (SaveControl.Running save : SaveControl.running()) assertTrue(SaveControl.cancel(save.id));
            for (CompletableFuture<Downloader.Result> result : ended) {
                assertEquals(Downloader.Status.CANCELLED, result.get(5, TimeUnit.SECONDS).status);
            }
            while (MediaSave.savesInFlight() > 0 && System.nanoTime() - before < 5_000_000_000L) Thread.sleep(10);
            long tookMs = (System.nanoTime() - before) / 1_000_000;
            assertEquals("the cancelled saves kept their slots", 0, MediaSave.savesInFlight());
            assertTrue("the cancels took " + tookMs + " ms", tookMs < 5_000);
            for (Thread worker : workers) finish(worker);
            assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, gallery.inserts.size());
            assertEquals(0, workFiles());
            assertTrue(pendingList().isEmpty());
            assertEquals("the stuck lookups ended with their saves", 3, MediaUrlPolicy.lookupsRunning());

            MediaUrlPolicy.lookupTimeoutMs = 700;
            CompletableFuture<Downloader.Result> waited = new CompletableFuture<>();
            before = System.nanoTime();
            Thread fourth = saveOf("https://scontent.cdninstagram.com/v/t66/fourth.mp4", waited);
            assertNotNull("a slot freed by a cancel wasn't free", fourth);
            assertEquals(Downloader.Status.NETWORK_ERROR, waited.get(10, TimeUnit.SECONDS).status);
            tookMs = (System.nanoTime() - before) / 1_000_000;
            finish(fourth);
            assertTrue("the next save waited " + tookMs + " ms for a lookup thread", tookMs < 3_000);
            assertEquals("the waiting save reached the stuck resolver", 3, asked.get());
            assertTrue("lookups took " + MediaUrlPolicy.lookupThreads() + " threads", MediaUrlPolicy.lookupThreads() <= 3);
            assertEquals("Download failed. Try again in a moment.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, workFiles());
        } finally {
            MediaUrlPolicy.lookupTimeoutMs = MediaUrlPolicy.LOOKUP_TIMEOUT_MS;
            answer.countDown();
        }

        long until = System.currentTimeMillis() + 5_000;
        while (MediaUrlPolicy.lookupsRunning() > 0 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertEquals("the lookups never ended once answered", 0, MediaUrlPolicy.lookupsRunning());
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        server.serveGenerated("/after.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        CompletableFuture<Downloader.Result> after = new CompletableFuture<>();
        finish(save("/after.mp4", after));
        assertEquals(Downloader.Status.OK, after.get(10, TimeUnit.SECONDS).status);
        assertEquals(1, gallery.inserts.size());
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
    }

    @Test public void threeSavesHeldInProxySelectionCanAllBeCancelled() throws Exception {
        heldProxyCallbacks(false, true);
    }

    @Test public void threeSavesHeldInProxySelectionAllReachTheirDeadline() throws Exception {
        heldProxyCallbacks(false, false);
    }

    @Test public void threeSavesHeldInProxyFailureReportsCanAllBeCancelled() throws Exception {
        heldProxyCallbacks(true, true);
    }

    @Test public void threeSavesHeldInProxyFailureReportsAllReachTheirDeadline() throws Exception {
        heldProxyCallbacks(true, false);
    }

    /** Policy discovery answers first; only the connection's callback is held, ignoring interrupts. */
    private void heldProxyCallbacks(boolean failureReport, boolean cancel) throws Exception {
        ProxySelector before = ProxySelector.getDefault();
        CountDownLatch held = new CountDownLatch(3);
        CountDownLatch answer = new CountDownLatch(1);
        Map<URI, AtomicInteger> selections = new ConcurrentHashMap<>();
        InetSocketAddress gone;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gone = new InetSocketAddress(InetAddress.getLoopbackAddress(), closed.getLocalPort());
        }
        ProxySelector waiting = new ProxySelector() {
            private void hold() {
                held.countDown();
                while (answer.getCount() != 0) {
                    try { answer.await(); }
                    catch (InterruptedException ignored) { /* A system callback needn't cooperate. */ }
                }
            }

            @Override public List<Proxy> select(URI uri) {
                int count = selections.computeIfAbsent(uri, key -> new AtomicInteger()).incrementAndGet();
                if (!failureReport && uri.getPath().startsWith("/held") && count > 1) hold();
                return failureReport ? Arrays.asList(new Proxy(Proxy.Type.HTTP, gone), Proxy.NO_PROXY)
                        : Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                if (failureReport && uri.getPath().startsWith("/held")) hold();
            }
        };
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[]{InetAddress.getLoopbackAddress()},
                url -> !MediaUrlPolicy.proxied(url)) {
            @Override Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == server.port()) {
                    return route.direct(url) ? lookedUp(url, false) : null;
                }
                return super.refusal(url);
            }
        };
        List<Thread> workers = new ArrayList<>();
        try {
            ProxySelector.setDefault(waiting);
            Downloader.budgetMs = 3_000;
            List<CompletableFuture<Downloader.Result>> ended = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
                ended.add(result);
                workers.add(save("/held" + i + ".mp4", result));
            }
            assertTrue("the connection callbacks never all started", held.await(10, TimeUnit.SECONDS));
            assertEquals(3, selections.size());
            for (AtomicInteger count : selections.values()) assertEquals("policy must answer before connect", 2, count.get());
            assertEquals(3, MediaSave.savesInFlight());
            assertEquals(3, SaveControl.running().size());
            assertEquals(3, notifications().getActiveNotifications().length);
            assertNull(save("/fourth.mp4", new CompletableFuture<>()));

            long stopped = System.nanoTime();
            if (cancel) for (SaveControl.Running save : SaveControl.running()) assertTrue(SaveControl.cancel(save.id));
            for (CompletableFuture<Downloader.Result> result : ended) {
                assertEquals(cancel ? Downloader.Status.CANCELLED : Downloader.Status.TIMED_OUT,
                        result.get(5, TimeUnit.SECONDS).status);
            }
            for (Thread worker : workers) finish(worker);
            assertTrue("callback wait outlived cancel/deadline", System.nanoTime() - stopped < 5_000_000_000L);
            assertEquals("callbacks kept the save admissions", 0, MediaSave.savesInFlight());
            assertTrue(SaveControl.running().isEmpty());
            assertNull(saveNotification());
            assertEquals(0, workFiles());
            assertEquals(0, gallery.inserts.size());
            assertTrue(pendingList().isEmpty());
            assertTrue(context.getSharedPreferences("hushthreads_saves", Context.MODE_PRIVATE)
                    .getStringSet("active_jobs", Collections.emptySet()).isEmpty());
            assertEquals("callbacks must still be blocked", 3, MediaUrlPolicy.lookupsRunning());

            MediaUrlPolicy.lookupTimeoutMs = 400;
            for (int i = 0; i < 3; i++) {
                CompletableFuture<Downloader.Result> result = new CompletableFuture<>();
                long start = System.nanoTime();
                Thread worker = save("/queued" + i + ".mp4", result);
                workers.add(worker);
                assertNotNull("a cancelled or expired save kept its slot", worker);
                assertEquals(Downloader.Status.NETWORK_ERROR, result.get(5, TimeUnit.SECONDS).status);
                finish(worker);
                assertTrue("queued work exceeded its budget", System.nanoTime() - start < 3_000_000_000L);
                assertTrue("callbacks spawned extra workers", MediaUrlPolicy.lookupThreads() <= 3);
            }
            assertEquals("queued callbacks reached the occupied workers", 3, selections.size());
        } finally {
            answer.countDown();
            Downloader.budgetMs = Downloader.BUDGET_MS;
            MediaUrlPolicy.lookupTimeoutMs = MediaUrlPolicy.LOOKUP_TIMEOUT_MS;
            for (Thread worker : workers) if (worker != null) worker.join(5_000);
            ProxySelector.setDefault(before);
        }
        long until = System.nanoTime() + 5_000_000_000L;
        while (MediaUrlPolicy.lookupsRunning() != 0 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals("released callbacks kept their workers", 0, MediaUrlPolicy.lookupsRunning());
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        server.serveGenerated("/after-proxy.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        CompletableFuture<Downloader.Result> after = new CompletableFuture<>();
        finish(save("/after-proxy.mp4", after));
        assertEquals(Downloader.Status.OK, after.get(5, TimeUnit.SECONDS).status);
        assertEquals(1, gallery.inserts.size());
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
    }

    /**
     * The flag alone stops a fetch between two reads, with no connection closed under it: the
     * close is what ends a read that's waiting, and each is tested apart from the other.
     */
    @Test
    public void aCancelSeenBetweenReadsStopsTheFetchAndDeletesItsFile() throws IOException {
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 2 * MIB, Long.MAX_VALUE, null);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closers = new AtomicInteger();
        Downloader.Progress cancelsAfterThreeReads = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
                reads.incrementAndGet();
            }

            @Override public void reading(Runnable close) {
                if (close != null) closers.incrementAndGet();
            }

            @Override public boolean cancelled() {
                return reads.get() >= 3;
            }
        };
        File into = File.createTempFile("video", ".part", DashSave.workFolder(context));

        Downloader.Result result = Downloader.fetch(server.origin() + "/two.mp4", Downloader.Kind.VIDEO, into, policy,
                Downloader.MAX_BYTES, cancelsAfterThreeReads);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertFalse("a cancelled fetch left its file", into.exists());
        assertTrue("the fetch read on long after the cancel: " + reads.get(), reads.get() < 10);
        // What a cancel needs to end a read waiting on the network, on Android.
        assertEquals("the fetch never handed over a way to close its connection", 1, closers.get());
    }

    /**
     * A read waiting on a server that went quiet sees no flag. On Android the cancel closes the
     * connection under it and it ends at once; the JDK these tests run on waits out the read
     * timeout instead, so it's short here. Either way the notification goes the moment Cancel is
     * pressed, and the save ends cancelled, not as a network failure.
     */
    @Test
    public void aCancelDuringAStalledReadTakesTheNotificationAwayAtOnce() throws Exception {
        Downloader.readTimeoutMs = 2_000;
        try {
            server.serveHeld("/quiet.mp4", "video/mp4", MP4_HEAD, 10 * MIB, MIB, release);
            Thread worker = save("/quiet.mp4");
            Notification started = saveNotification();
            assertNotNull(started);
            Thread.sleep(700);
            assertTrue("the save ended before the server went quiet", worker.isAlive());

            assertTrue(SaveControl.cancel(saveId(started)));
            assertNull("the notification stayed after Cancel", saveNotification());
            finish(worker);

            assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, workFiles());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("save finished: CANCELLED"));
        } finally {
            Downloader.readTimeoutMs = 20_000;
        }
    }

    /** Cancel closes the connection being read, and one opened after the cancel as soon as it's handed over. */
    @Test
    public void cancelClosesTheConnectionTheSaveIsReading() throws Exception {
        SaveControl.Save save = SaveControl.begin(context, true);
        Probe before = new Probe();
        save.reading(before);

        assertTrue(SaveControl.cancel(save.id));
        assertTrue("cancel left the connection open", before.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Probe after = new Probe();
        save.reading(after);
        assertTrue("a connection handed over after the cancel stayed open",
                after.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        save.end();
    }

    /** A connection's closer that only records being run. */
    private static final class Probe implements Runnable {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void run() {
            closed.countDown();
        }
    }

    /** The writer lists its row as soon as it's inserted, and crosses it off once it's settled. */
    @Test
    public void theWriterListsItsRowWhileItIsPending() throws IOException {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), published);

        MediaStoreWriter kept = new MediaStoreWriter(context, true);
        kept.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(1).toString()), pendingList());
        kept.commit();
        assertTrue(pendingList().isEmpty());

        MediaStoreWriter dropped = new MediaStoreWriter(context, true);
        dropped.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(2).toString()), pendingList());
        dropped.abandon();
        assertTrue(pendingList().isEmpty());
        assertFalse(gallery.rows.containsKey(2L));
    }

    @Test
    public void aPendingRowTheGalleryRefusesToDeleteStaysOnTheRetryList() throws IOException {
        Uri row = gallery.videoUri(1);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(row, published);
        MediaStoreWriter writer = new MediaStoreWriter(context, true);
        writer.open("video/mp4");
        gallery.refuseDeletion = true;

        writer.abandon();

        assertTrue("the failed deletion took the row off the retry list", pendingList().contains(row.toString()));
        assertTrue("the test gallery removed the row", gallery.rows.containsKey(1L));
        gallery.refuseDeletion = false;
        SaveLeftovers.sweepOnce(context);
        assertFalse("a later sweep did not remove the row", gallery.rows.containsKey(1L));
        assertTrue(pendingList().isEmpty());
    }

    @Test
    @Config(sdk = {30, 37})
    public void aPendingRowSweepRetriesAfterTheGalleryThrows() {
        Uri row = row(1);
        SaveLeftovers.pending(context, row);
        gallery.throwOnDelete = true;

        SaveLeftovers.sweepOnce(context);

        assertTrue("the failed sweep forgot the row", pendingList().contains(row.toString()));
        assertTrue(gallery.rows.containsKey(ContentUris.parseId(row)));
        gallery.throwOnDelete = false;
        SaveLeftovers.forgetSweepForTests();
        SaveLeftovers.sweepOnce(context);
        assertFalse("the next process did not remove the row", gallery.rows.containsKey(ContentUris.parseId(row)));
        assertTrue(pendingList().isEmpty());
    }

    @Test
    public void progressTextSaysHowMuchOfHowMuch() {
        assertNull(SaveControl.progressText(0, 100 * MIB));
        assertEquals("4.2 MB of 100 MB", SaveControl.progressText((long) (4.2 * MIB), 100 * MIB));
        assertEquals("12 MB so far", SaveControl.progressText(12 * MIB, -1));
    }

    /** Counts what the gallery was given, so a large save needs no array of its size. */
    static final class CountingStream extends OutputStream {
        final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();

        @Override public void write(int b) {
            count.incrementAndGet();
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            count.addAndGet(length);
        }
    }

    private static final class RecordingSink implements Downloader.Sink {
        boolean opened;
        boolean committed;
        boolean abandoned;

        @Override public OutputStream open(String mime) {
            opened = true;
            return new CountingStream();
        }

        @Override public void commit() {
            committed = true;
        }

        @Override public void abandon() {
            abandoned = true;
        }
    }

    /** MediaStore's video table, as a save and the leftover sweep use it. */
    public static final class Gallery extends ContentProvider {
        /** Read on the test's thread while a sweep deletes from it on a worker. */
        final Map<Long, ContentValues> rows = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Uri> inserts = new ArrayList<>();
        boolean refuseDeletion;
        boolean throwOnDelete;
        boolean securityOnDelete;
        int queryFailure;
        Uri lastQuery;
        /** While set, a deletion waits for it: a gallery that's slow while Threads starts. */
        volatile CountDownLatch holdDeletes;
        final CountDownLatch deleteAsked = new CountDownLatch(1);
        private long nextId = 1;

        Uri videoUri(long id) {
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            Uri item = ContentUris.withAppendedId(uri, id);
            inserts.add(item);
            return item;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            lastQuery = uri;
            if (queryFailure == 1) throw new SecurityException("query unavailable");
            if (queryFailure == 2) return null;
            String[] columns = projection == null ? new String[0] : projection;
            if (queryFailure == 3) columns = new String[]{"_id"};
            MatrixCursor cursor = new MatrixCursor(columns);
            String last = uri.getLastPathSegment();
            if (last != null && last.matches("[0-9]+")) {
                ContentValues row = rows.get(ContentUris.parseId(uri));
                if (row != null) {
                    Object[] values = new Object[columns.length];
                    for (int i = 0; i < columns.length; i++) values[i] = queryFailure == 4 ? null : row.get(columns[i]);
                    cursor.addRow(values);
                }
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        /** Honours the one selection the sweep uses, the way MediaStore does for a row named by id. */
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            deleteAsked.countDown();
            CountDownLatch hold = holdDeletes;
            if (hold != null) {
                try {
                    hold.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwOnDelete) throw new IllegalStateException("gallery unavailable");
            if (securityOnDelete) throw new SecurityException("has no access to this URI");
            if (refuseDeletion) return 0;
            long id = ContentUris.parseId(uri);
            ContentValues row = rows.get(id);
            if (row == null) return 0;
            if ((MediaStore.MediaColumns.IS_PENDING + "=1").equals(selection)
                    && !Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                return 0;
            }
            rows.remove(id);
            return 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }
}
