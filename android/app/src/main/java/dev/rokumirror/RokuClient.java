package dev.rokumirror;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds Rokus on the local network and talks to them over Roku's External Control Protocol. */
public final class RokuClient {
    public static final class Device {
        public final String ip;
        public final String name;

        Device(String ip, String name) {
            this.ip = ip;
            this.name = name;
        }
    }

    private RokuClient() {
    }

    /** SSDP search for Rokus. Blocks for about {@code timeoutMs}. */
    public static List<Device> discover(int timeoutMs) {
        Set<String> ips = new LinkedHashSet<>();
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "Host: 239.255.255.250:1900\r\n"
                + "Man: \"ssdp:discover\"\r\n"
                + "ST: roku:ecp\r\n"
                + "MX: 2\r\n\r\n";
        byte[] msg = search.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(400);
            InetSocketAddress group = new InetSocketAddress("239.255.255.250", 1900);
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    sock.send(new DatagramPacket(msg, msg.length, group));
                    nextSend = System.currentTimeMillis() + 1000;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                    Matcher m = Pattern.compile("(?im)^location:\\s*http://([0-9.]+):8060").matcher(reply);
                    if (m.find()) ips.add(m.group(1));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        List<Device> out = new ArrayList<>();
        for (String ip : ips) out.add(new Device(ip, deviceName(ip)));
        return out;
    }

    static String deviceName(String ip) {
        try {
            String xml = get("http://" + ip + ":8060/query/device-info");
            for (String tag : new String[]{"user-device-name", "friendly-device-name", "model-name"}) {
                Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
                if (m.find() && !m.group(1).trim().isEmpty()) return m.group(1).trim();
            }
        } catch (IOException ignored) {
        }
        return "Roku";
    }

    /**
     * Starts (or re-points) the sideloaded receiver channel at the given stream URL.
     * Throws with a readable message on failure.
     */
    public static void launchReceiver(String rokuIp, String streamUrl) throws IOException {
        String q = "url=" + URLEncoder.encode(streamUrl, "UTF-8");
        int code;
        try {
            code = post("http://" + rokuIp + ":8060/launch/dev?" + q);
        } catch (IOException e) {
            throw new IOException("Couldn't reach the Roku at " + rokuIp
                    + ". Check the IP and that both are on the same Wi-Fi.");
        }
        if (code == 403) {
            throw new IOException("The Roku refused control. On the Roku, set Settings > System > "
                    + "Advanced system settings > Control by mobile apps to Default or Permissive.");
        }
        if (code == 404) {
            throw new IOException("The receiver channel isn't installed on the Roku. "
                    + "Sideload roku-mirror-receiver.zip first (see the setup guide).");
        }
        if (code < 200 || code >= 300) {
            throw new IOException("The Roku answered HTTP " + code + " when launching the receiver.");
        }
    }

    /**
     * Experimental: hands the stream to Roku's built-in "Play on Roku" player (channel
     * 15985), which every Roku has, so no sideloaded channel is needed.
     */
    public static void launchBuiltInPlayer(String rokuIp, String streamUrl) throws IOException {
        String q = "t=v&u=" + URLEncoder.encode(streamUrl, "UTF-8")
                + "&k=(null)&videoName=" + URLEncoder.encode("Phone screen", "UTF-8")
                + "&videoFormat=hls";
        int code;
        try {
            code = post("http://" + rokuIp + ":8060/input/15985?" + q);
            if (code < 200 || code >= 300) {
                // Some models only accept it as a launch.
                code = post("http://" + rokuIp + ":8060/launch/15985?" + q);
            }
        } catch (IOException e) {
            throw new IOException("Couldn't reach the Roku at " + rokuIp
                    + ". Check the IP and that both are on the same Wi-Fi.");
        }
        if (code == 403) {
            throw new IOException("The Roku refused control. On the Roku, set Settings > System > "
                    + "Advanced system settings > Control by mobile apps to Default or Permissive.");
        }
        if (code < 200 || code >= 300) {
            throw new IOException("The Roku's built-in player answered HTTP " + code
                    + ". Turn off no-channel mode to use the receiver channel.");
        }
    }

    /** Sends a remote-control key such as "Home" or "Back". Errors are ignored. */
    public static void keypress(String rokuIp, String key) {
        try {
            post("http://" + rokuIp + ":8060/keypress/" + key);
        } catch (IOException ignored) {
        }
    }

    /**
     * The phone's own address on the network that reaches the Roku. Connecting a UDP
     * socket sends nothing but makes the OS pick the right interface.
     */
    public static String localAddressToward(String rokuIp) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(rokuIp), 8060);
            InetAddress a = s.getLocalAddress();
            if (a instanceof Inet4Address && !a.isAnyLocalAddress() && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        } catch (IOException ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (!ni.getName().toLowerCase(Locale.US).startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static int post(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
