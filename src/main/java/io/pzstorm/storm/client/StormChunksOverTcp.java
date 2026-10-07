package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.connection.StormChunkWire;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.Nullable;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.WorldStreamer;
import zombie.network.GameClient;
import zombie.network.PacketTypes;

/**
 * Client side of loading-phase chunk streaming over the Storm game-port TCP channel.
 *
 * <p>Flow: the advice on {@code RequestZipListPacket.write} calls {@link #stageForTcp} — if a TCP
 * session exists and the client is still loading ({@code !GameClient.playerConnectSent}), the
 * requests are snapshotted here and the UDP packet goes out empty. The advice on {@code
 * WorldStreamer.updateMain}'s exit calls {@link #dispatchStaged()} — deliberately <b>after</b>
 * {@code updateMain} published the requests to {@code sentRequests}, because a delivery applied
 * before that publish matches nothing and is silently discarded. A worker thread then fetches the
 * batches over TCP and hands every result to the vanilla {@code WorldStreamer.receiveChunkPart} /
 * {@code receiveNotRequired} methods, which keeps all vanilla bookkeeping (pending-request
 * retirement, cancel sweeps, {@code isBusy()} draining) intact.
 *
 * <p>Those two methods mutate a non-thread-safe list and are vanilla-called from the UdpEngine
 * thread, which can still deliver residual UDP traffic; {@link #RECEIVE_LOCK} serializes both
 * callers (the vanilla entry points are advice-wrapped with the same lock).
 *
 * <p>Fail-soft: any transport error marks the current session broken and stops diverting. The
 * requests the worker still owes an answer for were never sent to the server (their UDP packet went
 * out empty) and vanilla 42.20.4 has no per-request resend, only the 60-second no-progress abort in
 * {@code requestLargeAreaZip}, so they are handed back to the main thread and re-issued as a plain
 * {@code RequestZipList} under their original request numbers (see {@link #resendOverUdp}). Every
 * batch carries the session it was staged under, and a batch whose session is no longer current is
 * dropped instead of fetched or re-issued, so nothing from one connection reaches the next.
 */
public final class StormChunksOverTcp {

    /** Serializes vanilla UdpEngine-thread deliveries against the TCP worker's deliveries. */
    public static final ReentrantLock RECEIVE_LOCK = new ReentrantLock();

    /** Vanilla ccr batch size; the server endpoint enforces the same cap. */
    private static final int BATCH_SIZE = 20;

    /** Vanilla {@code MAX_CHUNK_SEND_TRIES}: retries for chunks the server is still loading. */
    private static final int MAX_RETRIES = 3;

    private static final long RETRY_DELAY_MILLIS = 250;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Requests per fallback packet; vanilla's own packet carries a whole update's worth. */
    private static final int UDP_RESEND_BATCH = 64;

    private static final int MAX_RESEND_FAILURES = 3;

    record Staged(int requestNumber, int wx, int wy, long crc, int attempts) {}

    /** Requests and the session they were staged under; they are moot once that session is gone. */
    record Batch(StormTcpChannel.Session session, List<Staged> requests) {}

    /** One TCP round trip: resolves what it can out of {@code outstanding}, or throws. */
    interface Fetcher {
        void fetch(List<Staged> batch, List<Staged> retries, Map<Integer, Staged> outstanding)
                throws Exception;
    }

    /** Client main thread only (staged inside updateMain, drained on its exit). */
    private static final List<Staged> staged = new ArrayList<>();

    /** Session {@link #staged} was filled under. Client main thread only. */
    private static @Nullable StormTcpChannel.Session stagedSession;

    private static final LinkedBlockingQueue<Batch> dispatchQueue = new LinkedBlockingQueue<>();

    /**
     * Requests the TCP path gave up on. Filled by the worker, drained on the client main thread by
     * {@link #dispatchStaged()}, the same thread vanilla sends its own {@code RequestZipList} from.
     */
    private static final ConcurrentLinkedQueue<Batch> udpResend = new ConcurrentLinkedQueue<>();

    /** Consecutive {@link #resendOverUdp} failures. Client main thread only. */
    private static int resendFailures;

    /** Session the transport last failed on; diversion stays off until a new session exists. */
    private static volatile @Nullable StormTcpChannel.Session brokenSession;

    @SuppressWarnings("unused")
    private static @Nullable Thread worker;

    private StormChunksOverTcp() {}

    /**
     * Snapshot the requests for TCP transfer instead of the UDP packet. Returns false — leaving the
     * vanilla packet write to run — unless a healthy TCP session exists and the client is still in
     * the loading phase. Called from the {@code RequestZipListPacket.write} advice on the client
     * main thread; must never throw into the woven method.
     */
    public static boolean stageForTcp(ArrayList<WorldStreamer.ChunkRequest> requests) {
        try {
            StormTcpChannel.Session session = StormTcpChannel.getSession();
            if (session == null
                    || session == brokenSession
                    || GameClient.instance.playerConnectSent) {
                return false;
            }
            stagedSession = session;
            for (WorldStreamer.ChunkRequest request : requests) {
                staged.add(
                        new Staged(
                                request.requestNumber,
                                request.chunk.wx,
                                request.chunk.wy,
                                request.crc,
                                0));
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to stage chunk requests for TCP; falling back to UDP", t);
            staged.clear();
            brokenSession = StormTcpChannel.getSession();
            return false;
        }
    }

    /**
     * Hand the staged requests to the worker. Called from the {@code WorldStreamer.updateMain} exit
     * advice — after the requests were published to {@code sentRequests}, so a fast TCP response
     * cannot race the publish. Must never throw into the woven method.
     */
    public static void dispatchStaged() {
        resendOverUdp();
        try {
            if (staged.isEmpty()) {
                return;
            }
            ensureWorker();
            dispatchQueue.add(new Batch(stagedSession, new ArrayList<>(staged)));
            staged.clear();
        } catch (Throwable t) {
            LOGGER.error("Failed to dispatch staged chunk requests; falling back to UDP", t);
            udpResend.add(new Batch(stagedSession, new ArrayList<>(staged)));
            staged.clear();
            brokenSession = StormTcpChannel.getSession();
        }
    }

    /**
     * Re-issue over UDP what the TCP path could not answer. The request numbers are unchanged, so
     * the replies retire the entries vanilla already holds in {@code sentRequests} / {@code
     * pendingRequests}; a reply for a number vanilla no longer tracks is ignored by its receive
     * path, so a duplicate costs one chunk of bandwidth and nothing else. Sent with {@code
     * endPacket} rather than {@code PacketType.send}: the client-side send limiter answers an
     * exceeded budget with a silent {@code cancelPacket()}, which would lose the requests a second
     * time. Requests staged under an earlier session are dropped: their numbers mean nothing to the
     * connection that replaced it. Must never throw into the woven method.
     */
    static void resendOverUdp() {
        if (udpResend.isEmpty()) {
            return;
        }
        StormTcpChannel.Session session = StormTcpChannel.getSession();
        List<Staged> lost = List.of();
        int from = 0;
        try {
            UdpConnection connection = GameClient.connection;
            if (connection == null) {
                // Disconnected; the requests died with the connection.
                udpResend.clear();
                return;
            }
            lost = drainResendQueue(session);
            if (lost.isEmpty()) {
                return;
            }
            PacketTypes.PacketType type = PacketTypes.PacketType.RequestZipList;
            for (; from < lost.size(); from += UDP_RESEND_BATCH) {
                List<Staged> slice =
                        lost.subList(from, Math.min(from + UDP_RESEND_BATCH, lost.size()));
                ByteBufferWriter writer = connection.startPacket();
                try {
                    type.doPacket(writer);
                    writeRequestList(writer.bb, slice);
                } catch (Throwable t) {
                    connection.cancelPacket();
                    throw t;
                }
                connection.endPacket(
                        type.packetPriority, type.packetReliability, type.orderingChannel);
            }
            resendFailures = 0;
            LOGGER.info("Re-issued {} chunk request(s) over UDP after a TCP failure", lost.size());
        } catch (Throwable t) {
            List<Staged> unsent = new ArrayList<>(lost.subList(from, lost.size()));
            if (++resendFailures < MAX_RESEND_FAILURES && session != null) {
                udpResend.add(new Batch(session, unsent));
                LOGGER.error(
                        "Failed to re-issue chunk requests over UDP; {} still queued",
                        unsent.size(),
                        t);
            } else {
                resendFailures = 0;
                LOGGER.error(
                        "Failed to re-issue chunk requests over UDP; giving up on {}",
                        unsent.size(),
                        t);
            }
        }
    }

    /** Empties {@link #udpResend}, oldest first, and returns what {@code current} still owes. */
    static List<Staged> drainResendQueue(@Nullable StormTcpChannel.Session current) {
        List<Staged> lost = new ArrayList<>();
        int stale = 0;
        for (Batch batch; (batch = udpResend.poll()) != null; ) {
            if (batch.session() == current) {
                lost.addAll(batch.requests());
            } else {
                stale += batch.requests().size();
            }
        }
        if (stale > 0) {
            LOGGER.info("Dropped {} chunk request(s) staged under a closed TCP session", stale);
        }
        return lost;
    }

    /** Body of a vanilla {@code RequestZipListPacket}: count, then 20 bytes per request. */
    static void writeRequestList(ByteBuffer out, List<Staged> requests) {
        out.putInt(requests.size());
        for (Staged request : requests) {
            out.putInt(request.requestNumber());
            out.putInt(request.wx());
            out.putInt(request.wy());
            out.putLong(request.crc());
        }
    }

    private static synchronized void ensureWorker() {
        if (worker == null) {
            Thread thread = new Thread(StormChunksOverTcp::run, "storm-chunk-tcp");
            thread.setDaemon(true);
            thread.start();
            worker = thread;
        }
    }

    private static void run() {
        while (true) {
            Batch batch;
            try {
                batch = dispatchQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            StormTcpChannel.Session session = batch.session();
            if (session != StormTcpChannel.getSession()) {
                continue;
            }
            try {
                process(
                        batch.requests(),
                        session,
                        (sub, retries, outstanding) ->
                                fetchBatch(session, sub, retries, outstanding));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Resolve one dispatched batch. Whatever is still unanswered when the transport fails or the
     * fetcher stops answering, or was dispatched before the breaker tripped and is only reached
     * now, goes to {@link #udpResend}.
     */
    static void process(List<Staged> batch, StormTcpChannel.Session session, Fetcher fetcher)
            throws InterruptedException {
        if (session == brokenSession) {
            udpResend.add(new Batch(session, batch));
            return;
        }
        Map<Integer, Staged> outstanding = new LinkedHashMap<>();
        for (Staged request : batch) {
            outstanding.put(request.requestNumber(), request);
        }
        try {
            List<Staged> retries = fetchAll(batch, outstanding, fetcher);
            while (!retries.isEmpty()) {
                Thread.sleep(RETRY_DELAY_MILLIS);
                retries = fetchAll(retries, outstanding, fetcher);
            }
            if (!outstanding.isEmpty()) {
                throw new IllegalStateException(
                        "chunk transfer left " + outstanding.size() + " request(s) unanswered");
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            brokenSession = session;
            udpResend.add(new Batch(session, new ArrayList<>(outstanding.values())));
            LOGGER.warn(
                    "Chunk transfer over TCP failed; re-issuing {} request(s) over UDP and staying"
                            + " on UDP for this session: {}",
                    outstanding.size(),
                    t.toString());
        }
    }

    /** Fetch in ccr-sized sub-batches; returns the requests the server asked to retry. */
    private static List<Staged> fetchAll(
            List<Staged> batch, Map<Integer, Staged> outstanding, Fetcher fetcher)
            throws Exception {
        List<Staged> retries = new ArrayList<>();
        for (int from = 0; from < batch.size(); from += BATCH_SIZE) {
            fetcher.fetch(
                    batch.subList(from, Math.min(from + BATCH_SIZE, batch.size())),
                    retries,
                    outstanding);
        }
        return retries;
    }

    private static void fetchBatch(
            StormTcpChannel.Session session,
            List<Staged> batch,
            List<Staged> retries,
            Map<Integer, Staged> outstanding)
            throws Exception {
        HttpRequest.Builder builder = StormTcpChannel.authenticatedRequest("/storm/game/chunks");
        if (builder == null || session != StormTcpChannel.getSession()) {
            throw new IllegalStateException("TCP session closed mid-transfer");
        }
        HttpResponse<byte[]> response =
                StormTcpChannel.send(
                        builder.header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(toJson(batch)))
                                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("chunk batch returned HTTP " + response.statusCode());
        }
        Map<Integer, Staged> byNumber = new LinkedHashMap<>();
        for (Staged request : batch) {
            byNumber.put(request.requestNumber(), request);
        }
        for (StormChunkWire.Entry entry : StormChunkWire.read(response.body())) {
            Staged request = byNumber.get(entry.requestNumber());
            if (request == null) {
                continue;
            }
            // Retired only once vanilla's receive path has returned: a request that threw on the
            // way in is re-issued, and a reply for an already-retired number is ignored.
            switch (entry.kind()) {
                case StormChunkWire.KIND_DATA -> {
                    applyData(entry.requestNumber(), entry.data());
                    outstanding.remove(entry.requestNumber());
                }
                case StormChunkWire.KIND_NOT_REQUIRED -> {
                    applyNotRequired(entry.requestNumber(), entry.sameOnServer());
                    outstanding.remove(entry.requestNumber());
                }
                case StormChunkWire.KIND_RETRY -> {
                    if (request.attempts() + 1 >= MAX_RETRIES) {
                        // Vanilla terminal verdict after 3 tries: sameOnServer = (crc == 0).
                        applyNotRequired(entry.requestNumber(), request.crc() == 0);
                        outstanding.remove(entry.requestNumber());
                    } else {
                        retries.add(
                                new Staged(
                                        request.requestNumber(),
                                        request.wx(),
                                        request.wy(),
                                        request.crc(),
                                        request.attempts() + 1));
                    }
                }
                default -> throw new IllegalStateException("unknown entry kind " + entry.kind());
            }
        }
    }

    private static String toJson(List<Staged> batch) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode requests = root.putArray("requests");
        for (Staged request : batch) {
            ObjectNode entry = requests.addObject();
            entry.put("requestNumber", request.requestNumber());
            entry.put("wx", request.wx());
            entry.put("wy", request.wy());
            entry.put("crc", request.crc());
        }
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Deliver a whole chunk as one synthetic {@code SentChunk} fragment. The vanilla receive path
     * is size-agnostic (fragmentation is a sender concern), so a single fragment covering the full
     * file exercises the exact vanilla bookkeeping: buffer allocation, {@code partsReceived},
     * pending-request retirement and the cancel sweep.
     */
    private static void applyData(int requestNumber, byte[] zip) {
        ByteBuffer buffer = ByteBuffer.allocate(24 + zip.length);
        buffer.putInt(requestNumber);
        buffer.putInt(1); // numChunks
        buffer.putInt(0); // chunkIndex
        buffer.putInt(zip.length); // fileSize
        buffer.putInt(0); // offset
        buffer.putInt(zip.length); // count
        buffer.put(zip);
        buffer.position(0);
        RECEIVE_LOCK.lock();
        try {
            // vanilla addIncoming moves sentRequests into pendingRequests before every receive
            WorldStreamer.instance.udpUpdate();
            WorldStreamer.instance.receiveChunkPart(new ByteBufferReader(buffer));
        } finally {
            RECEIVE_LOCK.unlock();
        }
    }

    /** Test hook. */
    static void reset() {
        staged.clear();
        stagedSession = null;
        dispatchQueue.clear();
        udpResend.clear();
        resendFailures = 0;
        brokenSession = null;
    }

    private static void applyNotRequired(int requestNumber, boolean sameOnServer) {
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.putInt(1); // count
        buffer.putInt(requestNumber);
        buffer.put((byte) (sameOnServer ? 1 : 0));
        buffer.position(0);
        RECEIVE_LOCK.lock();
        try {
            // vanilla addIncoming moves sentRequests into pendingRequests before every receive
            WorldStreamer.instance.udpUpdate();
            WorldStreamer.instance.receiveNotRequired(new ByteBufferReader(buffer));
        } finally {
            RECEIVE_LOCK.unlock();
        }
    }
}
