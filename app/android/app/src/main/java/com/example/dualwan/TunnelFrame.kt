package com.example.dualwan

import java.nio.ByteBuffer

// Client-side tunnel frame scaffold matching server framing
// Not yet wired; future milestones will use this for bonding tunnels.
data class TunnelFrame(
    val version: Int = 1,
    val flags: Int = 0,
    val streamId: Int,
    val seq: Long,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        // Minimal placeholder encoding: [DWNB][v][f][sid][seq][len][payload]
        val magic = byteArrayOf('D'.code.toByte(), 'W'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte())
        val len = payload.size
        val buf = ByteBuffer.allocate(4 + 1 + 1 + 4 + 8 + 4 + len).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(magic)
        buf.put(version.toByte())
        buf.put(flags.toByte())
        buf.putInt(streamId)
        buf.putLong(seq)
        buf.putInt(len)
        buf.put(payload)
        return buf.array()
    }
    companion object {
        fun decode(bytes: ByteArray): TunnelFrame? {
            val buf = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 4) return null
            val m0 = buf.get(); val m1 = buf.get(); val m2 = buf.get(); val m3 = buf.get()
            if (m0.toInt()!= 'D'.code || m1.toInt()!= 'W'.code || m2.toInt()!= 'N'.code || m3.toInt()!= 'B'.code) return null
            if (buf.remaining() < 1+1+4+8+4) return null
            val v = buf.get().toInt() and 0xFF
            val f = buf.get().toInt() and 0xFF
            val sid = buf.getInt()
            val seq = buf.getLong()
            val len = buf.getInt()
            if (len < 0 || buf.remaining() < len) return null
            val pl = ByteArray(len)
            buf.get(pl)
            return TunnelFrame(version = v, flags = f, streamId = sid, seq = seq, payload = pl)
        }
    }
}
