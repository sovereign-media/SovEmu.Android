package media.sovereign.sovemu

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import media.sovereign.sovemu.core.IceServer
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.SessionDescription as Sdp
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** One process-wide WebRTC factory. */
object WebRtc {
    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}

/**
 * A connection to one game session: receive-only video and audio, plus the unordered,
 * no-retransmit `input` DataChannel. Signalling is one POST of the complete offer (non-trickle),
 * exactly as the web client does.
 */
class Stream(
    context: Context,
    private val api: SovEmuApi,
    private val sessionId: String,
    iceServers: List<IceServer>,
    private val controller: Controller,
    private val videoSink: VideoSink,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val iceGathered = CompletableDeferred<Unit>()
    private var pump: Job? = null
    @Volatile var suspended = false

    /** Recent input round trips in ms (input → applied on a frame → ack). */
    val inputRtts = ConcurrentLinkedDeque<Long>()

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGathered.complete(Unit)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onState(state)
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.addSink(videoSink)
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private val pc: PeerConnection = WebRtc.factory(context).createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers.map(::toIceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        },
        observer,
    ) ?: error("could not create a peer connection")

    private val channel: DataChannel

    init {
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        channel = pc.createDataChannel(
            Protocol.INPUT_CHANNEL,
            DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            },
        )
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val ack = Protocol.decodeAck(bytes) ?: return
                inputRtts.addLast((now32() - ack.clientTimeMs) and 0xffff_ffffL)
                while (inputRtts.size > 60) inputRtts.pollFirst()
            }
        })
    }

    private fun toIceServer(s: IceServer): PeerConnection.IceServer {
        val urls = when (val u = s.urls) {
            is JsonArray -> u.map { (it as JsonPrimitive).content }
            is JsonPrimitive -> listOf(u.content)
            else -> emptyList()
        }
        return PeerConnection.IceServer.builder(urls).apply {
            s.username?.let { setUsername(it) }
            s.credential?.let { setPassword(it) }
        }.createIceServer()
    }

    suspend fun connect() {
        val offer = suspendCoroutine<SessionDescription?> { cont ->
            pc.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) = cont.resume(null)
            }, MediaConstraints())
        } ?: error("could not create an offer")
        setLocal(offer)
        withTimeoutOrNull(1500) { iceGathered.await() }
        val local = pc.localDescription ?: offer
        val answer = api.offer(sessionId, Sdp("offer", local.description))
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
        pump = scope.launch { pumpInput() }
    }

    private suspend fun setLocal(sdp: SessionDescription) = suspendCoroutine { cont ->
        pc.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resume(Unit)
        }, sdp)
    }

    private suspend fun setRemote(sdp: SessionDescription) = suspendCoroutine { cont ->
        pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resume(Unit)
        }, sdp)
    }

    /** Sends the controller state on every change and at least every 100 ms; hotkeys likewise. */
    private suspend fun pumpInput() {
        var seq = 0
        var sent = -1
        var sentHotkeys = 0
        var lastSend = 0L
        while (scope.isActive) {
            if (channel.state() == DataChannel.State.OPEN) {
                val r = if (suspended) Controller.Reading(0, false, false) else controller.read()
                val hotkeys = (if (r.fastForward) 1 else 0) or (if (r.rewind) 2 else 0)
                val now = System.currentTimeMillis()
                if (r.buttons != sent || hotkeys != sentHotkeys || now - lastSend > 100) {
                    seq++
                    send(Protocol.encodeInput(seq, now32().toInt(), intArrayOf(r.buttons)))
                    if (hotkeys != sentHotkeys || hotkeys != 0) send(Protocol.encodeHotkeys(r.fastForward, r.rewind))
                    sent = r.buttons
                    sentHotkeys = hotkeys
                    lastSend = now
                }
            }
            delay(4)
        }
    }

    private fun send(bytes: ByteArray) {
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    fun close() {
        pump?.cancel()
        channel.close()
        pc.close()
        pc.dispose()
    }

    private fun now32(): Long = System.nanoTime() / 1_000_000 and 0xffff_ffffL
}

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
