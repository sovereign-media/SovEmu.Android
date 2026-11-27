package media.sovereign.sovemu.core

import java.util.Locale
import kotlin.math.roundToLong

// Where this client's link goes and how it was measured (#243), as the web client's
// (SovEmu.Web/src/lib/link.ts) and worded the same: the path it reports to the worker, the
// stats overlay's Network rows, and the loading screen's "Home network" / "Remote".

/**
 * One end of the selected ICE candidate pair, as WebRTC's stats report it (`local-candidate`,
 * `remote-candidate`): [type] `host`, `srflx`, `prflx` or `relay`; [protocol] the transport to
 * the peer; [relayProtocol] the transport to the TURN server, for a local relay candidate.
 */
data class Candidate(
    val type: String,
    val protocol: String? = null,
    val relayProtocol: String? = null,
) {
    val relayed: Boolean get() = type == "relay"
}

/**
 * The path this client reports in its stats (`ClientStats.path`): through the relay over
 * `relay-udp`, `relay-tcp` or `relay-tls` when its own end of the pair is a relay candidate,
 * else `direct`. Only the client can tell the relay's transports apart. Null before ICE has
 * chosen a pair.
 */
fun clientPath(local: Candidate?): String? {
    local ?: return null
    if (!local.relayed) return "direct"
    val protocol = (local.relayProtocol ?: "udp").lowercase(Locale.ROOT)
    return "relay-${if (protocol == "tcp" || protocol == "tls") protocol else "udp"}"
}

private val CANDIDATES = mapOf(
    "host" to "host",
    "srflx" to "STUN",
    "prflx" to "STUN",
    "relay" to "relay",
)

/** `host → host`, `STUN → host`, `relay → host`: the selected pair's candidate types. */
fun pairLabel(local: Candidate?, remote: Candidate?): String? {
    val l = local?.let { CANDIDATES[it.type] } ?: return null
    val r = remote?.let { CANDIDATES[it.type] } ?: return null
    return "$l → $r"
}

/** `UDP`, or `TCP to the relay` / `TLS to the relay` when relayed. */
fun transportLabel(local: Candidate?): String? {
    local ?: return null
    if (local.relayed) {
        return "${(local.relayProtocol ?: "udp").uppercase(
            Locale.ROOT,
        )} to the relay"
    }
    return (local.protocol ?: "udp").uppercase(Locale.ROOT)
}

/** The loading screen's word for where the player is: `Home network` on `lan`, else `Remote`. */
fun networkLabel(path: String): String? = when (path) {
    "" -> null
    "lan" -> "Home network"
    else -> "Remote"
}

/** `18.2 Mbit/s`, or `850 kbit/s` under a megabit. */
fun rateLabel(kbps: Int): String = if (kbps >= 1000) {
    String.format(Locale.ROOT, "%.1f Mbit/s", kbps / 1000.0)
} else {
    "${kbps.toDouble().roundToLong()} kbit/s"
}

private val STOPPED = mapOf(
    "ceiling" to "reached the ceiling",
    "steady" to "settled",
    "trouble" to "the link filled",
    "budget" to "out of time",
    "unavailable" to "could not measure",
    "off" to "turned off",
)

/** `18.2 Mbit/s in 0.6 s (reached the ceiling)`, `not measured`. */
fun measuredLabel(link: Protocol.Link?): String {
    if (link == null || !link.done || link.stoppedBy == "off" ||
        link.stoppedBy == "unavailable" || link.estimateKbps == 0
    ) {
        return if (link?.stoppedBy == "off") "not measured (turned off)" else "not measured"
    }
    val why = STOPPED[link.stoppedBy] ?: link.stoppedBy
    val seconds = String.format(Locale.ROOT, "%.1f", link.elapsedMs / 1000.0)
    return "${rateLabel(link.estimateKbps)} in $seconds s ($why)"
}

/**
 * The stats overlay's Network rows: where the link goes and through what (this app's own view,
 * shown with any worker), then what the worker measured before the stream started and
 * estimates now (blank with an older worker).
 */
fun networkRows(
    local: Candidate?,
    remote: Candidate?,
    link: Protocol.Link?,
    estimateKbps: Int,
): List<Pair<String, String>> = buildList {
    networkLabel(link?.path ?: "")?.let { add("Network" to it) }
    local?.let { add("TURN server" to if (it.relayed) "Yes" else "No") }
    transportLabel(local)?.let { add("Transport" to it) }
    pairLabel(local, remote)?.let { add("Path" to it) }
    add("Measured at start" to measuredLabel(link))
    if (link != null && link.done && link.startKbps != 0) {
        add("Started at" to rateLabel(link.startKbps))
    }
    if (estimateKbps != 0) add("Estimate now" to rateLabel(estimateKbps))
}
