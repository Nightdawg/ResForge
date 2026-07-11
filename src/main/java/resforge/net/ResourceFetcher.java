package resforge.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Fetches a resource's bytes from a Haven &amp; Hearth resource server over HTTP,
 * matching the client's scheme: {@code <base>/<path>.res} (see
 * {@code haven.Resource.HttpSource}). The default base is the official server.
 */
public final class ResourceFetcher {
    /** The official Haven &amp; Hearth resource server (as used by the game client). */
    public static final String DEFAULT_BASE = "http://game.havenandhearth.com/res/";
    /** Maximum accepted response body. The largest resource in the validation corpus
     *  is under 5 MiB; this leaves ample compatibility margin while bounding memory. */
    public static final int MAX_RESOURCE_BYTES = 64 * 1024 * 1024;

    private static final byte[] EMPTY_BODY = new byte[0];

    private ResourceFetcher() {
    }

    /** Lazily-created, shared {@link HttpClient}. {@code HttpClient} is immutable and
     *  thread-safe, so one instance serves all fetches (reusing pooled connections and
     *  a single selector thread instead of spawning a fresh client — and its threads —
     *  per call). The holder idiom keeps it lazy, so the pure {@link #urlFor}/
     *  {@link #baseName} helpers don't start any threads. The client lives for the JVM
     *  lifetime; its threads are daemon, so it never blocks shutdown. */
    private static final class Holder {
        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Builds the full {@code .res} URL for a resource path under a base URL. */
    public static String urlFor(String base, String path) {
        if(base == null || base.isBlank())
            base = DEFAULT_BASE;
        base = base.strip();
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource base URL: " + base, e);
        }
        String scheme = baseUri.getScheme();
        if(scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || baseUri.getHost() == null)
            throw new IllegalArgumentException("Resource base must be an HTTP(S) URL: " + base);
        if(baseUri.getRawQuery() != null || baseUri.getRawFragment() != null)
            throw new IllegalArgumentException("Resource base must not contain a query or fragment");
        base = baseUri.toASCIIString();
        if(!base.endsWith("/"))
            base += "/";
        if(path == null)
            throw new IllegalArgumentException("Resource path is required");
        String p = path.strip().replace('\\', '/');
        while(p.startsWith("/"))
            p = p.substring(1);
        if(p.toLowerCase(Locale.ROOT).endsWith(".res"))
            p = p.substring(0, p.length() - 4);
        if(p.isEmpty())
            throw new IllegalArgumentException("Resource path is empty");

        String[] segments = p.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for(String segment : segments) {
            if(segment.isEmpty())
                throw new IllegalArgumentException("Resource path contains an empty segment");
            if(segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("Resource path contains a dot segment");
            if(!encoded.isEmpty())
                encoded.append('/');
            encoded.append(encodeSegment(segment));
        }
        return base + encoded + ".res";
    }

    private static String encodeSegment(String segment) {
        for(int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if(c == '?' || c == '#' || c == '%')
                throw new IllegalArgumentException(
                        "Resource path must not contain '?', '#', or '%'");
            if(Character.isISOControl(c))
                throw new IllegalArgumentException("Resource path must not contain control characters");
        }

        ByteBuffer bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(segment));
        } catch(CharacterCodingException e) {
            throw new IllegalArgumentException("Resource path contains invalid Unicode", e);
        }
        StringBuilder out = new StringBuilder();
        while(bytes.hasRemaining()) {
            int value = bytes.get() & 0xff;
            if((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '-'
                    || value == '.' || value == '_' || value == '~') {
                out.append((char) value);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** Downloads {@code <base>/<path>.res} and returns its bytes. */
    public static byte[] fetch(String base, String path) throws IOException, InterruptedException {
        return fetch(base, path, MAX_RESOURCE_BYTES);
    }

    static byte[] fetch(String base, String path, int maxBytes)
            throws IOException, InterruptedException {
        if(maxBytes < 1)
            throw new IllegalArgumentException("maxBytes must be positive");
        String url = urlFor(base, path);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ResForge")
                .GET()
                .build();
        HttpResponse<byte[]> resp = Holder.CLIENT.send(req, boundedBodyHandler(url, maxBytes));
        int code = resp.statusCode();
        if(code == 404)
            throw new IOException("Resource not found (404): " + url);
        if(code != 200)
            throw new IOException("HTTP " + code + " fetching " + url);
        byte[] body = resp.body();
        if(body == null || body.length == 0)
            throw new IOException("Empty response from " + url);
        return body;
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(String url, int maxBytes) {
        return info -> {
            if(info.statusCode() != 200)
                return HttpResponse.BodySubscribers.replacing(EMPTY_BODY);

            OptionalLong declaredLength;
            try {
                declaredLength = info.headers().firstValueAsLong("Content-Length");
            } catch(NumberFormatException e) {
                return new BoundedBodySubscriber(url, maxBytes, -1,
                        new IOException("Invalid Content-Length fetching " + url, e));
            }
            if(declaredLength.isPresent() && declaredLength.getAsLong() > maxBytes)
                return new BoundedBodySubscriber(url, maxBytes, -1, tooLarge(url, maxBytes));
            int expectedLength = declaredLength.isPresent()
                    ? (int) declaredLength.getAsLong()
                    : -1;
            return new BoundedBodySubscriber(url, maxBytes, expectedLength, null);
        };
    }

    private static IOException tooLarge(String url, int maxBytes) {
        return new IOException("Resource exceeds maximum download size of "
                + maxBytes + " bytes: " + url);
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final int expectedLength;
        private final String url;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private byte[] data;
        private int length;
        private Flow.Subscription subscription;

        BoundedBodySubscriber(String url, int maximumBytes, int expectedLength,
                              IOException initialFailure) {
            this.url = url;
            this.maximumBytes = maximumBytes;
            this.expectedLength = expectedLength;
            this.data = new byte[expectedLength >= 0 ? expectedLength
                    : Math.min(8192, maximumBytes)];
            if(initialFailure != null) {
                data = null;
                body.completeExceptionally(initialFailure);
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            if(subscription != null) {
                newSubscription.cancel();
                return;
            }
            subscription = newSubscription;
            if(body.isDone())
                subscription.cancel();
            else
                subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if(body.isDone())
                return;
            long incoming = 0;
            for(ByteBuffer buffer : buffers)
                incoming += buffer.remaining();
            if(incoming > maximumBytes - length) {
                fail(tooLarge(url, maximumBytes));
                return;
            }

            int required = length + (int) incoming;
            ensureCapacity(required);
            for(ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                buffer.get(data, length, count);
                length += count;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            data = null;
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            if(body.isDone())
                return;
            if(expectedLength >= 0 && length != expectedLength) {
                fail(new IOException("Incomplete HTTP response body: expected "
                        + expectedLength + " bytes, received " + length));
                return;
            }
            byte[] result = length == data.length ? data : Arrays.copyOf(data, length);
            data = null;
            body.complete(result);
        }

        private void ensureCapacity(int required) {
            if(required <= data.length)
                return;
            int capacity = data.length;
            while(capacity < required) {
                int grown = capacity + Math.max(1, capacity >>> 1);
                capacity = Math.min(maximumBytes, Math.max(required, grown));
            }
            data = Arrays.copyOf(data, capacity);
        }

        private void fail(IOException error) {
            data = null;
            if(subscription != null)
                subscription.cancel();
            body.completeExceptionally(error);
        }
    }

    /** The bare resource name from a path (the part after the last '/'), for display/saving. */
    public static String baseName(String path) {
        String p = path.strip().replace('\\', '/');
        while(p.endsWith("/"))
            p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        String name = (slash >= 0) ? p.substring(slash + 1) : p;
        if(name.toLowerCase().endsWith(".res"))
            name = name.substring(0, name.length() - 4);
        return name.isEmpty() ? "resource" : name;
    }
}
