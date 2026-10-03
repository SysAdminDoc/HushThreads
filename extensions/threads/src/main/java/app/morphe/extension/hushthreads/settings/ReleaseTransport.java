/*
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.hushthreads.settings;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One bounded HTTP/1.1 GET over a private TLS socket. Platform trust and HTTPS hostname checks
 * apply. Neither request nor response goes through the process's CookieHandler, cache or pool.
 * ReleaseCheck owns consent, the exact host policy, redirects and the decoded body limit.
 */
final class ReleaseTransport implements ReleaseCheck.Transport {
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private final SSLSocketFactory sockets;

    ReleaseTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    ReleaseTransport(SSLSocketFactory sockets) {
        this.sockets = sockets;
    }

    /** A request refused before it went out. The message names no address. */
    static final class Refused extends IOException {
        Refused(String reason) {
            super(reason);
        }
    }

    @Override
    public ReleaseCheck.Exchange get(URL url, Map<String, String> headers, long deadline) throws IOException {
        String request = request(url, headers);
        int port = url.getPort() == -1 ? 443 : url.getPort();
        Socket connection = new Socket();
        try {
            connection.connect(new InetSocketAddress(url.getHost(), port), timeout(deadline, CONNECT_TIMEOUT_MS));
            SSLSocket tls = (SSLSocket) sockets.createSocket(connection, url.getHost(), port, true);
            connection = tls;
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeout(deadline, READ_TIMEOUT_MS));
            tls.startHandshake();
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new Answer(tls, deadline);
        } catch (IOException | RuntimeException failure) {
            try {
                connection.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private static String request(URL url, Map<String, String> headers) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
            throw new Refused("the address isn't an anonymous HTTPS request");
        }
        String target;
        try {
            URI uri = new URI(url.toURI().toASCIIString());
            target = uri.getRawPath();
            if (target == null || target.isEmpty()) target = "/";
            if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
        } catch (URISyntaxException malformed) {
            throw new Refused("the address isn't a URI");
        }
        String authority = url.getAuthority();
        if (authority == null || !ascii(authority) || !ascii(target) || target.length() > 2048) {
            throw new Refused("the request address isn't bounded ASCII");
        }
        StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: ")
                .append(authority).append("\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
        Map<String, String> seen = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || value == null || !ascii(value) || value.length() > 256) {
                throw new Refused("a request header isn't bounded ASCII");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.equals("accept") || lower.equals("user-agent") || lower.equals("x-github-api-version"))
                    || seen.put(lower, value) != null) {
                throw new Refused("a request header isn't allowed");
            }
            request.append(name).append(": ").append(value).append("\r\n");
        }
        return request.append("\r\n").toString();
    }

    private static boolean ascii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private static int timeout(long deadline, int maximum) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("the check ran out of time");
        return (int) Math.min(maximum, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static final class Answer extends InputStream implements ReleaseCheck.Exchange {
        private final SSLSocket socket;
        private final long deadline;
        private final InputStream input;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final int status;
        private final long length;
        private final boolean chunked;
        private int metadataBytes;
        private long remaining;
        private boolean chunkEnd;
        private boolean finished;

        Answer(SSLSocket socket, long deadline) throws IOException {
            this.socket = socket;
            this.deadline = deadline;
            input = new BufferedInputStream(socket.getInputStream(), 8 * 1024);
            String first = line();
            if (!first.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) throw new IOException("invalid HTTP status");
            status = Integer.parseInt(first.substring(9, 12));
            String header;
            while (!(header = line()).isEmpty()) {
                int colon = header.indexOf(':');
                if (colon <= 0 || !header.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new IOException("invalid HTTP header");
                }
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                // Cookies are neither retained nor offered to any shared store.
                if (name.equals("set-cookie") || name.equals("set-cookie2")) continue;
                String previous = headers.get(name);
                if (previous != null && (name.equals("content-length") || name.equals("transfer-encoding")
                        || name.equals("content-encoding") || name.equals("location"))) {
                    throw new IOException("duplicate HTTP framing or location");
                }
                headers.put(name, previous == null ? value : previous + ", " + value);
            }
            String transfer = header("Transfer-Encoding");
            String size = header("Content-Length");
            if (transfer != null && (!transfer.equalsIgnoreCase("chunked") || size != null)) {
                throw new IOException("unsupported HTTP framing");
            }
            String encoding = header("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                throw new IOException("unsupported HTTP encoding");
            }
            chunked = transfer != null;
            try {
                if (size != null && !size.matches("[0-9]{1,19}")) throw new NumberFormatException();
                length = size == null ? -1 : Long.parseLong(size);
            } catch (NumberFormatException invalid) {
                throw new IOException("invalid HTTP length", invalid);
            }
            remaining = chunked ? 0 : length;
        }

        private String line() throws IOException {
            StringBuilder result = new StringBuilder();
            for (;;) {
                int c = octet();
                if (++metadataBytes > MAX_METADATA_BYTES || result.length() >= MAX_LINE_BYTES) {
                    throw new IOException("HTTP metadata too large");
                }
                if (c == '\r') {
                    if (octet() != '\n') throw new IOException("invalid HTTP line ending");
                    metadataBytes++;
                    return result.toString();
                }
                if ((c < 0x20 && c != '\t') || c > 0x7e) throw new IOException("invalid HTTP line");
                result.append((char) c);
            }
        }

        private int octet() throws IOException {
            socket.setSoTimeout(timeout(deadline, READ_TIMEOUT_MS));
            int c = input.read();
            if (c < 0) throw new EOFException("truncated HTTP response");
            return c;
        }

        @Override
        public int status() {
            return status;
        }

        @Nullable
        @Override
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public InputStream body() {
            return this;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset > bytes.length - count) throw new IndexOutOfBoundsException();
            if (count == 0) return 0;
            if (finished) return -1;
            if (chunked && remaining == 0) {
                if (chunkEnd && (octet() != '\r' || octet() != '\n')) throw new IOException("invalid chunk end");
                String size = line();
                int extension = size.indexOf(';');
                if (extension >= 0) size = size.substring(0, extension);
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("invalid HTTP chunk");
                remaining = Long.parseLong(size, 16);
                chunkEnd = true;
                if (remaining == 0) {
                    // Bound trailers, but don't retain them or feed them to a cookie handler.
                    while (!line().isEmpty()) { }
                    finished = true;
                    return -1;
                }
            } else if (!chunked && remaining == 0) {
                finished = true;
                return -1;
            }
            socket.setSoTimeout(timeout(deadline, READ_TIMEOUT_MS));
            int read = input.read(bytes, offset, remaining < 0 ? count : (int) Math.min(remaining, count));
            if (read < 0) {
                if (remaining >= 0) throw new EOFException("truncated HTTP body");
                finished = true;
                return -1;
            }
            if (remaining >= 0) remaining -= read;
            return read;
        }

        @Override
        public void close() {
            finished = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // This socket isn't pooled or shared with another request.
            }
        }
    }
}
