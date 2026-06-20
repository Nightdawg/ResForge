package hafen.resedit.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches a resource's bytes from a Haven &amp; Hearth resource server over HTTP,
 * matching the client's scheme: {@code <base>/<path>.res} (see
 * {@code haven.Resource.HttpSource}). The default base is the official server.
 */
public final class ResourceFetcher {
    /** The official Haven &amp; Hearth resource server (as used by the game client). */
    public static final String DEFAULT_BASE = "http://game.havenandhearth.com/res/";

    private ResourceFetcher() {
    }

    /** Builds the full {@code .res} URL for a resource path under a base URL. */
    public static String urlFor(String base, String path) {
        if(base == null || base.isBlank())
            base = DEFAULT_BASE;
        base = base.strip();
        if(!base.endsWith("/"))
            base += "/";
        String p = path.strip().replace('\\', '/');
        while(p.startsWith("/"))
            p = p.substring(1);
        if(p.toLowerCase().endsWith(".res"))
            p = p.substring(0, p.length() - 4);
        return base + p + ".res";
    }

    /** Downloads {@code <base>/<path>.res} and returns its bytes. */
    public static byte[] fetch(String base, String path) throws IOException, InterruptedException {
        String url = urlFor(base, path);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "hafen-resedit")
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
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
