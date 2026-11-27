package media.sovereign.sovemu

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.util.Log
import android.view.Display
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import media.sovereign.sovemu.core.Capabilities
import media.sovereign.sovemu.core.DecoderSetup
import media.sovereign.sovemu.core.FrameLatency
import media.sovereign.sovemu.core.IceServer
import media.sovereign.sovemu.core.InputMixer
import media.sovereign.sovemu.core.Protocol
import media.sovereign.sovemu.core.ScreenMode
import media.sovereign.sovemu.core.StatsReading
import media.sovereign.sovemu.core.VideoDecoder
import media.sovereign.sovemu.core.graphql.type.ClientCapabilitiesInput
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.LowLatencyVideoDecoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** One process-wide WebRTC factory. */
object WebRtc {
    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null

    /**
     * Hears how the hardware decoder was configured. The factory is process-wide and one stream
     * plays at a time, so each [Stream] points this at itself while it is open.
     */
    @Volatile var onDecoder: ((DecoderSetup) -> Unit)? = null

    /**
     * Hears each encoded frame going into a decoder (render time ms, capture time ns); as
     * [onDecoder].
     */
    @Volatile var onFrameEntered: ((Long, Long) -> Unit)? = null

    fun factory(context: Context): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(
                context.applicationContext,
            ).createInitializationOptions(),
        )
        // A game's sound, not a call's: played as game audio on the low-latency output path,
        // in stereo. (WebRTC's default is a voice call's: mono, on the ordinary path.)
        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseLowLatency(true)
            .setUseStereoOutput(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoders)
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
            .also {
                factory = it
                // The factory holds the module now.
                audio.release()
            }
    }

    private val decoders by lazy {
        LowLatencyVideoDecoderFactory(
            egl.eglBaseContext,
            onConfigured = { setup ->
                // Once per stream, beside the stats lines: which decoder, and in which mode.
                Log.i(STATS_TAG, setup.logfmt())
                onDecoder?.invoke(setup)
            },
            onFrameEntered = { renderTimeMs, captureTimeNs ->
                onFrameEntered?.invoke(renderTimeMs, captureTimeNs)
            },
        )
    }

    /**
     * The decoder each codec would get, asked of the device once (it does not change while the app
     * runs).
     */
    private val decoderChoices by lazy { decoders.describe() }

    /**
     * What this device can decode and show, for the server (`sessionOffer`): the decoders
     * WebRTC would use here, and the screen as it is now (its mode can change between games).
     * Call it after [factory], off the main thread: the first call asks the device's codecs.
     */
    fun capabilities(context: Context): ClientCapabilitiesInput {
        val mode = context.getSystemService(
            DisplayManager::class.java,
        ).getDisplay(Display.DEFAULT_DISPLAY).mode
        val screen =
            ScreenMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate.toDouble())
        return Capabilities.document(decoderChoices, screen).also {
            Log.i(
                STATS_TAG,
                "capabilities decoders=\"${Capabilities.summary(
                    it,
                )}\" display=${screen.width}x${screen.height}@${screen.refreshHz}",
            )
        }
    }

    private const val STATS_TAG = "SovEmu.Stats"
}

/**
 * The most audio packets the jitter buffer may hold: 150 ms at the server's 10 ms a packet,
 * which holds the buffer's own target to about 110 ms.
 */
private const val AUDIO_BUFFER_MAX_PACKETS = 15

/**
 * A connection to one game session: receive-only video and audio, plus the unordered,
 * no-retransmit `input` DataChannel. Signalling is one POST of the complete offer (non-trickle),
 * exactly as the web client does.
 */
class Stream(
    context: Context,
    private val api: SovEmuApi,
    private val sessionId: String,
    /** This app, named in the offer for the server's sessions page ([ClientEvents.clientName]). */
    private val client: String,
    iceServers: List<IceServer>,
    private val controller: Controller,
    private val videoSink: VideoSink,
    /** Where the stream's own work runs (statistics, capability checks): off the main thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit,
) {
    private val scope = CoroutineScope(compute)
    private val appContext = context.applicationContext
    private val iceGathered = CompletableDeferred<Unit>()
    private var pump: Job? = null

    @Volatile var suspended = false

    /** Recent input round trips in ms (input → applied on a frame → ack). */
    val inputRtts = ConcurrentLinkedDeque<Long>()

    private val _decoder = MutableStateFlow<DecoderSetup?>(null)

    /**
     * How the hardware video decoder was configured (its name, low latency or not), once it has
     * been; stays null when the stream decodes in software from the start.
     */
    val decoder: StateFlow<DecoderSetup?> = _decoder.asStateFlow()

    private val decoderListener: (DecoderSetup) -> Unit = { _decoder.value = it }

    /** Button to frame, measured: acks against the frames that follow them. */
    val frameLatency = FrameLatency()

    /** Every decoded frame passes here on its way to [videoSink]: the measurement's frame side. */
    private val _firstFrame = MutableStateFlow(false)

    /**
     * A video frame has reached the renderer: the game's picture is up. The server's session is
     * ready long before that on a console whose BIOS boots for seconds, and the player keeps its
     * loading screen until then.
     */
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    private val frameEntered: (Long, Long) -> Unit = { renderTimeMs, captureTimeNs ->
        frameLatency.frameEntered(renderTimeMs, captureTimeNs, now32())
    }

    /** Debug builds only, when asked for: the decoded frames written to files ([FrameDump]). */
    private val frameDump = FrameDump.ifRequested(appContext)

    private val measuringSink = VideoSink { frame ->
        if (!_firstFrame.value) _firstFrame.value = true
        frameLatency.frameDelivered(frame.timestampNs, now32())
        frameDump?.onFrame(frame)
        videoSink.onFrame(frame)
    }

    private val _pipeline = MutableStateFlow<Protocol.Pipeline?>(null)

    /**
     * How the worker makes the stream, once it has said (`0x82`); null from a server that does
     * not. Re-sent every 2 s, but an equal message changes nothing.
     */
    val pipeline: StateFlow<Protocol.Pipeline?> = _pipeline.asStateFlow()

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGathered.complete(Unit)
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection $state")
            onState(state)
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.addSink(measuringSink)
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
            // Sound waits in a buffer that grows to smooth uneven arrival, and by default may
            // grow to hundreds of milliseconds (measured: 150-390 ms behind the picture). Cap
            // what it may hold, and let it drain quickly after a hiccup, so sound stays close
            // to the picture; the price is a click where the link really does stall.
            audioJitterBufferMaxPackets = AUDIO_BUFFER_MAX_PACKETS
            audioJitterBufferFastAccelerate = true
        },
        observer,
    ) ?: error("could not create a peer connection")

    private val channel: DataChannel

    init {
        WebRtc.onDecoder = decoderListener
        WebRtc.onFrameEntered = frameEntered
        val recvOnly = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
        )
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

            // The worker lets go of held input when this closes; say so, for the log.
            override fun onStateChange() {
                Log.i(TAG, "input channel ${channel.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val ack = Protocol.decodeAck(bytes)
                if (ack != null) {
                    frameLatency.ackArrived(ack.clientTimeMs, now32())
                    inputRtts.addLast((now32() - ack.clientTimeMs) and 0xffff_ffffL)
                    while (inputRtts.size > 60) inputRtts.pollFirst()
                    return
                }
                // Anything else this app does not know is ignored, for newer workers' sake.
                Protocol.decodePipeline(bytes)?.let { _pipeline.value = it }
            }
        })
    }

    private fun toIceServer(s: IceServer): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(s.urls).apply {
            s.username?.let { setUsername(it) }
            s.credential?.let { setPassword(it) }
        }.createIceServer()

    suspend fun connect() {
        val offer = suspendCoroutine<SessionDescription?> { cont ->
            pc.createOffer(
                object : SdpAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                    override fun onCreateFailure(error: String?) = cont.resume(null)
                },
                MediaConstraints(),
            )
        } ?: error("could not create an offer")
        setLocal(offer)
        withTimeoutOrNull(1500) { iceGathered.await() }
        val local = pc.localDescription ?: offer
        // The first call asks the device's codecs, so not on the main thread.
        val capabilities = withContext(compute) { WebRtc.capabilities(appContext) }
        val answer = api.offer(sessionId, local.description, client, capabilities)
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
        pump = scope.launch { pumpInput() }
    }

    private suspend fun setLocal(sdp: SessionDescription) = suspendCoroutine { cont ->
        pc.setLocalDescription(
            object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("Local description: $error"))
            },
            sdp,
        )
    }

    private suspend fun setRemote(sdp: SessionDescription) = suspendCoroutine { cont ->
        pc.setRemoteDescription(
            object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("Remote description: $error"))
            },
            sdp,
        )
    }

    /**
     * Sends every player's buttons on any change and at least every 100 ms; hotkeys likewise
     * (pause among them, held while the menu is open).
     * Ports in use only grow during a session, so a player who disconnects is sent released
     * rather than left holding whatever they last pressed.
     */
    private suspend fun pumpInput() {
        var seq = 0
        var count = 1
        var sent = emptyList<Int>()
        var sentHotkeys = 0
        var lastSend = 0L
        var analogSeq = 0
        var sentAnalog = listOf(Protocol.Analog.CENTRED)
        var lastAnalogSend = 0L
        while (scope.isActive) {
            if (channel.state() == DataChannel.State.OPEN) {
                // Suspended (the in-game menu is open): no input, and the game is paused.
                val paused = suspended
                val r = if (paused) IDLE else controller.read()
                count = maxOf(count, r.count)
                val buttons = r.ports.take(count)
                val analog = r.analog.take(count)
                val hotkeys =
                    (if (r.fastForward) 1 else 0) or (if (r.rewind) 2 else 0) or
                        (if (paused) 4 else 0)
                val now = System.currentTimeMillis()
                if (buttons != sent || hotkeys != sentHotkeys || now - lastSend > 100) {
                    seq++
                    send(Protocol.encodeInput(seq, now32().toInt(), buttons.toIntArray()))
                    if (hotkeys != sentHotkeys ||
                        hotkeys != 0
                    ) {
                        send(Protocol.encodeHotkeys(r.fastForward, r.rewind, paused))
                    }
                    sent = buttons
                    sentHotkeys = hotkeys
                    lastSend = now
                }
                // Sticks: on change, and as a heartbeat while any is off-centre.
                if (analog != sentAnalog ||
                    (analog.any { !it.centred } && now - lastAnalogSend > 100)
                ) {
                    analogSeq++
                    send(Protocol.encodeAnalog(analogSeq, analog))
                    sentAnalog = analog
                    lastAnalogSend = now
                }
            }
            // Until an input changes, or 4 ms: a press is sent as it happens, where a fixed wait
            // held it for up to 4 ms; the timer keeps the heartbeats and anything unsignalled.
            withTimeoutOrNull(4) { controller.changes.receive() }
        }
    }

    /**
     * The connection's stats now (for the worker and the stats overlay); null once the stream
     * is closed. Call it from the main thread, as [close] is: getStats on a disposed connection
     * crashes in native code.
     */
    suspend fun readStats(): StatsReading? {
        if (closed.get()) return null
        return suspendCancellableCoroutine { cont ->
            pc.getStats { report -> cont.resume(reading(report)) }
        }
    }

    private fun reading(report: RTCStatsReport): StatsReading {
        val all = report.statsMap.values
        fun Map<String, Any?>.num(key: String) = (this[key] as? Number)?.toDouble()
        val video = all.firstOrNull {
            it.type == "inbound-rtp" && it.members["kind"] == "video"
        }?.members.orEmpty()
        val audio = all.firstOrNull {
            it.type == "inbound-rtp" && it.members["kind"] == "audio"
        }?.members.orEmpty()
        val playout = all.firstOrNull { it.type == "media-playout" }?.members.orEmpty()
        val pair = all.firstOrNull {
            it.type == "candidate-pair" && it.members["nominated"] == true &&
                it.members["state"] == "succeeded"
        }?.members.orEmpty()
        return StatsReading(
            atMs = (report.timestampUs / 1000).toLong(),
            bytesReceived = video.num("bytesReceived") ?: 0.0,
            jitterBufferDelay = video.num("jitterBufferDelay") ?: 0.0,
            jitterBufferEmittedCount = video.num("jitterBufferEmittedCount") ?: 0.0,
            totalDecodeTime = video.num("totalDecodeTime") ?: 0.0,
            framesDecoded = video.num("framesDecoded") ?: 0.0,
            framesPerSecond = video.num("framesPerSecond"),
            width = video.num("frameWidth")?.toInt(),
            height = video.num("frameHeight")?.toInt(),
            packetsLost = (
                (
                    video.num(
                        "packetsLost",
                    ) ?: 0.0
                    ) + (audio.num("packetsLost") ?: 0.0)
                ).toLong(),
            networkRttMs = pair.num("currentRoundTripTime")?.let { it * 1000 },
            decoder = (video["decoderImplementation"] as? String)?.let {
                VideoDecoder(it, video["powerEfficientDecoder"] as? Boolean)
            },
            framesDropped = video.num("framesDropped")?.toLong(),
            audioJitterBufferDelay = audio.num("jitterBufferDelay") ?: 0.0,
            audioJitterBufferTargetDelay = audio.num("jitterBufferTargetDelay") ?: 0.0,
            audioJitterBufferEmittedCount = audio.num("jitterBufferEmittedCount") ?: 0.0,
            audioPlayoutDelay = playout.num("totalPlayoutDelay") ?: 0.0,
            audioPlayoutSamples = playout.num("totalSamplesCount") ?: 0.0,
        )
    }

    /** This client's view of the stream, for the worker ([StreamStats.encode]). */
    fun sendStats(bytes: ByteArray) {
        if (!closed.get() && channel.state() == DataChannel.State.OPEN) send(bytes)
    }

    private fun send(bytes: ByteArray) {
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ends the stream. Safe to call more than once (Quit closes it, then leaving the player
     * does): closing or disposing a PeerConnection twice crashes in native code.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (WebRtc.onDecoder === decoderListener) WebRtc.onDecoder = null
        if (WebRtc.onFrameEntered === frameEntered) WebRtc.onFrameEntered = null
        frameDump?.close()
        // The pump sends on the channel from another thread: it must have stopped before the
        // channel goes (sending on a disposed one crashes in native code). One loop is ~4 ms.
        runBlocking { pump?.cancelAndJoin() }
        scope.cancel()
        channel.unregisterObserver()
        channel.close()
        channel.dispose()
        pc.close()
        pc.dispose()
    }

    private fun now32(): Long = System.nanoTime() / 1_000_000 and 0xffff_ffffL

    private companion object {
        const val TAG = "SovEmu.Stream"

        /** Nothing held: what the game gets while the menu is open. */
        val IDLE = InputMixer.Reading(
            ports = List(Protocol.MAX_PORTS) { 0 },
            analog = List(Protocol.MAX_PORTS) { Protocol.Analog.CENTRED },
            count = 1,
            fastForward = false,
            rewind = false,
            menu = false,
        )
    }
}

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
