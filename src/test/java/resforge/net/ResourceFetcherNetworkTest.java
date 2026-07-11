package resforge.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceFetcherNetworkTest {
    private static final int LIMIT = 64 * 1024;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptsFixedAndChunkedResponsesAtExactLimit() throws Exception {
        byte[] expected = bytes(LIMIT);
        server.createContext("/fixed/test.res",
                exchange -> respond(exchange, expected, false));
        server.createContext("/chunked/test.res",
                exchange -> respond(exchange, expected, true));

        assertArrayEquals(expected, ResourceFetcher.fetch(base + "/fixed", "test", LIMIT));
        assertArrayEquals(expected, ResourceFetcher.fetch(base + "/chunked", "test", LIMIT));
    }

    @Test
    void rejectsOversizedFixedLengthFromHeaders() {
        server.createContext("/test.res", exchange -> {
            exchange.sendResponseHeaders(200, LIMIT + 1L);
            exchange.close();
        });

        IOException error = assertThrows(IOException.class,
                () -> ResourceFetcher.fetch(base, "test", LIMIT));
        assertTrue(errorMessage(error).contains("maximum download size"));
    }

    @Test
    void rejectsOversizedChunkedResponseWhileStreaming() {
        server.createContext("/test.res",
                exchange -> respond(exchange, bytes(LIMIT + 1), true));

        IOException error = assertThrows(IOException.class,
                () -> ResourceFetcher.fetch(base, "test", LIMIT));
        assertTrue(errorMessage(error).contains("maximum download size"));
    }

    @Test
    void rejectsEmptySuccessfulResponse() {
        server.createContext("/test.res",
                exchange -> respond(exchange, new byte[0], false));

        IOException error = assertThrows(IOException.class,
                () -> ResourceFetcher.fetch(base, "test", LIMIT));
        assertTrue(error.getMessage().contains("Empty response"));
    }

    @Test
    void reportsHttpErrorsWithoutBufferingTheirBodies() {
        byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
        server.createContext("/test.res", exchange -> {
            exchange.sendResponseHeaders(404, body.length);
            try(var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        IOException error = assertThrows(IOException.class,
                () -> ResourceFetcher.fetch(base, "test", LIMIT));
        assertTrue(error.getMessage().contains("Resource not found (404)"));
    }

    @Test
    void sendsEncodedSegmentsWithoutQueryOrFragmentInterpretation() throws Exception {
        AtomicReference<String> rawPath = new AtomicReference<>();
        byte[] body = {1};
        server.createContext("/", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, body, false);
        });

        assertArrayEquals(body,
                ResourceFetcher.fetch(base, "folder/na me/caf\u00e9", LIMIT));
        assertEquals("/folder/na%20me/caf%C3%A9.res", rawPath.get());
    }

    private static void respond(HttpExchange exchange, byte[] body, boolean chunked)
            throws IOException {
        exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
        try(var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        for(int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) i;
        return bytes;
    }

    private static String errorMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        for(Throwable current = error; current != null; current = current.getCause()) {
            if(current.getMessage() != null)
                message.append(current.getMessage()).append('\n');
        }
        return message.toString();
    }
}
