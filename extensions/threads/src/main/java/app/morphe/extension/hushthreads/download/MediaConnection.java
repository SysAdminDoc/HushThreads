/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.download;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One GET of a media address, on a socket of its own, in place of {@code HttpURLConnection}.
 *
 * <p>{@code HttpURLConnection} asks the process's {@link java.net.CookieHandler} for cookies to
 * send and hands it every {@code Set-Cookie} it gets, and answers a 401 or a 407 by asking the
 * process's {@link java.net.Authenticator} for a password. It does so on the JDK and on Android
 * alike, and no setting turns either off for one connection. Threads' process is Threads': its
 * handlers can hold an account's cookies and passwords. So a save sends no {@code Cookie}, no
 * {@code Authorization} and no {@code Proxy-Authorization} header, never asks either handler
 * anything, never changes them, and drops every cookie it's sent.
 *
 * <p>What the old connection did with the network stays. The proxy the process's
 * {@link ProxySelector} names for the address is used, which on Android carries the Wi-Fi, global
 * and PAC proxy: an HTTP proxy through a {@code CONNECT} tunnel for HTTPS, or a SOCKS 5 proxy. Each
 * the selector lists is tried in turn, and one that can't be reached is reported back to it. A
 * VPN carries every socket of the process, this one too. TLS uses
 * {@link HttpsURLConnection#getDefaultSSLSocketFactory()}, so the trust is the platform's and the
 * app's network security config, and the certificate has to name the host. A proxy that asks for
 * a password, by a 407 or by SOCKS, gets none, and the fetch ends in {@link ProxyRefused}.
 *
 * <p>Straight to the server, the socket goes to the addresses {@link MediaUrlPolicy#vetWithin}
 * vetted, each in turn, and nothing here looks a name up on the save's thread: a lookup the
 * policy didn't make runs on its bounded lookup threads ({@link Lookup}).
 *
 * <p>One request a connection, closed after it ({@code Connection: close}), so nothing is pooled
 * between saves. Every wait is cut to what's left before the fetch's deadline, and a timer closes
 * the socket at the deadline itself: a TLS read waits for a whole record, and a peer that sends
 * one a byte at a time just inside the read timeout would hold it past every per-read check.
 */
final class MediaConnection {

    /** A proxy wants a password, which a save never sends. The message names no proxy and no address. */
    static final class ProxyRefused extends IOException {
        ProxyRefused(String reason) {
            super(reason);
        }
    }

    /**
     * Looks a host up where a cancel or the fetch's time can end the wait: the policy's lookup
     * threads ({@link MediaUrlPolicy#addressesWithin}). Throws when the host doesn't resolve.
     */
    interface Lookup {
        InetAddress[] addresses(String host) throws IOException;
    }

    /** The longest status or header line read, and the most of them read for one answer. */
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEAD = 64 * 1024;

    private static final Pattern STATUS = Pattern.compile("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * The JDK's default hostname verifier, which answers no to every name: it only stands in for
     * the check the JDK's own connection makes itself, which is endpoint identification here too.
     * Android's default is a real verifier (OkHostnameVerifier), and so is any an app installs.
     */
    private static final String JDK_PLACEHOLDER_VERIFIER = "javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier";

    /**
     * Closes a connection whose fetch is out of time. One daemon thread, gone after 30 idle
     * seconds, so nothing stays parked in Threads' process between saves.
     */
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "hushthreads-save-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(30, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
        return timer;
    }

    private final URL url;
    private final String host;
    private final int port;
    private final boolean https;
    /** The addresses the policy vetted for [host], or null when it looked nothing up. */
    private final InetAddress[] vetted;
    /** Looks up a proxy given by name. */
    private final Lookup lookup;
    /** Looks the server up for a route straight to it that no check looked up. */
    private final Lookup direct;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long deadline;

    private final Object lock = new Object();
    /** The socket to the first hop, the proxy's or the server's. What a cancel closes. */
    private Socket raw;
    /** What the request goes over: [raw], or the TLS socket over it. */
    private Socket socket;
    private boolean cancelled;
    private ScheduledFuture<?> expiry;

    private InputStream in;
    private int code;
    private final Map<String, String> headers = new HashMap<>();
    private long length = -1;
    private boolean chunked;
    private InputStream body;

    /**
     * A GET of [url], http or https, not yet connected. Straight to the server it goes to [vetted],
     * the addresses the policy checked, or when that's null to what [lookup] gives; a literal
     * address is its own. [connectTimeoutMs] and [readTimeoutMs] bound each wait (0 for none), and
     * each is cut to what's left before [deadline] on the monotonic clock.
     */
    MediaConnection(URL url, InetAddress[] vetted, Lookup lookup, int connectTimeoutMs, int readTimeoutMs,
            long deadline) throws IOException {
        this(url, vetted, lookup, lookup, connectTimeoutMs, readTimeoutMs, deadline);
    }

    /**
     * As above, with the server looked up through [direct] when a route goes straight to it and
     * the policy looked nothing up: a proxy list's DIRECT entry, which the policy can still hold
     * to a public answer. A proxy given by name is still looked up through [lookup].
     */
    MediaConnection(URL url, InetAddress[] vetted, Lookup lookup, Lookup direct, int connectTimeoutMs,
            int readTimeoutMs, long deadline) throws IOException {
        String scheme = url.getProtocol().toLowerCase(Locale.US);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new IOException("the address isn't HTTP");
        if (url.getUserInfo() != null) throw new IOException("the address carries a user name");
        String name = url.getHost();
        if (name == null || name.isEmpty()) throw new IOException("the address names no host");
        this.url = url;
        https = scheme.equals("https");
        host = name;
        port = url.getPort() != -1 ? url.getPort() : https ? 443 : 80;
        this.vetted = vetted == null || vetted.length == 0 ? null : vetted.clone();
        this.lookup = lookup;
        this.direct = direct;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.deadline = deadline;
    }

    /**
     * Connects through the route the process's proxy settings give, sends the request and reads
     * the answer's status and headers. Throws {@link ProxyRefused} when a proxy wants a password.
     */
    void connect() throws IOException {
        String target = target(url);
        String authority = port == (https ? 443 : 80) ? host : host + ":" + port;
        if (!printable(authority) || authority.indexOf(' ') >= 0 || target.length() > MAX_LINE / 2) {
            throw new IOException("the address isn't plain ASCII of a bounded length");
        }
        URI route = routeOf(url);
        startTimer();

        ProxySelector selector = ProxySelector.getDefault();
        List<Proxy> proxies = null;
        if (selector != null) {
            try {
                proxies = selector.select(route);
            } catch (RuntimeException e) {
                // A selector that can't answer leaves the fetch to go direct, as no selector does.
            }
        }
        if (proxies == null || proxies.isEmpty()) proxies = Collections.singletonList(Proxy.NO_PROXY);

        IOException failure = null;
        boolean absolute = false;
        for (Proxy listed : proxies) {
            Proxy proxy = listed == null ? Proxy.NO_PROXY : listed;
            try {
                absolute = open(proxy);
                failure = null;
                break;
            } catch (ProxyRefused | SSLHandshakeException | SSLPeerUnverifiedException ended) {
                // A proxy that wants a password ends the fetch, not the route. So does a
                // certificate the platform doesn't trust or one for another name: that's what a
                // TLS-inspecting proxy shows, and going round it to the next route would hide it.
                throw ended;
            } catch (IOException e) {
                closeSockets();
                failure = e;
                if (isCancelled() || Downloader.expired(deadline)) break;
                if (proxy.type() != Proxy.Type.DIRECT && selector != null) {
                    try {
                        selector.connectFailed(route, proxy.address(), e);
                    } catch (RuntimeException ignored) {
                        // The next proxy is tried all the same.
                    }
                }
            }
        }
        if (failure != null) throw failure;

        StringBuilder request = new StringBuilder("GET ")
                .append(absolute ? "http://" + authority + target : target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n");
        agent(request);
        request.append("Accept-Encoding: identity\r\nConnection: close\r\n\r\n");
        send(socket, request);

        in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
        int[] budget = { MAX_HEAD };
        do {
            headers.clear();
            code = head(in, socket, headers, budget);
            // A 1xx before the answer is skipped. 101 never comes: nothing asks to switch.
        } while (code >= 100 && code < 200);
        // Only a proxy that read the request itself can ask for its password. Through a tunnel, a
        // SOCKS proxy or none at all, a 407 is the server's answer, and an error like any other.
        if (code == 407 && absolute) throw new ProxyRefused("the proxy asked for a password");

        String transfer = headers.get("transfer-encoding");
        String size = headers.get("content-length");
        if (transfer != null && (!transfer.equalsIgnoreCase("chunked") || size != null)) {
            throw new IOException("the answer's length is given two ways or a way not read here");
        }
        chunked = transfer != null;
        if (size != null) length = Long.parseLong(size);
        if (code == 204 || code == 304) length = 0;
    }

    /**
     * The URI the proxy selector is asked about for [url]. Built from the address's parts, with
     * the path and query as {@link #target} sends them, because {@link URL#toURI()} throws on a
     * character a URL lets through, such as a space or a {@code |}.
     */
    static URI routeOf(URL url) throws IOException {
        String scheme = url.getProtocol().toLowerCase(Locale.US);
        String authority = url.getPort() == -1 ? url.getHost() : url.getHost() + ":" + url.getPort();
        try {
            return new URI(scheme + "://" + authority + target(url));
        } catch (URISyntaxException e) {
            throw new IOException("the address isn't a URI");
        }
    }

    /**
     * The request target: the address's path and query, with every character a target can't
     * carry as written percent-encoded from its UTF-8 bytes. That's controls, the space, anything
     * past ASCII, the ASCII characters RFC 3986 keeps out of a path and a query
     * ({@code " < > [ \ ] ^ ` { | }}) and a {@code %} that doesn't start an escape. An escape
     * already there is kept as it is.
     */
    static String target(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = url.getQuery();
        return encoded(path) + (query == null ? "" : "?" + encoded(query));
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String encoded(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xFF;
            boolean escape = c == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2]);
            if (c <= 0x20 || c >= 0x7F || "\"<>[\\]^`{|}".indexOf(c) >= 0 || (c == '%' && !escape)) {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            } else {
                out.append((char) c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    /**
     * Opens a socket to the first hop of [proxy] and through it to the server, TLS included.
     * Answers whether the request goes to an HTTP proxy in absolute form.
     */
    private boolean open(Proxy proxy) throws IOException {
        // Never a plain new Socket(): that asks the selector again for a socket:// route of its own,
        // and given a SOCKS proxy it speaks SOCKS itself, under this class's, and asks the process's
        // Authenticator for the proxy's password. The route is the one chosen above, and only it.
        Socket first;
        boolean absolute = false;
        switch (proxy.type()) {
            case HTTP:
                first = reach(proxyAddresses(proxy.address()), proxyPort(proxy.address()));
                if (https) tunnel(first);
                else absolute = true;
                break;
            case SOCKS:
                first = reach(proxyAddresses(proxy.address()), proxyPort(proxy.address()));
                socks(first);
                break;
            default:
                InetAddress literal = literalAddress(host);
                first = reach(literal != null ? new InetAddress[] { literal }
                        : vetted != null ? vetted : looked(direct, host), port);
                break;
        }
        if (https) secure(first);
        return absolute;
    }

    /**
     * A socket of its own connected to the first of [addresses] that takes it on [port], each one
     * tried in turn as the platform's connection tried every address of a host: an IPv6 address
     * that can't be reached leaves the IPv4 one after it to carry the fetch. Each try waits at
     * most the connect timeout, cut to what's left of the fetch's time.
     */
    private Socket reach(InetAddress[] addresses, int port) throws IOException {
        IOException failure = null;
        for (InetAddress address : addresses) {
            if (isCancelled()) throw new SocketException("the fetch was cancelled");
            if (Downloader.expired(deadline)) throw new SocketTimeoutException("the fetch ran out of time");
            if (address == null) continue;
            Socket attempt = adopt(new Socket(Proxy.NO_PROXY));
            try {
                attempt.connect(new InetSocketAddress(address, port), connectTimeout());
                return attempt;
            } catch (IOException e) {
                closeSockets();
                failure = e;
            }
        }
        if (isCancelled()) throw new SocketException("the fetch was cancelled");
        throw failure != null ? failure : new UnknownHostException("the host has no address");
    }

    /** The addresses [through] gives [name], on its threads. */
    private static InetAddress[] looked(Lookup through, String name) throws IOException {
        if (through == null) throw new UnknownHostException("nothing may look the host up");
        InetAddress[] found = through.addresses(name);
        if (found == null || found.length == 0) throw new UnknownHostException("the host does not resolve");
        return found;
    }

    /** A proxy's addresses: the one the selector gave, or its name's, looked up off this thread. */
    private InetAddress[] proxyAddresses(SocketAddress address) throws IOException {
        if (!(address instanceof InetSocketAddress)) throw new IOException("the proxy has no address");
        InetSocketAddress proxy = (InetSocketAddress) address;
        if (!proxy.isUnresolved()) return new InetAddress[] { proxy.getAddress() };
        InetAddress literal = literalAddress(proxy.getHostString());
        return literal != null ? new InetAddress[] { literal } : looked(lookup, proxy.getHostString());
    }

    private static int proxyPort(SocketAddress address) {
        return ((InetSocketAddress) address).getPort();
    }

    /** Asks an HTTP proxy for a tunnel to the server. */
    private void tunnel(Socket proxy) throws IOException {
        String target = host + ":" + port;
        StringBuilder request = new StringBuilder("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(target).append("\r\n");
        agent(request);
        request.append("\r\n");
        send(proxy, request);
        // Read straight from the socket: anything buffered past the proxy's answer is the TLS
        // handshake's.
        int answer = head(proxy.getInputStream(), proxy, new HashMap<>(), new int[] { MAX_HEAD });
        if (answer == 407) throw new ProxyRefused("the proxy asked for a password to open a tunnel");
        if (answer < 200 || answer > 299) throw new IOException("the proxy answered " + answer + " to a tunnel");
    }

    /** Asks a SOCKS 5 proxy, offering no password, to connect to the server. */
    private void socks(Socket proxy) throws IOException {
        OutputStream out = proxy.getOutputStream();
        InputStream from = proxy.getInputStream();
        out.write(new byte[] { 5, 1, 0 });
        out.flush();
        byte[] chosen = exactly(from, proxy, 2);
        if (chosen[0] != 5) throw new IOException("the proxy doesn't speak SOCKS 5");
        if ((chosen[1] & 0xFF) == 0xFF) throw new ProxyRefused("the SOCKS proxy asked for a password");
        if (chosen[1] != 0) throw new IOException("the SOCKS proxy chose a way to sign in that wasn't offered");

        InetAddress literal = literalAddress(host);
        byte[] address = literal == null ? null : literal.getAddress();
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        if (address == null && name.length > 255) throw new IOException("the host name is too long for SOCKS");
        byte[] request = new byte[6 + (address != null ? address.length : 1 + name.length)];
        request[0] = 5;
        request[1] = 1;
        int at = 3;
        if (address != null) {
            request[at++] = (byte) (address.length == 4 ? 1 : 4);
            System.arraycopy(address, 0, request, at, address.length);
            at += address.length;
        } else {
            // The proxy looks the name up, as the JDK's own SOCKS client lets it.
            request[at++] = 3;
            request[at++] = (byte) name.length;
            System.arraycopy(name, 0, request, at, name.length);
            at += name.length;
        }
        request[at++] = (byte) (port >> 8);
        request[at] = (byte) port;
        out.write(request);
        out.flush();

        byte[] reply = exactly(from, proxy, 4);
        if (reply[0] != 5) throw new IOException("the proxy doesn't speak SOCKS 5");
        if (reply[1] != 0) throw new IOException("the SOCKS proxy refused the connection: " + (reply[1] & 0xFF));
        int bound;
        switch (reply[3]) {
            case 1: bound = 4; break;
            case 4: bound = 16; break;
            case 3: bound = exactly(from, proxy, 1)[0] & 0xFF; break;
            default: throw new IOException("the SOCKS proxy answered with an address of an unknown kind");
        }
        exactly(from, proxy, bound + 2);
    }

    /** Starts TLS over [under] and checks the certificate names the host. */
    private void secure(Socket under) throws IOException {
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        SSLSocket tls = (SSLSocket) factory.createSocket(under, name, port, true);
        synchronized (lock) {
            if (cancelled) {
                close(tls);
                throw new SocketException("the fetch was cancelled");
            }
            socket = tls;
        }
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.setSoTimeout(readTimeout());
        tls.startHandshake();
        if (Downloader.expired(deadline)) throw new SocketTimeoutException("the fetch ran out of time");
        verify(name, tls.getSession());
    }

    /**
     * The platform's hostname verifier, asked outright once the handshake is done, as
     * HttpsURLConnection asks it: it can be stricter than endpoint identification, and an app or
     * a test can install another. The JDK's placeholder is the one it never asks.
     */
    private static void verify(String name, SSLSession session) throws SSLPeerUnverifiedException {
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        if (JDK_PLACEHOLDER_VERIFIER.equals(verifier.getClass().getName())) return;
        if (!verifier.verify(name, session)) {
            throw new SSLPeerUnverifiedException("the certificate doesn't name the host");
        }
    }

    /** The answer's status code. */
    int code() {
        return code;
    }

    /** The answer's header [name], several joined by commas, or null. Never a Set-Cookie: none is kept. */
    String header(String name) {
        return headers.get(name.toLowerCase(Locale.US));
    }

    /**
     * The redirect's address, its bytes read as UTF-8: a path past ASCII comes that way. Read as
     * one character a byte like the other headers, {@link #target} then encoded each such byte
     * twice, and the next hop asked for a file that isn't there.
     */
    String location() {
        String value = headers.get("location");
        return value == null ? null : new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** The length the answer announced, or -1. */
    long length() {
        return chunked ? -1 : length;
    }

    /**
     * The answer's body, as it comes: to its announced length, through its chunks, or to the end
     * of the connection. An answer cut short of its announced length simply ends, and the caller,
     * which knows what was announced, tells. One the server compressed though it was asked not to
     * fails here.
     */
    InputStream body() throws IOException {
        if (body != null) return body;
        String encoding = headers.get("content-encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
            throw new IOException("the server compressed the answer");
        }
        body = new Body();
        return body;
    }

    /**
     * Ends the connection from any thread: closes the socket to the first hop, which ends a read
     * or a handshake that's waiting, the way {@code HttpURLConnection.disconnect()} closed its own.
     * Called before the connect, it keeps the connect from starting. The deadline's timer calls
     * it too.
     */
    void cancel() {
        Socket first;
        synchronized (lock) {
            cancelled = true;
            first = raw;
        }
        close(first);
    }

    /** Ends the connection for good, on the thread that read it. */
    void close() {
        Socket first;
        Socket over;
        ScheduledFuture<?> timer;
        synchronized (lock) {
            cancelled = true;
            first = raw;
            over = socket;
            timer = expiry;
        }
        if (timer != null) timer.cancel(false);
        close(first);
        if (over != first) close(over);
    }

    /** Has the deadline close the connection, whatever it's waiting on then. */
    private void startTimer() {
        synchronized (lock) {
            if (expiry != null || cancelled) return;
            try {
                expiry = DEADLINES.schedule(this::cancel, Math.max(0L, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException e) {
                // No timer: each wait is still cut to the deadline, only a TLS record can outlast it.
            }
        }
    }

    private boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /** Makes [made] the first hop's socket, unless the connection was cancelled. */
    private Socket adopt(Socket made) throws IOException {
        synchronized (lock) {
            if (cancelled) {
                close(made);
                throw new SocketException("the fetch was cancelled");
            }
            raw = made;
            socket = made;
        }
        return made;
    }

    /** Closes what a route that failed opened, so the next one starts clean. */
    private void closeSockets() {
        Socket first;
        Socket over;
        synchronized (lock) {
            first = raw;
            over = socket;
            raw = null;
            socket = null;
        }
        close(first);
        if (over != first) close(over);
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException | RuntimeException ignored) {
            // Already closed, or closing under a read: either way it's done.
        }
    }

    private int connectTimeout() {
        return Downloader.within(connectTimeoutMs, deadline);
    }

    private int readTimeout() {
        return Downloader.within(readTimeoutMs, deadline);
    }

    /** [host] as an address when it's written as one, IPv6 in brackets or not, or null for a name. Never a lookup. */
    private static InetAddress literalAddress(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean v4 = bare.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean v6 = bare.indexOf(':') >= 0 && bare.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(bare);
        } catch (IOException e) {
            return null;
        }
    }

    /** The platform's own User-Agent, the one its connection sent, when it has a printable one. */
    private static void agent(StringBuilder request) {
        String agent = System.getProperty("http.agent");
        if (agent != null && !agent.isEmpty() && agent.length() <= 256 && printable(agent)) {
            request.append("User-Agent: ").append(agent).append("\r\n");
        }
    }

    /** Whether [text] is printable ASCII, spaces included: nothing in it can end a line of the request. */
    private static boolean printable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private void send(Socket to, StringBuilder request) throws IOException {
        to.setSoTimeout(readTimeout());
        OutputStream out = to.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Reads a status line and its headers from [from] into [into], lowercased by name, and answers
     * the status. Set-Cookie lines are dropped as they're read. [budget] is how many bytes may
     * still be read, shared by every head of one answer.
     *
     * <p>What RFC 9112 lets a server send is read: bytes past ASCII in a value or the reason
     * (obs-text), a value folded onto a line that starts with a space or a tab (obs-fold, read as
     * one space), and a Content-Length given again with the same number.
     */
    private int head(InputStream from, Socket over, Map<String, String> into, int[] budget) throws IOException {
        String status = line(from, over, budget);
        if (!STATUS.matcher(status).matches()) throw new IOException("the answer isn't HTTP");
        int answer = Integer.parseInt(status.substring(9, 12));
        String name = null;
        StringBuilder value = null;
        String line;
        while (!(line = line(from, over, budget)).isEmpty()) {
            char lead = line.charAt(0);
            if (lead == ' ' || lead == '\t') {
                // Before any header, it's whitespace RFC 9112 says to refuse or skip: refused.
                if (name == null) throw new IOException("the answer's head starts with a folded line");
                value.append(' ').append(line.trim());
                continue;
            }
            if (name != null) field(into, name, value.toString());
            int colon = line.indexOf(':');
            if (colon <= 0 || !TOKEN.matcher(line.substring(0, colon)).matches()) {
                throw new IOException("the answer has a header that isn't one");
            }
            name = line.substring(0, colon).toLowerCase(Locale.US);
            value = new StringBuilder(line.substring(colon + 1).trim());
        }
        if (name != null) field(into, name, value.toString());
        return answer;
    }

    /** Adds one header, folded lines joined, to [into]. */
    private static void field(Map<String, String> into, String name, String value) throws IOException {
        // Never kept and never handed to a cookie store.
        if (name.equals("set-cookie") || name.equals("set-cookie2")) return;
        String before = into.get(name);
        if (name.equals("content-length")) {
            into.put(name, length(before, value));
            return;
        }
        if (before != null && (name.equals("transfer-encoding") || name.equals("content-encoding")
                || name.equals("location"))) {
            throw new IOException("the answer gives its " + name + " twice");
        }
        into.put(name, before == null ? value : before + ", " + value);
    }

    /**
     * The one length every Content-Length so far gives: [before], or null, and each number in
     * [value]. The same number given again, on a line of its own or in a list, is one length (RFC
     * 9110 8.6). Two different numbers can't be told apart and are refused.
     */
    private static String length(String before, String value) throws IOException {
        String agreed = before;
        for (String part : value.split(",", -1)) {
            String number = part.trim();
            if (!number.matches("[0-9]{1,18}")) throw new IOException("the answer's length isn't a number");
            String plain = Long.toString(Long.parseLong(number));
            if (agreed != null && !agreed.equals(plain)) throw new IOException("the answer gives two lengths");
            agreed = plain;
        }
        return agreed;
    }

    /** One line of a head, without its CR LF. A CR anywhere but before the LF is refused. */
    private String line(InputStream from, Socket over, int[] budget) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            int c = octet(from, over);
            if (--budget[0] < 0 || text.length() >= MAX_LINE) throw new IOException("the answer's head is too long");
            if (c == '\r') {
                if (octet(from, over) != '\n') throw new IOException("the answer's head has a bare CR");
                budget[0]--;
                return text.toString();
            }
            if (c == '\n') return text.toString();
            // Tab, visible ASCII and obs-text. Controls and DEL aren't text.
            if ((c < 0x20 && c != '\t') || c == 0x7F) throw new IOException("the answer's head isn't text");
            text.append((char) c);
        }
    }

    private int octet(InputStream from, Socket over) throws IOException {
        over.setSoTimeout(readTimeout());
        int c = from.read();
        if (Downloader.expired(deadline)) throw new SocketTimeoutException("the fetch ran out of time");
        if (c < 0) throw new EOFException("the answer ended in its head");
        return c;
    }

    private byte[] exactly(InputStream from, Socket over, int count) throws IOException {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) bytes[i] = (byte) octet(from, over);
        return bytes;
    }

    /** The body as {@link #body()} describes it. */
    private final class Body extends InputStream {
        /** Bytes left of the announced length or of the current chunk; -1 to the end of the connection. */
        private long left = chunked ? 0 : length;
        private boolean chunkEnds;
        private boolean finished = !chunked && length == 0;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || count > bytes.length - offset) throw new IndexOutOfBoundsException();
            if (count == 0) return 0;
            if (finished) return -1;
            if (chunked && left == 0) {
                int[] budget = { MAX_HEAD };
                if (chunkEnds && !line(in, socket, budget).isEmpty()) throw new IOException("a chunk ran past its size");
                String size = line(in, socket, budget);
                int extension = size.indexOf(';');
                if (extension >= 0) size = size.substring(0, extension);
                size = size.trim();
                if (!size.matches("[0-9a-fA-F]{1,15}")) throw new IOException("a chunk's size isn't a number");
                left = Long.parseLong(size, 16);
                chunkEnds = true;
                if (left == 0) {
                    // Trailers are read past and dropped, cookies among them.
                    while (!line(in, socket, budget).isEmpty()) {
                        // Nothing kept.
                    }
                    finished = true;
                    return -1;
                }
            }
            socket.setSoTimeout(readTimeout());
            int read = in.read(bytes, offset, left < 0 ? count : (int) Math.min(left, count));
            if (read < 0) {
                if (chunked) throw new EOFException("the answer ended inside a chunk");
                finished = true;
                return -1;
            }
            if (left >= 0) {
                left -= read;
                if (!chunked && left == 0) finished = true;
            }
            return read;
        }

        @Override
        public void close() {
            finished = true;
        }
    }
}
