/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How a save's connection reaches the server and reads its answer: every vetted address in turn,
 * no lookup on the save's thread, the framing an answer can have, the header forms RFC 9112 lets
 * a server send, and an address whose characters {@code URL.toURI()} refuses.
 */
public class MediaConnectionTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    /** A host of the test's own. Nothing resolves it but the test's resolver. */
    private static final String HOST = "cdn.test";

    private static byte[] jpeg(int size) {
        byte[] body = new byte[size];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xD8;
        body[2] = (byte) 0xFF;
        for (int i = 3; i < size; i++) body[i] = (byte) (i * 7);
        return body;
    }

    /** A policy that lets the loopback address through and refuses every name. */
    private static MediaUrlPolicy loopback() {
        return new MediaUrlPolicy(host -> { throw new UnknownHostException(host); }) {
            @Override
            Refusal refusal(URL url) {
                return url.getHost().equals("127.0.0.1") ? null : super.refusal(url);
            }
        };
    }

    private static MediaConnection open(URL url) throws IOException {
        MediaConnection connection = new MediaConnection(url, null, null, 5_000, 5_000,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        connection.connect();
        return connection;
    }

    private static String body(MediaConnection connection) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = connection.body();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** The whole answer to [response], read as a save reads one, or the failure. */
    private static String read(RawServer server, String path) throws IOException {
        MediaConnection connection = open(server.url(path));
        try {
            return body(connection);
        } finally {
            connection.close();
        }
    }

    // ------------------------------------------------------------------ addresses

    /**
     * Each address the policy vetted is tried in turn: an IPv6 address that can't be reached
     * leaves the IPv4 one after it to carry the save, and the name is looked up once, by the check.
     */
    @Test
    public void everyVettedAddressIsTriedInTurn() throws Exception {
        assertTrue("the test server needs IPv4 loopback", InetAddress.getLoopbackAddress() instanceof Inet4Address);
        AtomicInteger lookups = new AtomicInteger();
        MediaUrlPolicy policy = new MediaUrlPolicy(host -> {
            lookups.incrementAndGet();
            return new InetAddress[] { InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1") };
        }) {
            @Override
            Refusal refusal(URL url) {
                // The shape rules want Meta's HTTPS on 443. Only the lookup is the policy's here.
                return url.getHost().equals(HOST) ? lookedUp(url, false) : super.refusal(url);
            }
        };
        try (LocalServer server = new LocalServer()) {
            byte[] body = jpeg(20_000);
            server.serve("/f.jpg", "image/jpeg", body);
            File into = temp.newFile();

            Downloader.Result result = Downloader.fetch("http://" + HOST + ":" + server.port() + "/f.jpg",
                    Downloader.Kind.IMAGE, into, policy, Downloader.MAX_BYTES, Downloader.SILENT);

            assertEquals(result.toString(), Downloader.Status.OK, result.status);
            assertArrayEquals(body, Files.readAllBytes(into.toPath()));
            assertEquals("the name was looked up again", 1, lookups.get());
            assertTrue(server.requests().get(0), server.requests().get(0).contains("\r\nHost: " + HOST + ":"
                    + server.port() + "\r\n"));
        }
    }

    /** Only the addresses the check vetted: with none that answers, the save fails. */
    @Test
    public void noOtherAddressThanTheVettedOnesIsTried() throws Exception {
        try (LocalServer server = new LocalServer()) {
            server.serve("/f.jpg", "image/jpeg", jpeg(1_000));
            MediaConnection connection = new MediaConnection(new URL("http://" + HOST + ":" + server.port() + "/f.jpg"),
                    new InetAddress[] { InetAddress.getByName("::1") }, host -> {
                        throw new AssertionError("a vetted host was looked up again");
                    }, 5_000, 5_000, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
            try {
                assertThrows(IOException.class, connection::connect);
            } finally {
                connection.close();
            }
            assertEquals(0, server.hits("/f.jpg"));
        }
    }

    /**
     * Through a VPN the check looks nothing up and fences nothing. The connection's own lookup
     * then runs on the policy's lookup threads, never on the save's, so a cancel ends a lookup
     * that never answers.
     */
    @Test
    public void aLookupTheCheckDidntMakeRunsOnTheLookupThreadsAndEndsAtACancel() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        AtomicBoolean hold = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MediaUrlPolicy vpn = new MediaUrlPolicy(host -> {
            threads.add(host + " on " + Thread.currentThread().getName());
            if (hold.get()) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        }, url -> false) {
            @Override
            Refusal refusal(URL url) {
                return url.getHost().equals(HOST) ? null : super.refusal(url);
            }
        };
        try (LocalServer server = new LocalServer()) {
            byte[] body = jpeg(10_000);
            server.serve("/f.jpg", "image/jpeg", body);
            String url = "http://" + HOST + ":" + server.port() + "/f.jpg";

            File into = temp.newFile();
            Downloader.Result answered = Downloader.fetch(url, Downloader.Kind.IMAGE, into, vpn, Downloader.MAX_BYTES,
                    Downloader.SILENT);
            assertEquals(answered.toString(), Downloader.Status.OK, answered.status);
            assertArrayEquals(body, Files.readAllBytes(into.toPath()));
            assertEquals(1, threads.size());
            assertTrue(threads.get(0), threads.get(0).startsWith(HOST + " on hushthreads-lookup-"));

            hold.set(true);
            AtomicBoolean stop = new AtomicBoolean();
            Downloader.Progress progress = new Downloader.Progress() {
                @Override public void transferred(long done, long total) {
                }

                @Override public void reading(Runnable close) {
                }

                @Override public boolean cancelled() {
                    return stop.get();
                }
            };
            File stuck = temp.newFile();
            FutureTask<Downloader.Result> fetch = new FutureTask<>(() -> Downloader.fetch(url, Downloader.Kind.IMAGE,
                    stuck, vpn, Downloader.MAX_BYTES, progress));
            Thread thread = new Thread(fetch, "vpn-fetch");
            thread.setDaemon(true);
            thread.start();
            try {
                assertTrue("the lookup was never asked", entered.await(10, TimeUnit.SECONDS));
                long cancelledAt = System.nanoTime();
                stop.set(true);
                Downloader.Result cancelled = fetch.get(5, TimeUnit.SECONDS);
                long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);

                assertEquals(cancelled.toString(), Downloader.Status.CANCELLED, cancelled.status);
                assertTrue("the cancel took " + tookMs + " ms to end a stuck lookup", tookMs < 2_000);
                assertFalse(stuck.exists());
                assertEquals(1, server.hits("/f.jpg"));
            } finally {
                release.countDown();
            }
        }
    }

    // ------------------------------------------------------------------ framing

    /**
     * The cases of ReleaseTransportTest's chunkedAndCloseDelimitedBodiesAreDecodedWithoutCookies:
     * a chunked answer with an extension and a trailer that sets a cookie, and one that runs to the
     * end of the connection.
     */
    @Test
    public void chunkedAndCloseDelimitedBodiesAreReadWithoutCookies() throws Exception {
        for (String response : new String[] {
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1;part=first\r\n{\r\n1\r\n}\r\n"
                        + "0\r\nSet-Cookie: trailer=private\r\n\r\n",
                "HTTP/1.0 200 OK\r\n\r\n{}" }) {
            try (RawServer server = new RawServer(response)) {
                MediaConnection connection = open(server.url("/latest?value=1"));
                try {
                    assertEquals(200, connection.code());
                    assertEquals(-1, connection.length());
                    assertEquals("{}", body(connection));
                    assertNull(connection.header("Set-Cookie"));
                } finally {
                    connection.close();
                }
                assertTrue(server.requests().get(0), server.requests().get(0).startsWith("GET /latest?value=1 HTTP/1.1\r\n"));
                assertFalse(server.requests().get(0).toLowerCase(java.util.Locale.US).contains("cookie"));
            }
        }
    }

    /** A 100 and a 103 before the answer are read past, headers and all. */
    @Test
    public void informationalAnswersBeforeTheAnswerAreSkipped() throws Exception {
        try (RawServer server = new RawServer("HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")) {
            MediaConnection connection = open(server.url("/f"));
            try {
                assertEquals(200, connection.code());
                assertEquals(2, connection.length());
                assertNull(connection.header("Link"));
                assertEquals("{}", body(connection));
            } finally {
                connection.close();
            }
        }
    }

    /**
     * The cases of ReleaseTransportTest's malformedAndAmbiguousResponsesFailClosed, read as a save
     * reads them. Its Content-Length given twice with the same number is the one case left out:
     * RFC 9110 lets a recipient read that as one length, and a save does (see the test below). Two
     * different lengths take its place, and a chunk cut short is added.
     */
    @Test
    public void malformedAndAmbiguousAnswersFailClosed() throws Exception {
        for (String response : new String[] {
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nx\r\n{}",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}x\n0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{}",
                "HTTP/1.1 200 OK\r\nX-Large: " + new String(new char[8192]).replace('\0', 'a') + "\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\n folded: invalid\r\n\r\n{}" }) {
            try (RawServer server = new RawServer(response)) {
                assertThrows(response, IOException.class, () -> read(server, "/f"));
            }
        }
    }

    /** A chunked photo is saved whole, its length unknown until the last chunk. */
    @Test
    public void aChunkedPhotoIsSavedWhole() throws Exception {
        byte[] photo = jpeg(5_000);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        for (int at = 0; at < photo.length; at += 1_500) {
            int size = Math.min(1_500, photo.length - at);
            wire.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            wire.write(photo, at, size);
            wire.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        wire.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        try (RawServer server = new RawServer(wire.toByteArray())) {
            File into = temp.newFile();
            Downloader.Result result = Downloader.fetch(server.url("/p.jpg").toString(), Downloader.Kind.IMAGE, into,
                    loopback(), Downloader.MAX_BYTES, Downloader.SILENT);
            assertEquals(result.toString(), Downloader.Status.OK, result.status);
            assertArrayEquals(photo, Files.readAllBytes(into.toPath()));
        }
    }

    // ------------------------------------------------------------------ what RFC 9112 allows

    /**
     * Bytes past ASCII in the reason and in a value (obs-text, 0x85 among them), a value folded
     * onto lines that start with a space or a tab (obs-fold, read as one space each), and the same
     * Content-Length given again, on its own line and in a list, are all read.
     */
    @Test
    public void whatRfc9112LetsAServerSendIsRead() throws Exception {
        String note = "caf" + (char) 0xE9 + " " + (char) 0x85 + (char) 0xFF;
        String response = "HTTP/1.1 200 Tr" + (char) 0xE8 + "s bien\r\n"
                + "X-Note: " + note + "\r\n"
                + "X-Folded: first\r\n  second\r\n\tthird\r\n"
                + "Content-Length: 2\r\n"
                + "Content-Length: 2\r\n"
                + "Content-Length: 2, 02\r\n"
                + "\r\n{}";
        try (RawServer server = new RawServer(response)) {
            MediaConnection connection = open(server.url("/f"));
            try {
                assertEquals(200, connection.code());
                assertEquals(2, connection.length());
                assertEquals(note, connection.header("X-Note"));
                assertEquals("first second third", connection.header("X-Folded"));
                assertEquals("{}", body(connection));
            } finally {
                connection.close();
            }
        }
    }

    /** What stays refused: two different lengths, in any form, and a CR or a NUL inside a value. */
    @Test
    public void differentLengthsAndAControlInsideAValueAreRefused() throws Exception {
        for (String response : new String[] {
                "HTTP/1.1 200 OK\r\nContent-Length: 2, 3\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n 3\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2,\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nX-Bad: a\rb\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nX-Bad: a" + (char) 0 + "b\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nX-Bad: a" + (char) 0x7F + "b\r\nContent-Length: 2\r\n\r\n{}" }) {
            try (RawServer server = new RawServer(response)) {
                assertThrows(response, IOException.class, () -> read(server, "/f"));
            }
        }
    }

    // ------------------------------------------------------------------ the request target

    /**
     * java.net.URL lets through characters {@code URL.toURI()} throws on. The request goes out
     * with each of them percent-encoded from its UTF-8 bytes, an escape already there kept, and a
     * stray % encoded too.
     */
    @Test
    public void anAddressToUriRefusesStillGoesOutEncoded() throws Exception {
        try (RawServer server = new RawServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")) {
            URL url = server.url("/a b|c^d{e}[f]\"g`h<i>j\\k" + (char) 0xE9 + ".jpg?x=1|2 3^{}&y=%2F&z=100%");
            assertEquals("{}", read(server, url.getFile()));
            String line = server.requests().get(0).substring(0, server.requests().get(0).indexOf("\r\n"));
            String target = "/a%20b%7Cc%5Ed%7Be%7D%5Bf%5D%22g%60h%3Ci%3Ej%5Ck%C3%A9.jpg?x=1%7C2%203%5E%7B%7D&y=%2F&z=100%25";
            assertEquals("GET " + target + " HTTP/1.1", line);
            assertEquals("http://127.0.0.1:" + server.port() + target, MediaConnection.routeOf(url).toString());
        }
    }

    /**
     * A redirect to a path past ASCII sends it as UTF-8 bytes. Each one read as a character of
     * its own came out encoded twice, %C3%83%C2%A9 for an é, and the next hop asked for a file
     * that isn't there.
     */
    @Test
    public void aRedirectsUtf8PathIsAskedForAsSent() throws Exception {
        try (RawServer server = new RawServer("HTTP/1.1 302 Found\r\nLocation: /cafÃ©.jpg?n=Ã©\r\n"
                + "Content-Length: 0\r\n\r\n")) {
            MediaConnection connection = open(server.url("/f"));
            try {
                assertEquals(302, connection.code());
                assertEquals("/café.jpg?n=é", connection.location());
                assertEquals("/caf%C3%A9.jpg?n=%C3%A9",
                        MediaConnection.target(new URL(server.url("/f"), connection.location())));
            } finally {
                connection.close();
            }
        }
    }

    /** Such an address is saved, through the fetch a save makes. */
    @Test
    public void aSaveOfSuchAnAddressGoesThrough() throws Exception {
        try (LocalServer server = new LocalServer()) {
            byte[] body = jpeg(4_000);
            server.serve("/a%7Cb%20c.jpg", "image/jpeg", body);
            File into = temp.newFile();
            Downloader.Result result = Downloader.fetch(server.origin() + "/a|b c.jpg?q=1|2", Downloader.Kind.IMAGE,
                    into, loopback(), Downloader.MAX_BYTES, Downloader.SILENT);
            assertEquals(result.toString(), Downloader.Status.OK, result.status);
            assertArrayEquals(body, Files.readAllBytes(into.toPath()));
            assertTrue(server.requests().get(0), server.requests().get(0).startsWith("GET /a%7Cb%20c.jpg?q=1%7C2 HTTP/1.1\r\n"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Answers every connection with the same bytes, as written, once it has read the request's
     * head, and then closes it. One connection at a time, on the loopback address.
     */
    private static final class RawServer implements Closeable {
        private final ServerSocket listener;
        private final byte[] response;
        private final List<String> requests = new CopyOnWriteArrayList<>();

        RawServer(String response) throws IOException {
            this(response.getBytes(StandardCharsets.ISO_8859_1));
        }

        RawServer(byte[] response) throws IOException {
            this.response = response;
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "raw-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        URL url(String path) throws MalformedURLException {
            return new URL("http://127.0.0.1:" + port() + path);
        }

        /** Each request's head, as sent. */
        List<String> requests() {
            return new ArrayList<>(requests);
        }

        private void serve() {
            while (!listener.isClosed()) {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(5_000);
                    InputStream in = client.getInputStream();
                    StringBuilder head = new StringBuilder();
                    while (head.length() < 64 * 1024 && !head.toString().endsWith("\r\n\r\n")) {
                        int c = in.read();
                        if (c < 0) break;
                        head.append((char) c);
                    }
                    requests.add(head.toString());
                    client.getOutputStream().write(response);
                    client.getOutputStream().flush();
                    client.shutdownOutput();
                } catch (IOException ignored) {
                    // A client that went away, or the server closing.
                }
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
