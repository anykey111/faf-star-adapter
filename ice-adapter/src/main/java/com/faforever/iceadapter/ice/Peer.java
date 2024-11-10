package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;

import java.io.ByteArrayInputStream;
import com.google.common.io.LittleEndianDataInputStream;

/**
 * Represents a peer in the current game session which we are connected to
 */
@Getter
@Slf4j
public class Peer {

    private final GameSession gameSession;

    private final int remoteId;
    private final String remoteLogin;
    private final boolean localOffer;//Do we offer or are we waiting for a remote offer

    private PeerIceModule ice = new PeerIceModule(this);
    private DatagramSocket faSocket;//Socket on which we are listening for FA / sending data to FA

    @Getter private int resendCounter = 0;
    @Getter private int numIncomingPackets = 0;
    @Getter private int totalSize = 0;
    
    int seqPtr = 0;
    long[] seqHist = new long[10];

    public Peer(GameSession gameSession, int remoteId, String remoteLogin, boolean localOffer) {
        this.gameSession = gameSession;
        this.remoteId = remoteId;
        this.remoteLogin = remoteLogin;
        this.localOffer = localOffer;

        log.debug("Peer created: {}, {}, localOffer: {}", remoteId, remoteLogin, String.valueOf(localOffer));

        initForwarding();

        if (localOffer) {
            new Thread(ice::initiateIce).start();
        }
    }

    /**
     * Starts waiting for data from FA
     */
    private void initForwarding() {
        try {
            faSocket = new DatagramSocket(0);
        } catch (SocketException e) {
            log.error("Could not create socket for peer: {}", getPeerIdentifier(), e);
        }

        new Thread(this::faListener).start();

        log.debug("Now forwarding data to peer {}", getPeerIdentifier());
    }

    /**
     * Forwards data received on ICE to FA
     * @param data
     * @param offset
     * @param length
     */
    synchronized void onIceDataReceived(byte data[], int offset, int length) {
        try {
            DatagramPacket packet = new DatagramPacket(data, offset, length, InetAddress.getByName("127.0.0.1"), IceAdapter.LOBBY_PORT);
            faSocket.send(packet);
            inspectPacket(data, offset, length, true);
            numIncomingPackets += 1;
            totalSize += length;
        } catch (UnknownHostException e) {
        } catch (IOException e) {
            log.error("Error while writing to local FA as peer (probably disconnecting from peer) " + getPeerIdentifier(), e);
            return;
        }
    }

    /**
     * This method get's invoked by the thread listening for data from FA
     */
    private void faListener() {
        byte data[] = new byte[65536];//64KiB = UDP MTU, in practice due to ethernet frames being <= 1500 B, this is often not used
        while (IceAdapter.running && IceAdapter.gameSession == gameSession) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length);
                faSocket.receive(packet);
                ice.onFaDataReceived(data, packet.getLength());
                inspectPacket(data, 0, packet.getLength(), false);
            } catch (IOException e) {
                log.debug("Error while reading from local FA as peer (probably disconnecting from peer) " + getPeerIdentifier(), e);
                return;
            }
        }
        log.debug("No longer listening for messages from FA");
    }

    public volatile boolean closing = false;
    public void close() {
        if(closing) {
            return;
        }

        log.info("Closing peer for player {}", getRemoteId());

        closing = true;
        if(faSocket != null) {
            faSocket.close();
        }

        if(ice != null) {
            ice.close();
        }
    }

    /**
     * @return %username%(%id%)
     */
    public String getPeerIdentifier() {
        return String.format("%s(%d)", this.remoteLogin, this.remoteId);
    }

    private void inspectPacket(byte[] data, int offset, int length, boolean incoming) {
        try {
            if (length >= 11) {
                LittleEndianDataInputStream ls = new LittleEndianDataInputStream(new ByteArrayInputStream(data, offset, length));
                int ty = ls.readByte(); // type
                int mask = ls.readInt(); // mask
                if (mask > 0) {
                    // bitmask indicate that of the data segments is lost
                    resendCounter += 1;
                }
                // when ACK confirmation lost the game resend DAT packet using previous `seq` and `expected` values
                if (incoming || ty != 4) {
                    return;
                }
                ls.readUnsignedShort(); // ser
                ls.readUnsignedShort(); // irt
                int seq = ls.readUnsignedShort();
                int expected = ls.readUnsignedShort();                
                long val = (long)seq << 16 | expected;
                boolean valExists = false;
                for (int i = 0; i < seqHist.length; ++i) {
                    if (seqHist[i] == val) {
                        valExists = true;
                        break;
                    }
                }
                if (valExists) {
                    resendCounter += 1;
                } else {
                    seqHist[seqPtr] = val;
                    seqPtr = (seqPtr + 1) % seqHist.length;
                }
            }
        } catch (IOException e) {
            // ignore exception
        }
    }
}
