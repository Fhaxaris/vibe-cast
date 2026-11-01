package dev.rokumirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP server that serves the live playlist and segments to the Roku. */
public final class MiniHttpServer {
    private final HlsSegmenter segmenter;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket socket;
    private volatile boolean running;
    private volatile int segmentsServed;

    /** How many video segments the TV has downloaded so far. */
    public int segmentsServed() {
        return segmentsServed;
    }

    public MiniHttpServer(HlsSegmenter segmenter) {
        this.segmenter = segmenter;
    }

    /** Binds to the first free port in [firstPort, firstPort + 10) and returns it. */
    public int start(int firstPort) throws IOException {
        IOException last = null;
        for (int port = firstPort; port < firstPort + 10; port++) {
            try {
                socket = new ServerSocket(port);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (socket == null) throw last;
        running = true;
        Thread t = new Thread(this::acceptLoop, "mirror-http");
        t.setDaemon(true);
        t.start();
        return socket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                return;     // pool shut down
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(10_000);
            c.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // headers are ignored
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            boolean head = "HEAD".equals(parts[0]);
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            OutputStream out = c.getOutputStream();
            if (path.endsWith(".m3u8")) {
                String list = segmenter.playlist(8_000);
                if (list == null) {
                    respond(out, 503, "text/plain", "starting".getBytes(StandardCharsets.US_ASCII), head);
                } else {
                    respond(out, 200, "application/vnd.apple.mpegurl",
                            list.getBytes(StandardCharsets.US_ASCII), head);
                }
            } else if (path.startsWith("/seg") && path.endsWith(".ts")) {
                byte[] data = null;
                try {
                    data = segmenter.segment(Integer.parseInt(path.substring(4, path.length() - 3)));
                } catch (NumberFormatException ignored) {
                }
                if (data == null) {
                    respond(out, 404, "text/plain", "gone".getBytes(StandardCharsets.US_ASCII), head);
                } else {
                    respond(out, 200, "video/mp2t", data, head);
                    if (!head) segmentsServed++;
                }
            } else {
                respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.US_ASCII), head);
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static void respond(OutputStream out, int code, String type, byte[] body, boolean head)
            throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : "Service Unavailable";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(body);
        out.flush();
    }
}
