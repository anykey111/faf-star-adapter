package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.faforever.iceadapter.debug.Debug.debug;
import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import java.io.ByteArrayOutputStream;


class FaPacket {
    public int remoteId;
    public byte[] data;
    public int length;

    public FaPacket(int remoteId, byte[] data, int length) {
        this.remoteId = remoteId;
        this.data = Arrays.copyOf(data, length);
        this.length = length;
    }
};

/**
 * Represents a game session and the current ICE status/communication with all peers
 * Is created by a JoinGame or HostGame event (via RPC), is destroyed by a gpgnet connection breakdown
 */
@Slf4j
public class GameSession extends Thread {

    @Getter
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();
    @Getter
    @Setter
    private volatile boolean gameEnded = false;

    static ConcurrentLinkedQueue<FaPacket> faPackets = new ConcurrentLinkedQueue<>();

    SocketAddress endpoint;
    Socket socket = null;
    LittleEndianDataInputStream in;
    LittleEndianDataOutputStream out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();

    int packetType;
    int packetLength;
    int packetFromId;
    int packetToId;

    public GameSession() {
    }

    public void init() throws Exception {
        String addr = System.getProperty("FAF_PROXY", "176.119.158.140");
        endpoint = new InetSocketAddress(addr, 7788);
        this.start();
    }

    /**
     * Initiates a connection to a peer (ICE)
     *
     * @return the port the ice adapter will be listening/sending for FA
     */
    public synchronized int connectToPeer(String remotePlayerLogin, int remotePlayerId, boolean offer) {
        Peer peer = new Peer(this, remotePlayerId, remotePlayerLogin, offer);
        peers.put(remotePlayerId, peer);
        debug().connectToPeer(remotePlayerId, remotePlayerLogin, offer);
        return peer.getFaSocket().getLocalPort();
    }

    /**
     * Disconnects from a peer (ICE)
     */
    public synchronized void disconnectFromPeer(int remotePlayerId) {
        Peer removedPeer = peers.remove(remotePlayerId);
        if (removedPeer != null) {
            removedPeer.close();
            debug().disconnectFromPeer(remotePlayerId);
        }
    }

    /**
     * Stops the connection to all peers and all ice agents
     */
    public void close() {
        log.info("Closing gameSession");
        peers.values().forEach(Peer::close);
        peers.clear();
    }

    void processAuth() throws IOException {
        byte[] data = IceAdapter.login.getBytes(StandardCharsets.UTF_8);
        log.info("send auth data gameId={} playerId={} data={}", IceAdapter.gameId, IceAdapter.id, data);
        buf.reset();
        LittleEndianDataOutputStream tmp = new LittleEndianDataOutputStream(buf);
        tmp.writeByte(0xF0);
        tmp.writeShort(data.length);
        tmp.writeInt(IceAdapter.id);
        tmp.writeInt(0);
        tmp.write(data, 0, data.length);
        tmp.flush();
        out.write(buf.toByteArray());
        byte[] resp = in.readNBytes(11);
        if (resp[0] != -16) {
            log.warn("auth response {}", resp[0]);
            throw new IOException("no confirmation form server");
        }
    }

    

    @Override
    public void run() {
        int numAttempts = 0;
        log.debug("start game session");
        while (IceAdapter.running && IceAdapter.gameSession == this) {
            if (socket == null || !socket.isConnected()) {
                try {
                    socket = new Socket();
                    log.debug("connect to the proxy server endpoint={} numAttempts={}", endpoint, numAttempts);
                    socket.connect(endpoint, 15000);
                    socket.setTcpNoDelay(true);
                    in = new LittleEndianDataInputStream(socket.getInputStream());
                    out = new LittleEndianDataOutputStream(socket.getOutputStream());
                    processAuth();
                    numAttempts = 0;
                    packetLength = 0;
                } catch (IOException e) {
                    log.warn("connection failed {}", e);
                    numAttempts += 1;
                    if (numAttempts > 30) {
                        log.error("giveup");
                        break;
                    }
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e2) {
                        break;
                    }
                    continue;
                }
            }
            try {
                processIncomingPackets();
                processOutgoingPackets();
                Thread.sleep(0);
            } catch (Exception e) {
                log.error("packet processing error {}, reconnect", e);
                numAttempts += 1;
                try {
                    socket.close();
                } catch (IOException e2) {
                    log.warn("socket close error {}", e2);
                }
                socket = null;
            }
        }
    }

    public void onFaDataReceived(int remoteId, byte[] data, int length) {
        //log.debug("recv packet remote_id={} len={}", remoteId, length);
        faPackets.add(new FaPacket(remoteId, data, length));
    }

    void processOutgoingPackets() throws IOException {
        FaPacket i;
        buf.reset();
        while ((i = faPackets.poll()) != null) {
            //log.debug("forward packet, len={} remoteId={}", i.length, i.remoteId);
            LittleEndianDataOutputStream tmp = new LittleEndianDataOutputStream(buf);
            tmp.writeByte(0xF4);
            tmp.writeShort(i.length);
            tmp.writeInt(IceAdapter.id);
            tmp.writeInt(i.remoteId);
            tmp.write(i.data, 0, i.length);
            tmp.flush();
        }
        if (buf.size() > 0) {
            out.write(buf.toByteArray());
        }
    }

    void processIncomingPackets() throws IOException {
        if (packetLength == 0 && in.available() >= 16) {
            packetType = in.readUnsignedByte();
            packetLength = in.readUnsignedShort();
            packetFromId = in.readInt();
            packetToId = in.readInt();
        }
        if (packetLength > 0 && in.available() >= packetLength) {
            //log.debug("packet type={} len={} from_id={} to_id={}", packetType, packetLength, packetFromId, packetToId);
            byte[] data = in.readNBytes(packetLength);
            int length = packetLength;
            packetLength = 0;
            if (packetType == 0xF4) {
                Peer peer = peers.get(packetFromId);
                if (peer == null) {
                    log.warn("peer not found, remoteId={}", packetFromId);
                } else {
                    peer.onIceDataReceived(data, 0, length);
                }
            } else {
                log.warn("unknown packet type {}", packetType);
            }
        }
    }

    @Getter
    private static final List<IceServer> iceServers = new ArrayList<>();

    /**
     * Set ice servers (to be used for harvesting candidates)
     * Called by the client via jsonRPC
     */
    public static void setIceServers(List<Map<String, Object>> iceServersData) {
        log.info("skip setIceServers");
    }
}
