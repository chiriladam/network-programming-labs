import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.util.Collections;

/**
 * The two UDP sockets of a peer.
 *
 *  groupSocket    bound to 0.0.0.0:<chat port>, shared by every peer on this
 *                 computer (SO_REUSEADDR, and SO_REUSEPORT where it exists).
 *                 It receives what is sent to everybody: the general channel,
 *                 JOIN, ONLINE, ALIVE, LEAVE. In multicast mode it has joined
 *                 the group 239.255.42.99. Broadcast and multicast datagrams
 *                 are delivered to EVERY socket bound to the port.
 *
 *  privateSocket  bound to 0.0.0.0:<a free port chosen by the OS>, unique to
 *                 this peer. Everything is SENT from it, so the source
 *                 endpoint of our datagrams (IP + this port) is our private
 *                 address: the others send HERE, TAKEN and private messages
 *                 there. A unicast datagram to a SHARED port would reach only
 *                 one of the sockets that share it, so private messages could
 *                 not go to the chat port.
 */
public final class Net {

    public static final String GROUP = "239.255.42.99";   // administratively scoped multicast

    public final int port;
    public final boolean broadcast;
    public final NetworkInterface iface;
    public final InetAddress localAddress;
    private final InetAddress everybody;                    // group or broadcast address
    public final DatagramSocket groupSocket;
    public final DatagramSocket privateSocket;
    private final ChatLog log;

    public Net(int port, boolean broadcast, String ifaceIp, ChatLog log) throws IOException {
        this.port = port;
        this.broadcast = broadcast;
        this.log = log;

        iface = chooseInterface(ifaceIp, broadcast);
        localAddress = ipv4Of(iface);
        everybody = broadcast ? broadcastAddressOf(iface) : InetAddress.getByName(GROUP);

        // ---- the shared socket on the chat port, on all the addresses (0.0.0.0)
        groupSocket = new DatagramSocket(null);                 // not bound yet
        groupSocket.setReuseAddress(true);                      // setsockopt SO_REUSEADDR
        if (groupSocket.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
            groupSocket.setOption(StandardSocketOptions.SO_REUSEPORT, true);   // macOS, Linux
        }
        groupSocket.bind(new InetSocketAddress(port));          // bind 0.0.0.0:port
        if (!broadcast) {
            groupSocket.joinGroup(new InetSocketAddress(InetAddress.getByName(GROUP), 0), iface);
        }

        // ---- our own socket, on a free port
        privateSocket = new DatagramSocket(new InetSocketAddress(0));
        privateSocket.setBroadcast(true);                       // setsockopt SO_BROADCAST
        if (!broadcast) {
            privateSocket.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface);
            privateSocket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true); // peers on this PC too
            privateSocket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1);     // stay in the LAN
        }
    }

    /** Our private endpoint, as the others see it. */
    public InetSocketAddress privateEndpoint() {
        return new InetSocketAddress(localAddress, privateSocket.getLocalPort());
    }

    public String everybodyDescription() {
        return (broadcast ? "broadcast " : "multicast ") + everybody.getHostAddress() + ":" + port;
    }

    /** One datagram for everybody: to the multicast group or the broadcast address. */
    public void sendToAll(Message m) throws IOException {
        send(m, new InetSocketAddress(everybody, port));
    }

    /** One datagram for one peer only. */
    public void send(Message m, InetSocketAddress to) throws IOException {
        byte[] data = m.toBytes();
        privateSocket.send(new DatagramPacket(data, data.length, to));
        log.sent(to, m.type, data.length);
    }

    public void close() {
        groupSocket.close();
        privateSocket.close();
    }

    // ------------------------------------------------------------------ choosing the network card

    /**
     * The network card to use: the one with the given IP, or else the best
     * real one (Wi-Fi / Ethernet), skipping virtual cards of VirtualBox,
     * Hyper-V / WSL, VMware, Docker and VPNs, which would send our broadcasts
     * and multicast into a virtual network where nobody is listening.
     */
    static NetworkInterface chooseInterface(String ip, boolean broadcast) throws IOException {
        if (ip != null) {
            NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
            if (ni == null) throw new SocketException("no network card has the address " + ip);
            return ni;
        }
        NetworkInterface best = null;
        int bestScore = -1;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
            if (!broadcast && !ni.supportsMulticast()) continue;
            String n = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
            if (n.matches(".*(virtualbox|vbox|vmware|vethernet|hyper-v|wsl|docker|bridge|utun|awdl|llw"
                    + "|tailscale|zerotier|vpn|tap|tun|bluetooth).*")) continue;
            InetAddress a = ipv4OrNull(ni);
            if (a == null) continue;
            if (broadcast && broadcastOrNull(ni) == null) continue;
            String s = a.getHostAddress();
            int score = s.startsWith("192.168.") ? 3 : s.startsWith("10.") ? 2 : a.isSiteLocalAddress() ? 1 : 0;
            if (score > bestScore) {
                best = ni;
                bestScore = score;
            }
        }
        if (best == null) {                     // no network: use the loopback card (one computer only)
            best = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        }
        return best;
    }

    private static InetAddress ipv4Of(NetworkInterface ni) throws SocketException {
        InetAddress a = ipv4OrNull(ni);
        if (a == null) throw new SocketException("the network card " + ni.getName() + " has no IPv4 address");
        return a;
    }

    private static InetAddress ipv4OrNull(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return a;
        }
        return null;
    }

    private static InetAddress broadcastOrNull(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) return ia.getBroadcast();
        }
        return null;
    }

    private static InetAddress broadcastAddressOf(NetworkInterface ni) throws IOException {
        InetAddress b = broadcastOrNull(ni);
        return b != null ? b : InetAddress.getByName("255.255.255.255");
    }
}
