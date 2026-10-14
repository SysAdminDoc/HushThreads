/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.extension.hushthreads.download;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A proxy on the loopback address for the download tests, the kind a phone's Wi-Fi or global proxy
 * setting names: HTTP, which forwards a request in absolute form and tunnels a CONNECT, or SOCKS 5.
 * One that asks for a password answers every request with a 407 (HTTP), or refuses the no-password
 * way to sign in (SOCKS), and lets nothing through whatever comes next.
 *
 * <p>It records what it was sent: each HTTP request's head as it came, or for SOCKS the ways to
 * sign in it was offered and the address it was asked for. Each client has a thread of its own,
 * so saves through it can run at once.
 */
final class LocalProxy implements Closeable {

    private final ServerSocket socket;
    private final Proxy.Type type;
    private final boolean asksForPassword;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();

    LocalProxy(Proxy.Type type, boolean asksForPassword) throws IOException {
        if (type == Proxy.Type.DIRECT) throw new IllegalArgumentException("a proxy is HTTP or SOCKS");
        this.type = type;
        this.asksForPassword = asksForPassword;
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::accept, "local-proxy");
        thread.setDaemon(true);
        thread.start();
    }

    /** This proxy as a selector names it, by an address already looked up. */
    Proxy proxy() {
        return new Proxy(type, new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()));
    }

    /** This proxy by a name nobody has looked up yet, as Android's selector names a Wi-Fi proxy. */
    Proxy unresolved() {
        return new Proxy(type, InetSocketAddress.createUnresolved("127.0.0.1", socket.getLocalPort()));
    }

    /** Every request so far: an HTTP head as sent, or {@code SOCKS methods [..] to host:port}. */
    List<String> requests() {
        return new ArrayList<>(requests);
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                open.add(client);
                Thread worker = new Thread(() -> serve(client), "local-proxy-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException ignored) {
                // The proxy closing.
            }
        }
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(10_000);
            if (type == Proxy.Type.HTTP) http(client);
            else socks(client);
        } catch (IOException ignored) {
            // A client that went away, or the proxy closing.
        } finally {
            close(client);
        }
    }

    private void http(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        String head = head(in);
        requests.add(head);
        if (asksForPassword) {
            out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                    + "Proxy-Authenticate: Basic realm=\"local proxy\"\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            // Whatever the client sends next gets no answer: a client that answered the 407 with a
            // password would show up here as a second request on a connection of its own.
            return;
        }
        int end = head.indexOf("\r\n");
        String[] request = head.substring(0, end).split(" ");
        if (request[0].equals("CONNECT")) {
            int colon = request[1].lastIndexOf(':');
            Socket upstream = connect(request[1].substring(0, colon), Integer.parseInt(request[1].substring(colon + 1)));
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            relay(client, upstream);
            return;
        }
        URI target = URI.create(request[1]);
        if (!"http".equals(target.getScheme())) throw new IOException("not a request in absolute form");
        Socket upstream = connect(target.getHost(), target.getPort() == -1 ? 80 : target.getPort());
        String path = target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
        String forwarded = request[0] + " " + path + " " + request[2] + head.substring(end);
        upstream.getOutputStream().write(forwarded.getBytes(StandardCharsets.ISO_8859_1));
        upstream.getOutputStream().flush();
        relay(client, upstream);
    }

    private void socks(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        byte[] greeting = exactly(in, 2);
        if (greeting[0] != 5) throw new IOException("not SOCKS 5");
        byte[] methods = exactly(in, greeting[1] & 0xFF);
        if (asksForPassword) {
            requests.add("SOCKS methods " + Arrays.toString(methods) + " refused");
            out.write(new byte[] { 5, (byte) 0xFF });
            out.flush();
            return;
        }
        out.write(new byte[] { 5, 0 });
        out.flush();
        byte[] ask = exactly(in, 4);
        String host;
        switch (ask[3]) {
            case 1: host = InetAddress.getByAddress(exactly(in, 4)).getHostAddress(); break;
            case 4: host = InetAddress.getByAddress(exactly(in, 16)).getHostAddress(); break;
            case 3: host = new String(exactly(in, exactly(in, 1)[0] & 0xFF), StandardCharsets.US_ASCII); break;
            default: throw new IOException("an address of an unknown kind");
        }
        byte[] port = exactly(in, 2);
        int number = (port[0] & 0xFF) << 8 | (port[1] & 0xFF);
        requests.add("SOCKS methods " + Arrays.toString(methods) + " to " + host + ":" + number
                + (ask[3] == 3 ? " by name" : ""));
        Socket upstream = connect(host, number);
        out.write(new byte[] { 5, 0, 0, 1, 127, 0, 0, 1, 0, 0 });
        out.flush();
        relay(client, upstream);
    }

    private Socket connect(String host, int port) throws IOException {
        // Straight there: a plain new Socket() would ask the test's proxy selector for a route.
        Socket upstream = new Socket(Proxy.NO_PROXY);
        open.add(upstream);
        upstream.connect(new InetSocketAddress(host, port), 10_000);
        upstream.setSoTimeout(10_000);
        return upstream;
    }

    /** Copies both ways until either side ends, then closes both. */
    private void relay(Socket client, Socket upstream) {
        Thread back = new Thread(() -> copy(upstream, client), "local-proxy-relay");
        back.setDaemon(true);
        back.start();
        copy(client, upstream);
    }

    private void copy(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // One side went away.
        } finally {
            close(from);
            close(to);
        }
    }

    /** A request head, through the blank line that ends it. */
    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("the request ended in its head");
            head.write(c);
            matched = (c == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (c == '\r' ? 1 : 0);
            if (head.size() > 64 * 1024) throw new IOException("the request head is too long");
        }
        return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] exactly(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        for (int at = 0; at < count; ) {
            int read = in.read(bytes, at, count - at);
            if (read < 0) throw new EOFException("the client stopped mid-message");
            at += read;
        }
        return bytes;
    }

    private void close(Socket socket) {
        open.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        for (Socket left : new ArrayList<>(open)) close(left);
    }
}
