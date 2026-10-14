/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * A save's requests carry nothing of Threads' account. The process's cookie handler, authenticator
 * and response cache are replaced here by ones that would hand out an account's cookie and
 * password and count every question, and each test holds that none was asked anything, none was
 * replaced, and no request sent a Cookie, Authorization or Proxy-Authorization header: on the
 * file, on a redirect on the same server or to another, through an HTTP proxy, a tunnel or a SOCKS
 * proxy, and while three fetches run at once.
 *
 * <p>What the old connection did with the network stays, and is held here too: the proxy the
 * selector names, the next one when it can't be reached, the platform's TLS trust and the check
 * that the certificate names the host, the platform's hostname verifier, and a cancel that ends a
 * read through a tunnel. A TLS failure through a proxy ends the fetch instead of going round the
 * proxy, and a peer that drips its TLS records ends at the fetch's time. A proxy that asks for a
 * password ends the fetch as PROXY_AUTH after its one request, and a 407 from the server is an
 * error like any other.
 */
public class DownloadIsolationTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    /** What the recording authenticator is asked by this test itself, to show it's still installed. */
    private static final String PROBE = "is this still the process's authenticator";
    private static final String ACCOUNT_COOKIE = "account=threads-session";
    private static final String ACCOUNT_PASSWORD = "threads-password";

    /** An ftyp box the sniff reads as an MP4 video; the made-up bytes after it don't matter. */
    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private final List<Closeable> opened = new ArrayList<>();
    private final Set<Integer> ports = ConcurrentHashMap.newKeySet();
    private MediaUrlPolicy policy;

    private final RecordingCookies cookies = new RecordingCookies();
    private final RecordingPasswords passwords = new RecordingPasswords();
    private final RecordingCache cache = new RecordingCache();
    private final Routes routes = new Routes();
    private CookieHandler cookiesBefore;
    private ResponseCache cacheBefore;
    private ProxySelector selectorBefore;
    private SSLSocketFactory tlsBefore;
    private HostnameVerifier verifierBefore;
    /** The TLS factory a test expects the process to have: the platform's, or the test key's once trusted. */
    private SSLSocketFactory tls;
    /** The hostname verifier a test expects the process to have: the platform's, or one it installed. */
    private HostnameVerifier verifier;
    private SSLContext testKey;
    private X509ExtendedTrustManager testTrust;

    @Before
    public void install() throws Exception {
        cookiesBefore = CookieHandler.getDefault();
        cacheBefore = ResponseCache.getDefault();
        selectorBefore = ProxySelector.getDefault();
        tlsBefore = HttpsURLConnection.getDefaultSSLSocketFactory();
        verifierBefore = HttpsURLConnection.getDefaultHostnameVerifier();
        tls = tlsBefore;
        verifier = verifierBefore;
        CookieHandler.setDefault(cookies);
        ResponseCache.setDefault(cache);
        ProxySelector.setDefault(routes);
        Authenticator.setDefault(passwords);
        // The TLS servers answer as localhost, which the connection looks up through the policy.
        policy = new MediaUrlPolicy(host -> new InetAddress[] { host.equals("localhost")
                ? InetAddress.getLoopbackAddress() : InetAddress.getByName("157.240.22.19") }) {
            @Override
            Refusal refusal(URL url) {
                boolean loopback = url.getHost().equals("127.0.0.1") || url.getHost().equals("localhost");
                if (loopback && ports.contains(url.getPort())) return null;
                return super.refusal(url);
            }
        };

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream fixture = getClass().getResourceAsStream("/release-loopback.p12")) {
            assertNotNull("the public test-only localhost key fixture", fixture);
            keys.load(fixture, "test-only".toCharArray());
        }
        KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, "test-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        testKey = SSLContext.getInstance("TLS");
        testKey.init(keyManager.getKeyManagers(), trust.getTrustManagers(), null);
        for (TrustManager each : trust.getTrustManagers()) {
            if (each instanceof X509ExtendedTrustManager) testTrust = (X509ExtendedTrustManager) each;
        }
        assertNotNull("the test key's trust", testTrust);
    }

    @After
    public void restore() throws IOException {
        for (Closeable each : opened) each.close();
        CookieHandler.setDefault(cookiesBefore);
        ResponseCache.setDefault(cacheBefore);
        ProxySelector.setDefault(selectorBefore);
        HttpsURLConnection.setDefaultSSLSocketFactory(tlsBefore);
        HttpsURLConnection.setDefaultHostnameVerifier(verifierBefore);
        // Android's API can't read the authenticator there was. None is the JDK's own.
        Authenticator.setDefault(null);
    }

    // ------------------------------------------------------------------ fixtures

    private LocalServer server() throws IOException {
        LocalServer server = new LocalServer();
        opened.add(server);
        ports.add(server.port());
        return server;
    }

    /** A server answering over TLS as localhost, with the test key's certificate. */
    private LocalServer tlsServer() throws IOException {
        LocalServer server = new LocalServer(testKey);
        opened.add(server);
        ports.add(server.port());
        return server;
    }

    private LocalProxy proxy(Proxy.Type type, boolean asksForPassword) throws IOException {
        LocalProxy proxy = new LocalProxy(type, asksForPassword);
        opened.add(proxy);
        return proxy;
    }

    /** The platform trusts the test key from here on, as a network security config can make it. */
    private void trustTheTestKey() {
        tls = testKey.getSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls);
    }

    /**
     * The platform trusts the test key from here on, except the first certificate it's shown,
     * as it wouldn't trust a TLS-inspecting proxy's. Answers how many it was shown.
     */
    private AtomicInteger trustAllButTheFirst() throws Exception {
        FirstRefused trust = new FirstRefused(testTrust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { trust }, null);
        tls = context.getSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls);
        return trust.asked;
    }

    /** [installed] is the platform's hostname verifier from here on, as an app can make it. */
    private void verifyWith(HostnameVerifier installed) {
        verifier = installed;
        HttpsURLConnection.setDefaultHostnameVerifier(installed);
    }

    private static byte[] jpeg(int size) {
        byte[] body = new byte[size];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xD8;
        body[2] = (byte) 0xFF;
        for (int i = 3; i < size; i++) body[i] = (byte) (i * 7);
        return body;
    }

    /** [path] answers [body] as a JPEG, with a cookie of its own the client is meant to drop. */
    private static byte[] servePhoto(LocalServer server, String path, int size) {
        byte[] body = jpeg(size);
        server.serve(path, "image/jpeg", body);
        server.header(path, "Set-Cookie: cdn-session=" + path.substring(1) + "; Path=/; HttpOnly");
        server.header(path, "Set-Cookie2: legacy=1; Version=1");
        return body;
    }

    private Downloader.Result fetch(String url, Downloader.Kind kind, File into) {
        return Downloader.fetch(url, kind, into, policy, Downloader.MAX_BYTES, Downloader.SILENT);
    }

    /** A file to fetch into. It's there before the fetch, so a failed one has to have taken it away. */
    private File file() throws IOException {
        return temp.newFile();
    }

    /** The shared handlers are the ones installed here, and none was asked anything. */
    private void assertUntouched(String when) {
        assertSame(when + ": the cookie handler was replaced", cookies, CookieHandler.getDefault());
        assertSame(when + ": the response cache was replaced", cache, ResponseCache.getDefault());
        assertSame(when + ": the proxy selector was replaced", routes, ProxySelector.getDefault());
        assertSame(when + ": the TLS factory was replaced", tls, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(when + ": the hostname check was replaced", verifier, HttpsURLConnection.getDefaultHostnameVerifier());
        // Android's API can't read the authenticator back, so it's asked a question only this test asks.
        int probes = passwords.probes.get();
        Authenticator.requestPasswordAuthentication("probe.invalid", null, 0, "http", PROBE, "basic");
        assertEquals(when + ": the authenticator was replaced", probes + 1, passwords.probes.get());
        assertEquals(when + ": the cookie handler was asked", 0, cookies.calls.get());
        assertEquals(when + ": the authenticator was asked", Collections.emptyList(), passwords.asked);
        assertEquals(when + ": the response cache was asked", 0, cache.calls.get());
        // A plain socket asks for a socket:// route of its own, and speaks SOCKS itself when given one.
        assertFalse(when + ": a socket chose its own route " + routes.schemes, routes.schemes.contains("socket"));
    }

    /** No request in [heads] carries a cookie or a password, Threads' or the server's. */
    private static void assertNoCredentials(List<String> heads) {
        assertFalse("nothing was sent at all, so this checks nothing", heads.isEmpty());
        for (String head : heads) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                String name = colon < 0 ? "" : line.substring(0, colon).trim().toLowerCase(Locale.US);
                assertFalse("a request sent " + name + ":\n" + head, name.equals("cookie") || name.equals("cookie2")
                        || name.equals("authorization") || name.equals("proxy-authorization"));
            }
            assertFalse(head, head.contains(ACCOUNT_COOKIE) || head.contains("cdn-session") || head.contains("legacy=1"));
            assertFalse(head, head.contains("Basic ") || head.contains("Bearer "));
        }
    }

    private static List<String> all(List<String> first, List<String> second) {
        List<String> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    // ------------------------------------------------------------------ cookies and origins

    @Test
    public void aSetCookieOnTheFileIsNeitherKeptNorSentAgain() throws Exception {
        LocalServer cdn = server();
        byte[] body = servePhoto(cdn, "/f.jpg", 20_000);
        assertUntouched("before");

        for (int i = 0; i < 2; i++) {
            File into = file();
            Downloader.Result result = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);
            assertEquals(result.toString(), Downloader.Status.OK, result.status);
            assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        }

        assertEquals(2, cdn.requests().size());
        assertNoCredentials(cdn.requests());
        assertUntouched("after");

        // Not even the connection keeps it for its caller to read.
        MediaConnection connection = new MediaConnection(new URL(cdn.origin() + "/f.jpg"), null, null, 5_000, 5_000,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        try {
            connection.connect();
            assertEquals(200, connection.code());
            assertEquals("image/jpeg", connection.header("Content-Type"));
            assertNull(connection.header("Set-Cookie"));
            assertNull(connection.header("Set-Cookie2"));
        } finally {
            connection.close();
        }
        assertUntouched("after the connection on its own");
    }

    /**
     * A cookie set on a redirect, to a hop on the same server or one that leaves for another, goes
     * no further: each hop is a request of its own with nothing from the one before.
     */
    @Test
    public void redirectsOnTheSameCdnAndToAnotherCarryNoCookie() throws Exception {
        LocalServer first = server();
        LocalServer other = server();
        byte[] body = servePhoto(other, "/moved.jpg", 30_000);
        first.redirect("/start.jpg", "/same.jpg");
        first.header("/start.jpg", "Set-Cookie: hop=1; Path=/");
        // To another server, by another name: localhost rather than 127.0.0.1, on another port.
        first.redirect("/same.jpg", "http://localhost:" + other.port() + "/moved.jpg");
        first.header("/same.jpg", "Set-Cookie: hop=2; Domain=localhost; Path=/");
        assertUntouched("before");

        File into = file();
        Downloader.Result result = fetch(first.origin() + "/start.jpg", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        assertEquals(1, first.hits("/start.jpg"));
        assertEquals(1, first.hits("/same.jpg"));
        assertEquals(1, other.hits("/moved.jpg"));
        assertTrue(other.requests().get(0), other.requests().get(0).contains("\r\nHost: localhost:" + other.port() + "\r\n"));
        assertNoCredentials(all(first.requests(), other.requests()));
        for (String head : all(first.requests(), other.requests())) assertFalse(head, head.contains("hop="));
        assertUntouched("after");
    }

    /** A server that asks for a password is never given one: the save ends as an expired link would. */
    @Test
    public void anOriginThatAsksForAPasswordGetsNone() throws Exception {
        LocalServer cdn = server();
        cdn.serve("/locked.jpg", 401, "text/plain", "sign in".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7);
        cdn.header("/locked.jpg", "WWW-Authenticate: Basic realm=\"cdn\"");
        File into = file();

        Downloader.Result result = fetch(cdn.origin() + "/locked.jpg", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.EXPIRED, result.status);
        assertFalse(into.exists());
        assertEquals("the request was sent again", 1, cdn.hits("/locked.jpg"));
        assertNoCredentials(cdn.requests());
        assertUntouched("after");
    }

    /** Three fetches at once, two plain and one over TLS, each with its cookie: the handlers never move. */
    @Test
    public void concurrentFetchesLeaveTheSharedHandlersAlone() throws Exception {
        trustTheTestKey();
        List<LocalServer> servers = Arrays.asList(server(), server(), tlsServer());
        CountDownLatch release = new CountDownLatch(1);
        long size = 1024 * 1024;
        for (LocalServer server : servers) {
            server.serveHeld("/held.mp4", "video/mp4", MP4_HEAD, size, 256 * 1024, release);
            server.header("/held.mp4", "Set-Cookie: cdn-session=" + server.port() + "; Path=/");
        }
        assertUntouched("before");

        CountDownLatch moving = new CountDownLatch(servers.size());
        List<FutureTask<Downloader.Result>> fetches = new ArrayList<>();
        List<File> files = new ArrayList<>();
        try {
            for (LocalServer server : servers) {
                File into = file();
                files.add(into);
                AtomicBoolean counted = new AtomicBoolean();
                Downloader.Progress progress = new Downloader.Progress() {
                    @Override public void transferred(long done, long total) {
                        if (done > 0 && counted.compareAndSet(false, true)) moving.countDown();
                    }

                    @Override public void reading(Runnable close) {
                    }

                    @Override public boolean cancelled() {
                        return false;
                    }
                };
                FutureTask<Downloader.Result> fetch = new FutureTask<>(() -> Downloader.fetch(server.origin() + "/held.mp4",
                        Downloader.Kind.VIDEO, into, policy, Downloader.MAX_BYTES, progress));
                fetches.add(fetch);
                new Thread(fetch, "isolation-fetch-" + server.port()).start();
            }
            assertTrue("the three fetches never all started reading", moving.await(20, TimeUnit.SECONDS));
            assertUntouched("while three fetches were reading");
        } finally {
            release.countDown();
        }

        for (int i = 0; i < fetches.size(); i++) {
            Downloader.Result result = fetches.get(i).get(30, TimeUnit.SECONDS);
            assertEquals(result.toString(), Downloader.Status.OK, result.status);
            assertEquals(size, files.get(i).length());
        }
        List<String> heads = new ArrayList<>();
        for (LocalServer server : servers) heads.addAll(server.requests());
        assertEquals(3, heads.size());
        assertNoCredentials(heads);
        assertUntouched("after");
    }

    // ------------------------------------------------------------------ the route

    @Test
    public void throughAnHttpProxyAPlainAddressGoesInAbsoluteForm() throws Exception {
        LocalServer cdn = server();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, false);
        // Android's selector names a Wi-Fi proxy by a name not yet looked up.
        routes.route = Collections.singletonList(proxy.unresolved());
        File into = file();

        Downloader.Result result = fetch(cdn.origin() + "/f.jpg?x=1", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        List<String> asked = proxy.requests();
        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).startsWith("GET " + cdn.origin() + "/f.jpg?x=1 HTTP/1.1\r\n"));
        assertTrue(cdn.requests().get(0), cdn.requests().get(0).startsWith("GET /f.jpg?x=1 HTTP/1.1\r\n"));
        assertNoCredentials(all(asked, cdn.requests()));
        assertTrue("the selector was never asked", routes.selected.get() > 0);
        assertUntouched("after");
    }

    /** HTTPS through an HTTP proxy is a tunnel, and the TLS inside it is checked as it is without one. */
    @Test
    public void throughAnHttpProxyTlsGoesThroughATunnel() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, false);
        routes.route = Collections.singletonList(proxy.proxy());
        File into = file();

        Downloader.Result result = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        List<String> asked = proxy.requests();
        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).startsWith("CONNECT localhost:" + cdn.port() + " HTTP/1.1\r\n"));
        assertTrue(cdn.requests().get(0), cdn.requests().get(0).startsWith("GET /f.jpg HTTP/1.1\r\n"));
        assertNoCredentials(all(asked, cdn.requests()));
        assertUntouched("after");
    }

    /**
     * Straight to the server, TLS takes the platform's trust and the certificate has to name the
     * host: a certificate the platform doesn't trust, or one for another name, gets no request.
     */
    @Test
    public void directTlsKeepsThePlatformsTrustAndTheNameCheck() throws Exception {
        LocalServer cdn = tlsServer();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);

        File refused = file();
        Downloader.Result untrusted = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, refused);
        assertEquals(untrusted.toString(), Downloader.Status.NETWORK_ERROR, untrusted.status);
        assertFalse(refused.exists());

        trustTheTestKey();
        Downloader.Result otherName = fetch("https://127.0.0.1:" + cdn.port() + "/f.jpg", Downloader.Kind.IMAGE, refused);
        assertEquals(otherName.toString(), Downloader.Status.NETWORK_ERROR, otherName.status);
        assertFalse(refused.exists());
        assertEquals("a request went over TLS that wasn't checked", 0, cdn.requests().size());

        File into = file();
        Downloader.Result result = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        assertNoCredentials(cdn.requests());
        assertUntouched("after");
    }

    /** A SOCKS 5 proxy is offered no password, and looks a name up itself. */
    @Test
    public void throughASocksProxyNoPasswordIsOffered() throws Exception {
        trustTheTestKey();
        LocalServer plain = server();
        LocalServer secure = tlsServer();
        byte[] photo = servePhoto(plain, "/f.jpg", 10_000);
        byte[] other = servePhoto(secure, "/g.jpg", 12_000);
        LocalProxy proxy = proxy(Proxy.Type.SOCKS, false);
        routes.route = Collections.singletonList(proxy.proxy());

        File byNumber = file();
        Downloader.Result first = fetch(plain.origin() + "/f.jpg", Downloader.Kind.IMAGE, byNumber);
        File byName = file();
        Downloader.Result second = fetch(secure.origin() + "/g.jpg", Downloader.Kind.IMAGE, byName);

        assertEquals(first.toString(), Downloader.Status.OK, first.status);
        assertEquals(second.toString(), Downloader.Status.OK, second.status);
        assertArrayEquals(photo, Files.readAllBytes(byNumber.toPath()));
        assertArrayEquals(other, Files.readAllBytes(byName.toPath()));
        assertEquals(Arrays.asList(
                "SOCKS methods [0] to 127.0.0.1:" + plain.port(),
                "SOCKS methods [0] to localhost:" + secure.port() + " by name"), proxy.requests());
        assertNoCredentials(all(plain.requests(), secure.requests()));
        assertUntouched("after");
    }

    /** A proxy that can't be reached is told to the selector, and the next one it named carries the fetch. */
    @Test
    public void anUnreachableProxyIsReportedAndTheNextOneCarriesTheFetch() throws Exception {
        LocalServer cdn = server();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, false);
        InetSocketAddress gone;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gone = new InetSocketAddress(InetAddress.getLoopbackAddress(), closed.getLocalPort());
        }
        routes.route = Arrays.asList(new Proxy(Proxy.Type.HTTP, gone), proxy.proxy());
        File into = file();

        Downloader.Result result = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        assertEquals(Collections.singletonList((SocketAddress) gone), routes.failed);
        assertEquals(1, proxy.requests().size());
        assertNoCredentials(all(proxy.requests(), cdn.requests()));
        assertUntouched("after");
    }

    /**
     * A proxy list can end in DIRECT, and the check looks nothing up for it since the list names a
     * proxy. With only the phone's own lookup between that socket and the answer, the answer has
     * to be public: a name answered with a loopback address gets no connection. Through a VPN the
     * same route connects, so the fence is what stopped it.
     */
    @Test
    public void aProxyListsDirectEntryIsHeldToAPublicAnswer() throws Exception {
        LocalServer cdn = server();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);
        InetSocketAddress gone;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gone = new InetSocketAddress(InetAddress.getLoopbackAddress(), closed.getLocalPort());
        }
        routes.route = Arrays.asList(new Proxy(Proxy.Type.HTTP, gone), Proxy.NO_PROXY);
        String url = "http://localhost:" + cdn.port() + "/f.jpg";

        File fenced = file();
        Downloader.Result refused = Downloader.fetch(url, Downloader.Kind.IMAGE, fenced, directEntry(true),
                Downloader.MAX_BYTES, Downloader.SILENT);
        assertEquals(refused.toString(), Downloader.Status.NETWORK_ERROR, refused.status);
        assertEquals("the loopback answer got a request", 0, cdn.requests().size());
        assertFalse(fenced.exists());

        File throughVpn = file();
        Downloader.Result fetched = Downloader.fetch(url, Downloader.Kind.IMAGE, throughVpn, directEntry(false),
                Downloader.MAX_BYTES, Downloader.SILENT);
        assertEquals(fetched.toString(), Downloader.Status.OK, fetched.status);
        assertArrayEquals(body, Files.readAllBytes(throughVpn.toPath()));
        assertEquals(1, cdn.requests().size());
    }

    /**
     * The class's rules for a list that names a proxy, so the check looks nothing up, with the
     * DIRECT entry's fence up ([fenced], no VPN) or down (a VPN).
     */
    private MediaUrlPolicy directEntry(boolean fenced) {
        return new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getLoopbackAddress() }, url -> false,
                url -> fenced) {
            @Override
            Refusal refusal(URL url) {
                return url.getHost().equals("localhost") && ports.contains(url.getPort()) ? null : super.refusal(url);
            }
        };
    }

    /** A cancel closes the socket under a read that's waiting inside a tunnel, as it does without one. */
    @Test
    public void aCancelEndsAReadThroughATunnel() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        CountDownLatch never = new CountDownLatch(1);
        cdn.serveHeld("/held.mp4", "video/mp4", MP4_HEAD, 8 * 1024 * 1024, 128 * 1024, never);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, false);
        routes.route = Collections.singletonList(proxy.proxy());
        AtomicReference<Runnable> closer = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch moving = new CountDownLatch(1);
        Downloader.Progress progress = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
                if (done > 0) moving.countDown();
            }

            @Override public void reading(Runnable close) {
                closer.set(close);
            }

            @Override public boolean cancelled() {
                return stop.get();
            }
        };
        File into = file();
        FutureTask<Downloader.Result> fetch = new FutureTask<>(() -> Downloader.fetch(cdn.origin() + "/held.mp4",
                Downloader.Kind.VIDEO, into, policy, Downloader.MAX_BYTES, progress));
        new Thread(fetch, "isolation-cancel").start();
        try {
            assertTrue("the fetch never started reading", moving.await(20, TimeUnit.SECONDS));
            long cancelledAt = System.nanoTime();
            stop.set(true);
            closer.get().run();
            Downloader.Result result = fetch.get(10, TimeUnit.SECONDS);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);

            assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
            assertTrue("the cancel took " + tookMs + " ms to end the read", tookMs < 5_000);
            assertFalse(into.exists());
        } finally {
            never.countDown();
        }
        assertUntouched("after");
    }

    /**
     * A TLS peer that sends its records a byte at a time, each byte inside the read timeout, can't
     * hold a fetch past its time. A TLS read waits for a whole record, so only the timer that
     * closes the socket at the deadline ends it.
     */
    @Test
    public void aTlsPeerThatDripsItsRecordsEndsAtTheFetchsTime() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        servePhoto(cdn, "/f.jpg", 10_000);
        DripRelay drip = new DripRelay(cdn.port(), 40);
        opened.add(drip);
        ports.add(drip.port());
        File into = file();
        Downloader.budgetMs = 1_500;
        try {
            long started = System.nanoTime();
            FutureTask<Downloader.Result> fetch = new FutureTask<>(() -> fetch("https://localhost:" + drip.port()
                    + "/f.jpg", Downloader.Kind.IMAGE, into));
            Thread thread = new Thread(fetch, "isolation-drip");
            thread.setDaemon(true);
            thread.start();
            Downloader.Result result = fetch.get(15, TimeUnit.SECONDS);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(result.toString(), Downloader.Status.TIMED_OUT, result.status);
            assertTrue("a dripping peer held the fetch " + tookMs + " ms against a 1.5 s limit", tookMs < 4_000);
            assertTrue("the relay never dripped, so this checks nothing", drip.dripped.get() > 0);
            assertEquals("a request went out", 0, cdn.requests().size());
            assertFalse(into.exists());
        } finally {
            Downloader.budgetMs = Downloader.BUDGET_MS;
        }
        assertUntouched("after");
    }

    // ------------------------------------------------------------------ TLS failures

    /**
     * Once the handshake is done the platform's hostname verifier is asked, as HttpsURLConnection
     * asks it, and a no from it ends the fetch before any request goes out.
     */
    @Test
    public void thePlatformsHostnameVerifierIsAskedOnceTheHandshakeIsDone() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        byte[] body = servePhoto(cdn, "/f.jpg", 10_000);
        List<String> asked = new CopyOnWriteArrayList<>();
        verifyWith((name, session) -> {
            asked.add(name + " " + session.getPeerHost());
            return false;
        });

        File refused = file();
        Downloader.Result no = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, refused);
        assertEquals(no.toString(), Downloader.Status.NETWORK_ERROR, no.status);
        assertEquals(Collections.singletonList("localhost localhost"), asked);
        assertEquals("a request went out past the verifier's no", 0, cdn.requests().size());
        assertFalse(refused.exists());

        verifyWith((name, session) -> {
            asked.add(name + " " + session.getPeerHost());
            return true;
        });
        File into = file();
        Downloader.Result yes = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);
        assertEquals(yes.toString(), Downloader.Status.OK, yes.status);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
        assertEquals(Arrays.asList("localhost localhost", "localhost localhost"), asked);
        assertUntouched("after");
    }

    /**
     * A certificate the platform doesn't trust, met through a proxy, is what a TLS-inspecting
     * proxy shows. The fetch ends there: it doesn't go round the proxy to the DIRECT route after
     * it, and the proxy isn't reported as unreachable.
     */
    @Test
    public void anUntrustedCertificateThroughAProxyEndsTheFetchRatherThanGoingRoundIt() throws Exception {
        AtomicInteger shown = trustAllButTheFirst();
        LocalServer cdn = tlsServer();
        servePhoto(cdn, "/f.jpg", 10_000);
        assertNoWayRound(cdn, shown);
    }

    /** The same for a certificate the hostname verifier says names another host. */
    @Test
    public void aCertificateForAnotherNameThroughAProxyEndsTheFetchRatherThanGoingRoundIt() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        servePhoto(cdn, "/f.jpg", 10_000);
        AtomicInteger asked = new AtomicInteger();
        verifyWith((name, session) -> asked.incrementAndGet() > 1);
        assertNoWayRound(cdn, asked);
    }

    /**
     * A fetch of [cdn] whose route is an HTTP proxy and then DIRECT, where TLS through the proxy
     * fails, ends as a network failure after one TLS check ([checks]), with no request sent and
     * nothing told to the selector.
     */
    private void assertNoWayRound(LocalServer cdn, AtomicInteger checks) throws Exception {
        LocalProxy proxy = proxy(Proxy.Type.HTTP, false);
        routes.route = Arrays.asList(proxy.proxy(), Proxy.NO_PROXY);
        File into = file();

        Downloader.Result result = fetch(cdn.origin() + "/f.jpg", Downloader.Kind.IMAGE, into);

        assertEquals(result.toString(), Downloader.Status.NETWORK_ERROR, result.status);
        assertEquals("the certificate was checked again on another route", 1, checks.get());
        assertEquals(1, proxy.requests().size());
        assertTrue(proxy.requests().get(0), proxy.requests().get(0).startsWith("CONNECT localhost:" + cdn.port() + " "));
        assertEquals("a request went out", 0, cdn.requests().size());
        assertEquals("a TLS failure was reported as an unreachable proxy", 0, routes.failed.size());
        assertFalse(into.exists());
        assertUntouched("after");
    }

    // ------------------------------------------------------------------ a proxy that wants a password

    @Test
    public void anHttpProxyThatAsksForAPasswordGetsNone() throws Exception {
        LocalServer cdn = server();
        servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, true);
        routes.route = Collections.singletonList(proxy.proxy());
        assertProxyRefused(cdn.origin() + "/f.jpg", proxy, cdn, "GET " + cdn.origin() + "/f.jpg HTTP/1.1\r\n");
    }

    @Test
    public void aTunnelThatAsksForAPasswordGetsNone() throws Exception {
        trustTheTestKey();
        LocalServer cdn = tlsServer();
        servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.HTTP, true);
        routes.route = Collections.singletonList(proxy.proxy());
        assertProxyRefused(cdn.origin() + "/f.jpg", proxy, cdn, "CONNECT localhost:" + cdn.port() + " HTTP/1.1\r\n");
    }

    @Test
    public void aSocksProxyThatAsksForAPasswordGetsNone() throws Exception {
        LocalServer cdn = server();
        servePhoto(cdn, "/f.jpg", 10_000);
        LocalProxy proxy = proxy(Proxy.Type.SOCKS, true);
        routes.route = Collections.singletonList(proxy.proxy());
        assertProxyRefused(cdn.origin() + "/f.jpg", proxy, cdn, "SOCKS methods [0] refused");
    }

    /**
     * Only a proxy that read the request can ask for its password. A 407 from the server, straight,
     * through a tunnel or through SOCKS, is an error answer like any other.
     */
    @Test
    public void a407FromTheServerIsAnErrorAndNotAProxysQuestion() throws Exception {
        trustTheTestKey();
        LocalServer plain = server();
        LocalServer secure = tlsServer();
        for (LocalServer cdn : Arrays.asList(plain, secure)) {
            cdn.serve("/locked.jpg", 407, "text/plain", "who are you".getBytes(StandardCharsets.UTF_8), 11);
            cdn.header("/locked.jpg", "Proxy-Authenticate: Basic realm=\"cdn\"");
        }
        LocalProxy tunnel = proxy(Proxy.Type.HTTP, false);
        LocalProxy socks = proxy(Proxy.Type.SOCKS, false);
        assertUntouched("before");

        routes.route = Collections.singletonList(Proxy.NO_PROXY);
        Downloader.Result straight = fetch(plain.origin() + "/locked.jpg", Downloader.Kind.IMAGE, file());
        routes.route = Collections.singletonList(tunnel.proxy());
        Downloader.Result tunnelled = fetch(secure.origin() + "/locked.jpg", Downloader.Kind.IMAGE, file());
        routes.route = Collections.singletonList(socks.proxy());
        Downloader.Result bySocks = fetch(plain.origin() + "/locked.jpg", Downloader.Kind.IMAGE, file());

        for (Downloader.Result result : Arrays.asList(straight, tunnelled, bySocks)) {
            assertEquals(result.toString(), Downloader.Status.HTTP_ERROR, result.status);
            assertTrue(result.reason, result.reason.contains("407"));
        }
        assertEquals(2, plain.hits("/locked.jpg"));
        assertEquals(1, secure.hits("/locked.jpg"));
        assertNoCredentials(all(plain.requests(), secure.requests()));
        assertEquals(0, routes.failed.size());
        assertUntouched("after");
    }

    /**
     * The fetch of [url] through [proxy] ends as PROXY_AUTH after one request that starts with
     * [request], in moments, and no other proxy is tried. Nothing reached [cdn], no password was
     * looked for or sent, and the reason names no address.
     */
    private void assertProxyRefused(String url, LocalProxy proxy, LocalServer cdn, String request) throws Exception {
        assertUntouched("before");
        File into = file();
        long started = System.nanoTime();

        Downloader.Result result = fetch(url, Downloader.Kind.IMAGE, into);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(result.toString(), Downloader.Status.PROXY_AUTH, result.status);
        assertTrue("the refusal took " + tookMs + " ms", tookMs < 10_000);
        assertFalse(into.exists());
        List<String> asked = proxy.requests();
        assertEquals("the proxy was asked again: " + asked, 1, asked.size());
        assertTrue(asked.get(0), asked.get(0).startsWith(request));
        assertNoCredentials(asked);
        assertEquals("the server was reached past the proxy", 0, cdn.requests().size());
        assertTrue(result.reason, result.reason.contains("password"));
        assertFalse(result.reason, result.reason.contains("127.0.0.1") || result.reason.contains("localhost")
                || result.reason.matches(".*[0-9].*"));
        assertEquals("a proxy that asked for a password was reported as unreachable", 0, routes.failed.size());
        assertUntouched("after");
    }

    // ------------------------------------------------------------------ recording handlers

    /** Hands Threads' cookie to anyone who asks, and counts every question. */
    private static final class RecordingCookies extends CookieHandler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            calls.incrementAndGet();
            return Collections.singletonMap("Cookie", Collections.singletonList(ACCOUNT_COOKIE));
        }

        @Override
        public void put(URI uri, Map<String, List<String>> headers) {
            calls.incrementAndGet();
        }
    }

    /** Hands Threads' password to anyone who asks, and remembers who did. */
    private static final class RecordingPasswords extends Authenticator {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final AtomicInteger probes = new AtomicInteger();

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (PROBE.equals(getRequestingPrompt())) {
                probes.incrementAndGet();
                return null;
            }
            asked.add(getRequestorType() + " " + getRequestingHost() + ":" + getRequestingPort());
            return new PasswordAuthentication("threads", ACCOUNT_PASSWORD.toCharArray());
        }
    }

    /** Never has an answer, and counts every question. */
    private static final class RecordingCache extends ResponseCache {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CacheResponse get(URI uri, String method, Map<String, List<String>> headers) {
            calls.incrementAndGet();
            return null;
        }

        @Override
        public CacheRequest put(URI uri, URLConnection connection) {
            calls.incrementAndGet();
            return null;
        }
    }

    /** The proxies a test sets, direct by default, and the ones reported as unreachable. */
    private static final class Routes extends ProxySelector {
        volatile List<Proxy> route = Collections.singletonList(Proxy.NO_PROXY);
        final AtomicInteger selected = new AtomicInteger();
        final Set<String> schemes = ConcurrentHashMap.newKeySet();
        final List<SocketAddress> failed = new CopyOnWriteArrayList<>();

        /** The same route for every address, socket:// included, as a SOCKS proxy setting gives. */
        @Override
        public List<Proxy> select(URI uri) {
            selected.incrementAndGet();
            schemes.add(uri.getScheme());
            return route;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            failed.add(address);
        }
    }

    /** Trusts what [real] trusts, except the first server certificate it's shown. Counts them. */
    private static final class FirstRefused extends X509ExtendedTrustManager {
        final X509ExtendedTrustManager real;
        final AtomicInteger asked = new AtomicInteger();

        FirstRefused(X509ExtendedTrustManager real) {
            this.real = real;
        }

        private void refuseTheFirst() throws CertificateException {
            if (asked.incrementAndGet() == 1) throw new CertificateException("an inspecting proxy's certificate");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException {
            refuseTheFirst();
            real.checkServerTrusted(chain, auth, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException {
            refuseTheFirst();
            real.checkServerTrusted(chain, auth, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            refuseTheFirst();
            real.checkServerTrusted(chain, auth);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException {
            real.checkClientTrusted(chain, auth, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException {
            real.checkClientTrusted(chain, auth, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            real.checkClientTrusted(chain, auth);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return real.getAcceptedIssuers();
        }
    }

    /**
     * Takes one client and passes what it sends to the loopback port [to] at once, and what comes
     * back a byte at a time, one every [everyMs]: a TLS peer whose every byte comes well inside any
     * read timeout and whose records take minutes.
     */
    private static final class DripRelay implements Closeable {
        final AtomicInteger dripped = new AtomicInteger();
        private final ServerSocket listener;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();

        DripRelay(int to, long everyMs) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> relay(to, everyMs), "drip-relay");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void relay(int to, long everyMs) {
            // Never a plain new Socket(): it would ask the test's selector for a route.
            try (Socket client = listener.accept(); Socket upstream = new Socket(Proxy.NO_PROXY)) {
                open.add(client);
                open.add(upstream);
                upstream.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), to), 5_000);
                Thread up = new Thread(() -> {
                    byte[] buffer = new byte[16 * 1024];
                    try {
                        int read;
                        while ((read = client.getInputStream().read(buffer)) >= 0) {
                            upstream.getOutputStream().write(buffer, 0, read);
                            upstream.getOutputStream().flush();
                        }
                    } catch (IOException ignored) {
                        // One side went away.
                    }
                }, "drip-relay-up");
                up.setDaemon(true);
                up.start();
                InputStream from = upstream.getInputStream();
                OutputStream back = client.getOutputStream();
                int octet;
                while ((octet = from.read()) >= 0) {
                    back.write(octet);
                    back.flush();
                    dripped.incrementAndGet();
                    Thread.sleep(everyMs);
                }
            } catch (IOException | InterruptedException ignored) {
                // The client let go, or the relay closing.
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket each : open) {
                try {
                    each.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
            }
        }
    }
}
