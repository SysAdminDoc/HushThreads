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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Which addresses a save may fetch.
 *
 * <p>The address comes out of Facebook's own objects by reflection, and the story path ranks
 * whatever text it finds there. Something that can put text into one of those objects could
 * otherwise send this process to any host it likes, a router's admin page or a service on the
 * phone itself included. So every address, the first and each redirect, has to be HTTPS on the
 * default port, with no user name in it, to a host of Meta's media CDNs.
 *
 * <p>The host check is the one that matters: TLS then has to show Meta's certificate for that
 * name, which nothing on a home network can. When the socket goes straight to the address the
 * phone's own lookup gives, that answer has to be public too, a second fence against a lookup
 * that points a Meta name at a private address. The connection goes to the addresses this check
 * vetted and no others ({@link #vetWithin}), so a resolver can't change its answer between the
 * two. Through a VPN or a proxy the lookup isn't where the socket goes, so that fence stays down
 * there: a proxy resolves the name itself, and through a VPN the connection looks the name up on
 * the same bounded threads ({@link #addressesWithin}). A proxy list's DIRECT entry, tried when
 * the proxies before it fail, is fenced again unless a VPN is up ({@link #directAddressesWithin}).
 *
 * <p>FAKE_IP: fake-IP DNS (Clash, mihomo, sing-box, v2rayNG, OpenClash, common where Facebook
 * is blocked) answers every name from 198.18.0.0/15, fc00::/18 or fdfe:dcba:9876::/64 and
 * carries the connection to the real host. It runs as a VPN on the phone, which the route sees, or on the home router, which it
 * can't: a direct route then gets those answers too, and refusing them failed every save there.
 * Neither range is a home network's, so they are let through on every route.
 *
 * <p>No Android type here, like {@link RenditionPicker}: the rules run under plain JUnit. Not final,
 * so a test can let its local server through and hand every other hop to these rules.
 */
class MediaUrlPolicy {

    /**
     * Looks a host up. A save uses the system's ({@link #DNS}); tests answer with fixed addresses,
     * or hold the answer back, instead of asking DNS.
     */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Whether the connection to a URL goes straight to the address the phone's own lookup gives. */
    interface Route {
        boolean direct(URL url);
    }

    static final Resolver DNS = InetAddress::getAllByName;

    /** No VPN and no proxy between the phone and the address. */
    static final Route DIRECT = url -> true;

    /** Meta's media CDNs. A save never needs anything else. */
    private static final String[] META_MEDIA_DOMAINS = { "fbcdn.net", "fbsbx.com", "cdninstagram.com" };

    /** The rules with a direct route, for a caller that knows no better and for tests. */
    static final MediaUrlPolicy META = new MediaUrlPolicy(DNS);

    /** Why an address may not be fetched. */
    static final class Refusal {
        final String reason;
        /**
         * True when the address itself is fine and the network isn't: its lookup failed, or it
         * answered with a private address on a direct route. That's a network problem to the
         * person saving, not a file that isn't Facebook's.
         */
        final boolean network;

        Refusal(String reason, boolean network) {
            this.reason = reason;
            this.network = network;
        }
    }

    /**
     * What {@link #vetWithin} found: why the address may not be fetched, or else the addresses
     * its host resolved to, each one vetted, in the resolver's order. Null addresses when no
     * lookup was made: the route goes through a VPN or a proxy, or a test let its own host through.
     */
    static final class Vetted {
        final Refusal refusal;
        final InetAddress[] addresses;

        Vetted(Refusal refusal, InetAddress[] addresses) {
            this.refusal = refusal;
            this.addresses = addresses;
        }
    }

    /** Where {@link #lookedUp} leaves the addresses it vetted while {@link #vetWithin} asks. */
    private static final ThreadLocal<InetAddress[][]> VETTED = new ThreadLocal<>();

    private final Resolver resolver;
    /** Package-visible so a test can ask a production policy about its route without a lookup. */
    final Route route;
    /**
     * Whether a socket sent straight out, past any proxy, reaches the address the phone's own
     * lookup gives: no VPN. A proxy list can end in DIRECT, and [route] reads any proxy in it as
     * no direct route, so the check looks nothing up and {@link #directAddressesWithin} fences
     * the lookup that entry makes. Off unless given: a test that lets its own host through hands
     * its loopback server to that lookup.
     */
    private final Route unproxied;

    MediaUrlPolicy(Resolver resolver) {
        this(resolver, DIRECT);
    }

    MediaUrlPolicy(Resolver resolver, Route route) {
        this(resolver, route, url -> false);
    }

    MediaUrlPolicy(Resolver resolver, Route route, Route unproxied) {
        this.resolver = resolver;
        this.route = route;
        this.unproxied = unproxied;
    }

    /**
     * Why [url] may not be fetched, or {@code null} when it may. Checks the shape and, on a direct
     * route, every address the host resolves to, so it can block on DNS: call it on a worker
     * thread. It takes the very {@link URL} the connection opens, so the check and the fetch
     * can't read the address two different ways.
     */
    Refusal refusal(URL url) {
        String shape = shapeRefusal(url);
        if (shape != null) return new Refusal(shape, false);
        if (!route.direct(url)) return null;
        return lookedUp(url, true);
    }

    /**
     * The lookup half of {@link #refusal}: looks [url]'s host up and, with [fence], holds every
     * answer to being public. The addresses it lets through are the ones the connection goes to.
     * Package-visible so a test can look up a host of its own without the shape rules.
     */
    final Refusal lookedUp(URL url, boolean fence) {
        // Lowercase only, trailing dot kept: the name as the address gives it.
        String host = url.getHost().toLowerCase(Locale.US);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return new Refusal("its host does not resolve", true);
        } catch (Throwable t) {
            return new Refusal("its host could not be looked up", true);
        }
        if (addresses == null || addresses.length == 0) return new Refusal("its host does not resolve", true);

        for (InetAddress address : addresses) {
            if (address == null) return new Refusal("its host does not resolve", true);
            String reason = fence ? nonPublic(address) : null;
            if (reason != null) return new Refusal("its host resolves to " + reason, true);
        }
        InetAddress[][] into = VETTED.get();
        if (into != null) into[0] = addresses.clone();
        return null;
    }

    /**
     * How many lookups run at once: one for each save that can run at once (MediaSave's limit).
     * A resolver that never answers keeps its thread until it does, so this is also the most
     * threads lookups can ever hold.
     */
    private static final int LOOKUP_THREADS = 3;

    /** How many lookups may wait for a thread. One more is refused at once, as the network's. */
    private static final int LOOKUP_QUEUE = 6;

    /** How often a save waiting on its lookup asks whether it was cancelled. */
    private static final long LOOKUP_POLL_MS = 100;

    /**
     * The longest a save waits for one lookup, queued and running together, unless its own time
     * ends first. Android's own resolver gives up well before this. A test shortens it.
     */
    static final long LOOKUP_TIMEOUT_MS = 20_000;
    static volatile long lookupTimeoutMs = LOOKUP_TIMEOUT_MS;

    /**
     * Where {@link #vetWithin} and {@link #addressesWithin} look hosts up. Daemon threads that end
     * after 30 idle seconds, so nothing stays parked in Threads' process between saves.
     */
    private static final ThreadPoolExecutor LOOKUPS = lookups();

    private static ThreadPoolExecutor lookups() {
        AtomicInteger made = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "hushthreads-lookup-" + made.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(LOOKUP_QUEUE), factory, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * {@link #refusal}, worked out on a lookup thread while this one waits, so a save stuck on DNS
     * still ends at a cancel or at its own time. Answers the refusal, or the addresses the check
     * vetted, which are the only ones the connection may go to: it never looks the name up again
     * on the save's thread, where nothing could end the wait. A lookup given up on, a full queue
     * or a lookup that took too long answers as the network's refusal; the caller tells a cancel
     * or its own deadline apart by checking those first. {@link #await} has the bounds.
     */
    final Vetted vetWithin(URL url, BooleanSupplier stopped, long deadline) {
        try {
            return await(() -> vet(url), stopped, deadline, "host lookup");
        } catch (GaveUp e) {
            return new Vetted(new Refusal(e.getMessage(), true), null);
        }
    }

    /** {@link #refusal}, with the addresses it vetted. On a lookup thread. */
    private Vetted vet(URL url) {
        InetAddress[][] found = new InetAddress[1][];
        VETTED.set(found);
        try {
            Refusal refusal = refusal(url);
            return new Vetted(refusal, refusal == null ? found[0] : null);
        } finally {
            VETTED.remove();
        }
    }

    /**
     * Every address [host] resolves to, looked up on a lookup thread as {@link #vetWithin} does,
     * for a connection to a host no check looked up: a proxy given by name, and under
     * {@link #directAddressesWithin} the server itself. Nothing is fenced here. Throws when the
     * host doesn't resolve or the wait was given up on; the message names no host.
     */
    final InetAddress[] addressesWithin(String host, BooleanSupplier stopped, long deadline)
            throws UnknownHostException {
        InetAddress[] addresses;
        try {
            addresses = await(() -> resolver.resolve(host), stopped, deadline, "host lookup");
        } catch (GaveUp e) {
            throw new UnknownHostException(e.getMessage());
        }
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("its host does not resolve");
        return addresses.clone();
    }

    /**
     * {@link #addressesWithin} for [url]'s own host on a route straight to it that the check
     * didn't look up, a proxy list's DIRECT entry say. Where nothing but the phone's lookup is
     * between the socket and the answer ({@link #unproxied}), the answer has to be public, as on
     * a direct route in {@link #refusal}. Throws as {@link #addressesWithin} does, and when an
     * answer isn't public; the message names no host.
     */
    final InetAddress[] directAddressesWithin(URL url, BooleanSupplier stopped, long deadline)
            throws UnknownHostException {
        InetAddress[] addresses = addressesWithin(url.getHost().toLowerCase(Locale.US), stopped, deadline);
        if (!unproxied.direct(url)) return addresses;
        for (InetAddress address : addresses) {
            String reason = address == null ? "no address" : nonPublic(address);
            if (reason != null) throw new UnknownHostException("its host resolves to " + reason);
        }
        return addresses;
    }

    /** A wait for a network callback given up on, and why. */
    static final class GaveUp extends IOException {
        GaveUp(String reason) {
            super(reason);
        }
    }

    /**
     * Runs [work] on a lookup thread and waits for it until [deadline] on the monotonic clock or
     * {@link #lookupTimeoutMs}, whichever comes first, asking [stopped] every 100 ms.
     *
     * <p>The bound: at most {@link #LOOKUP_THREADS} lookups ever run, and a resolver that never
     * answers holds those threads, not new ones. Further lookups wait in a queue of
     * {@link #LOOKUP_QUEUE}, each for at most {@link #lookupTimeoutMs}, and leave it when given up
     * on. A callback left running ends when it answers, and nothing waits for it. Proxy selection
     * and failure reports share these bounds with host lookups. [operation] is a fixed label,
     * never a host, address or exception message.
     */
    static <T> T await(Callable<T> work, BooleanSupplier stopped, long deadline, String operation) throws GaveUp {
        if (stopped.getAsBoolean()) throw new GaveUp("the save stopped during its " + operation);
        if (Downloader.expired(deadline)) throw new GaveUp("its " + operation + " took too long");
        FutureTask<T> lookup = new FutureTask<>(work);
        try {
            LOOKUPS.execute(lookup);
        } catch (RejectedExecutionException e) {
            throw new GaveUp("too many " + operation + "s were waiting");
        }
        long wait = Math.min(TimeUnit.MILLISECONDS.toNanos(lookupTimeoutMs), deadline - System.nanoTime());
        long until = System.nanoTime() + Math.max(0L, wait);
        try {
            while (true) {
                if (stopped.getAsBoolean()) throw giveUp(lookup, "the save stopped during its " + operation);
                long left = until - System.nanoTime();
                if (left <= 0) throw giveUp(lookup, "its " + operation + " took too long");
                try {
                    return lookup.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(LOOKUP_POLL_MS)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                    // Not yet. Ask about a cancel again.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw giveUp(lookup, "the save stopped during its " + operation);
        } catch (ExecutionException e) {
            throw new GaveUp("its " + operation + " failed");
        }
    }

    private static GaveUp giveUp(FutureTask<?> lookup, String reason) {
        lookup.cancel(false);
        // Out of the queue if it never started, so a resolver that never answers can't fill it.
        LOOKUPS.remove(lookup);
        return new GaveUp(reason);
    }

    /** How many lookup threads there are now, for the tests that hold them. */
    static int lookupThreads() {
        return LOOKUPS.getPoolSize();
    }

    /** How many lookups are running now, for the tests that hold them. */
    static int lookupsRunning() {
        return LOOKUPS.getActiveCount();
    }

    /**
     * Whether Java's proxy settings send [url] through a proxy. On Android they carry the Wi-Fi
     * or global proxy, PAC included, so a proxied save never depends on the phone's own lookup.
     */
    static boolean proxied(URL url) {
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return false;
            // The URI the connection asks the selector about, so the two get one answer.
            for (Proxy proxy : selector.select(MediaConnection.routeOf(url))) {
                if (proxy != null && proxy.type() != Proxy.Type.DIRECT) return true;
            }
        } catch (Throwable ignored) {
            // No answer means no proxy we know of: the fence stays up.
        }
        return false;
    }

    /**
     * Why [url] can never be fetched, from its text alone, or {@code null}. No lookup, so the
     * ranking can use it on any thread to keep a foreign address from outranking Meta's.
     */
    static String shapeRefusal(String url) {
        if (url == null || url.isEmpty()) return "there is no address";
        try {
            return shapeRefusal(new URL(url));
        } catch (Throwable t) {
            return "it is not a well-formed address";
        }
    }

    static String shapeRefusal(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) return "it is not HTTPS";
        String authority = url.getAuthority();
        if (authority == null || authority.isEmpty()) return "it names no host";
        // A user name ahead of the host is how a foreign host hides behind a familiar one:
        // https://scontent.xx.fbcdn.net@example.com/ goes to example.com. A backslash is where
        // parsers disagree about where the host ends.
        if (url.getUserInfo() != null || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0) {
            return "it carries a user name";
        }
        if (url.getPort() != -1 && url.getPort() != 443) return "it names a port other than 443";

        String host = url.getHost();
        if (host == null || host.isEmpty()) return "it names no host";
        host = normalise(host);
        if (isLiteralAddress(host)) return "its host is a bare IP address";
        if (!isMetaMediaHost(host)) return "its host is not one of Meta's media servers";
        return null;
    }

    static boolean isMetaMediaHost(String host) {
        String name = normalise(host);
        for (String domain : META_MEDIA_DOMAINS) {
            if (name.equals(domain) || name.endsWith("." + domain)) return true;
        }
        return false;
    }

    /** Lowercase, and without the trailing dot a fully qualified name may carry. */
    private static String normalise(String host) {
        String name = host.toLowerCase(Locale.US);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name;
    }

    private static boolean isLiteralAddress(String host) {
        if (host.startsWith("[") || host.indexOf(':') >= 0) return true;
        // Four dotted numbers, or a single number, which some parsers read as an address.
        return host.matches("[0-9.]+") || host.matches("0x[0-9a-f.x]+");
    }

    /** What kind of non-public address this is, or {@code null} for a public one. */
    static String nonPublic(InetAddress address) {
        if (address.isAnyLocalAddress()) return "an unspecified address";
        if (address.isLoopbackAddress()) return "a loopback address";
        if (address.isLinkLocalAddress()) return "a link-local address";
        if (address.isSiteLocalAddress()) return "a private address";
        if (address.isMulticastAddress()) return "a multicast address";

        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) return nonPublicV4(b);
        if (address instanceof Inet6Address) return nonPublicV6(b);
        return "an address of an unknown kind";
    }

    private static String nonPublicV4(byte[] b) {
        int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF, a2 = b[2] & 0xFF;
        if (a0 == 0) return "an unspecified address";
        if (a0 == 10 || (a0 == 172 && (a1 & 0xF0) == 16) || (a0 == 192 && a1 == 168)) return "a private address";
        if (a0 == 100 && (a1 & 0xC0) == 64) return "a carrier-grade NAT address";
        if (a0 == 127) return "a loopback address";
        if (a0 == 169 && a1 == 254) return "a link-local address";
        if (a0 >= 224 && a0 <= 239) return "a multicast address";
        if (a0 >= 240) return "a reserved address";
        if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) return "a reserved address";
        if (a0 == 192 && a1 == 88 && a2 == 99) return "a reserved address";
        // 198.18.0.0/15 is let through: see FAKE_IP in the class notes.
        if (a0 == 198 && a1 == 51 && a2 == 100) return "a reserved address";
        if (a0 == 203 && a1 == 0 && a2 == 113) return "a reserved address";
        return null;
    }

    private static String nonPublicV6(byte[] b) {
        int first = b[0] & 0xFF;
        // The IPv6 fake-IP pools are let through, like 198.18.0.0/15: fc00::/18 (sing-box) and
        // fdfe:dcba:9876::/64 (Clash and mihomo, OpenClash's preset). The rest of fc00::/7 stays
        // private.
        boolean fakeIp = (first == 0xFC && b[1] == 0 && (b[2] & 0xC0) == 0)
                || (first == 0xFD && (b[1] & 0xFF) == 0xFE && (b[2] & 0xFF) == 0xDC && (b[3] & 0xFF) == 0xBA
                        && (b[4] & 0xFF) == 0x98 && (b[5] & 0xFF) == 0x76 && b[6] == 0 && b[7] == 0);
        if ((first & 0xFE) == 0xFC && !fakeIp) return "a private address";
        if (first == 0xFE && (b[1] & 0xC0) == 0x80) return "a link-local address";
        if (first == 0xFE && (b[1] & 0xC0) == 0xC0) return "a private address";
        if (first == 0xFF) return "a multicast address";
        if (first == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
            return "a reserved address";
        }

        // An IPv4 address carried inside an IPv6 one is held to the IPv4 rules: mapped
        // (::ffff:a.b.c.d), compatible (::a.b.c.d), NAT64 (64:ff9b::a.b.c.d) and 6to4 (2002:ab:cd::).
        boolean zeros80 = true;
        for (int i = 0; i < 10; i++) if (b[i] != 0) zeros80 = false;
        if (zeros80 && (((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) || (b[10] == 0 && b[11] == 0))) {
            return embedded(b, 12);
        }
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            return embedded(b, 12);
        }
        if (first == 0x20 && (b[1] & 0xFF) == 0x02) return embedded(b, 2);
        return null;
    }

    private static String embedded(byte[] b, int at) {
        byte[] v4 = { b[at], b[at + 1], b[at + 2], b[at + 3] };
        if (v4[0] == 0 && v4[1] == 0 && v4[2] == 0 && (v4[3] == 0 || v4[3] == 1)) {
            return v4[3] == 0 ? "an unspecified address" : "a loopback address";
        }
        return nonPublicV4(v4);
    }
}
