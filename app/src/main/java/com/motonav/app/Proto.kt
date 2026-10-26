package com.motonav.app

/**
 * Moto BLE Navigation Protocol v1 —— 手机侧实现
 *
 * 规范来源：moto-gps-waveshare / shared/protocol/ble-navigation-v1.md
 *
 * 帧格式（全部小端）：
 *   0      u8  magic = 0xB7
 *   1      u8  protocol_version = 1
 *   2      u8  message_type
 *   3      u8  flags (bit0 START, bit1 END, bit2 ACK_REQUESTED, bit3 URGENT)
 *   4-5    u16 sequence
 *   6-7    u16 fragment_offset
 *   8-9    u16 message_length
 *   10..   u8[] fragment_payload
 *   末尾2  u16 CRC16 (对偏移 0..9+N，低字节先发)
 *
 * CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, refin/refout=false, xorout=0
 * 标准检查串 "123456789" → 0x29B1
 */
object Proto {

    const val MAGIC = 0xB7
    const val VERSION = 1
    const val HEADER_SIZE = 10
    const val CRC_SIZE = 2

    // 消息类型
    const val MSG_CONNECTION_STATUS = 0x01
    const val MSG_HEARTBEAT = 0x02
    const val MSG_ACK = 0x03
    const val MSG_NAV_SNAPSHOT = 0x10
    const val MSG_ROUTE_GEOMETRY = 0x11
    const val MSG_TRAFFIC_DEVIATION = 0x12
    const val MSG_MEDIA_STATE = 0x13
    const val MSG_MAP_SCENE = 0x14
    const val MSG_DEVICE_COMMAND = 0x20

    // 帧标志
    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val FLAG_ACK_REQUESTED = 0x04
    const val FLAG_URGENT = 0x08

    // 角色
    const val ROLE_PHONE = 1
    const val ROLE_DEVICE = 2

    // 连接状态
    const val STATE_STARTING = 0
    const val STATE_READY = 1
    const val STATE_DEGRADED = 2
    const val STATE_CLOSING = 3

    // 能力位
    const val CAP_NAVIGATION = 1 shl 0
    const val CAP_ROUTE_GEOMETRY = 1 shl 1
    const val CAP_TRAFFIC = 1 shl 2
    const val CAP_MEDIA_STATE = 1 shl 3
    const val CAP_TOUCH_COMMANDS = 1 shl 4
    const val CAP_MUSIC_COMMANDS = 1 shl 5
    const val CAP_COMMAND_ACK = 1 shl 6
    const val CAP_MAP_SCENE = 1 shl 7

    // 导航状态
    const val NAV_IDLE = 0
    const val NAV_ACQUIRING = 1
    const val NAV_PLANNING = 2
    const val NAV_NAVIGATING = 3
    const val NAV_REROUTING = 4
    const val NAV_ARRIVED = 5

    // 网络
    const val NET_OFFLINE = 0
    const val NET_CONNECTING = 1
    const val NET_ONLINE = 2

    // 显示页
    const val PAGE_NAVIGATION = 0
    const val PAGE_SPEED = 1
    const val PAGE_COMPASS = 2
    const val PAGE_MUSIC = 3

    // 交通
    const val TRAFFIC_UNKNOWN = 0
    const val TRAFFIC_FREE = 1
    const val TRAFFIC_SLOW = 2
    const val TRAFFIC_CONGESTED = 3
    const val TRAFFIC_SEVERE = 4

    // Snapshot flags
    const val SNAP_HAS_DESTINATION = 1 shl 0
    const val SNAP_HAS_FIX = 1 shl 1
    const val SNAP_GNSS_STALE = 1 shl 2
    const val SNAP_OFF_ROUTE = 1 shl 3
    const val SNAP_HAS_NEXT_MANEUVER = 1 shl 4
    const val SNAP_ROUTE_REQUEST_IN_FLIGHT = 1 shl 5
    const val SNAP_TRAFFIC_REQUEST_IN_FLIGHT = 1 shl 6
    const val SNAP_HAS_ROUTE_VIEW = 1 shl 7

    /** CRC-16/CCITT-FALSE */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (k in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /** 自检：必须返回 0x29B1 */
    fun selfTest(): Boolean {
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        return crc16(check, 0, check.size) == 0x29B1
    }

    /** 一个解析后的帧 */
    data class Frame(
        val type: Int,
        val flags: Int,
        val sequence: Int,
        val fragmentOffset: Int,
        val messageLength: Int,
        val payload: ByteArray,
    ) {
        val isStart: Boolean get() = (flags and FLAG_START) != 0
        val isEnd: Boolean get() = (flags and FLAG_END) != 0
    }

    /** 组一个完整帧（不含分片逻辑，payload 必须是完整逻辑消息的一段） */
    fun encodeFrame(
        type: Int,
        flags: Int,
        sequence: Int,
        fragmentOffset: Int,
        messageLength: Int,
        payload: ByteArray,
    ): ByteArray {
        val n = payload.size
        val buf = ByteArray(HEADER_SIZE + n + CRC_SIZE)
        buf[0] = MAGIC.toByte()
        buf[1] = VERSION.toByte()
        buf[2] = type.toByte()
        buf[3] = flags.toByte()
        putU16(buf, 4, sequence)
        putU16(buf, 6, fragmentOffset)
        putU16(buf, 8, messageLength)
        System.arraycopy(payload, 0, buf, HEADER_SIZE, n)
        val crc = crc16(buf, 0, HEADER_SIZE + n)
        buf[HEADER_SIZE + n] = (crc and 0xFF).toByte()          // 低字节先发
        buf[HEADER_SIZE + n + 1] = ((crc ushr 8) and 0xFF).toByte()
        return buf
    }

    /** 把一条逻辑消息切成若干帧；maxValue 是 GATT value 最大字节数 */
    fun fragment(
        type: Int,
        sequence: Int,
        message: ByteArray,
        maxValue: Int,
        ackRequested: Boolean = false,
        urgent: Boolean = false,
    ): List<ByteArray> {
        val maxPayload = (maxValue - HEADER_SIZE - CRC_SIZE).coerceAtLeast(1)
        val total = message.size
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < total || chunks.isEmpty()) {
            val n = minOf(maxPayload, total - offset)
            var flags = 0
            if (offset == 0) flags = flags or FLAG_START
            if (offset + n >= total) flags = flags or FLAG_END
            if (ackRequested) flags = flags or FLAG_ACK_REQUESTED
            if (urgent) flags = flags or FLAG_URGENT
            val piece = ByteArray(n)
            System.arraycopy(message, offset, piece, 0, n)
            chunks.add(encodeFrame(type, flags, sequence, offset, total, piece))
            offset += n
            if (n == 0) break
        }
        return chunks
    }

    /** 解析收到的帧；返回 null 表示格式或 CRC 错误 */
    fun decodeFrame(raw: ByteArray): Frame? {
        if (raw.size < HEADER_SIZE + CRC_SIZE) return null
        if ((raw[0].toInt() and 0xFF) != MAGIC) return null
        if ((raw[1].toInt() and 0xFF) != VERSION) return null
        val n = raw.size - HEADER_SIZE - CRC_SIZE
        val expect = crc16(raw, 0, HEADER_SIZE + n)
        val got = (raw[HEADER_SIZE + n].toInt() and 0xFF) or
                  ((raw[HEADER_SIZE + n + 1].toInt() and 0xFF) shl 8)
        if (expect != got) return null
        val payload = ByteArray(n)
        System.arraycopy(raw, HEADER_SIZE, payload, 0, n)
        return Frame(
            type = raw[2].toInt() and 0xFF,
            flags = raw[3].toInt() and 0xFF,
            sequence = getU16(raw, 4),
            fragmentOffset = getU16(raw, 6),
            messageLength = getU16(raw, 8),
            payload = payload,
        )
    }

    // ---------- 消息负载编码 ----------

    /** ConnectionStatus，固定 17 字节 */
    fun connectionStatus(
        role: Int,
        state: Int,
        capabilities: Int,
        sessionId: Int,
        maxFrameSize: Int,
        heartbeatMs: Int,
    ): ByteArray {
        val b = ByteArray(17)
        b[0] = 1                                   // revision
        b[1] = role.toByte()
        b[2] = state.toByte()
        b[3] = 1                                   // minimum_version
        b[4] = 1                                   // maximum_version
        putU32(b, 5, capabilities)
        putU32(b, 9, sessionId)
        putU16(b, 13, maxFrameSize)
        putU16(b, 15, heartbeatMs)
        return b
    }

    /** Heartbeat，固定 11 字节 */
    fun heartbeat(sessionId: Int, monotonicMs: Int): ByteArray {
        val b = ByteArray(11)
        b[0] = 1
        putU32(b, 1, sessionId)
        putU32(b, 5, monotonicMs)
        putU16(b, 9, 0)
        return b
    }

    /** Ack，固定 6 字节 */
    fun ack(ackSeq: Int, status: Int, commandId: Int): ByteArray {
        val b = ByteArray(6)
        b[0] = 1
        putU16(b, 1, ackSeq)
        b[3] = status.toByte()
        putU16(b, 4, commandId)
        return b
    }

    /**
     * NavigationSnapshot —— 圆屏导航的核心数据
     * 固定部分 53 字节（含两个长度字节）
     */
    fun navSnapshot(
        state: Int,
        network: Int,
        displayPage: Int,
        maneuver: Int,
        traffic: Int,
        flags: Int,
        routeToken: Int,
        routeGeneration: Int,
        maneuverId: Int,
        distanceToManeuverM: Long,
        remainingDistanceM: Long,
        remainingDurationS: Long,
        routeProgressM: Long,
        totalDistanceM: Long,
        speedDeciKph: Int,
        speedLimitKph: Int,
        headingCdeg: Int,
        accuracyDm: Int,
        crossTrackDm: Int,
        roundaboutExit: Int,
        roadName: String,
        instruction: String,
    ): ByteArray {
        val road = roadName.toByteArray(Charsets.UTF_8).let {
            if (it.size > 63) it.copyOf(63) else it
        }
        val inst = instruction.toByteArray(Charsets.UTF_8).let {
            if (it.size > 95) it.copyOf(95) else it
        }
        val size = 52 + road.size + 1 + inst.size
        val b = ByteArray(size)
        var o = 0
        b[o++] = 1                                  // revision
        b[o++] = state.toByte()
        b[o++] = network.toByte()
        b[o++] = displayPage.toByte()
        b[o++] = maneuver.toByte()
        b[o++] = traffic.toByte()
        putU16(b, o, flags); o += 2
        putU32(b, o, routeToken); o += 4
        putU32(b, o, routeGeneration); o += 4
        putU32(b, o, maneuverId); o += 4
        putU32(b, o, distanceToManeuverM); o += 4
        putU32(b, o, remainingDistanceM); o += 4
        putU32(b, o, remainingDurationS); o += 4
        putU32(b, o, routeProgressM); o += 4
        putU32(b, o, totalDistanceM); o += 4
        putU16(b, o, speedDeciKph); o += 2
        putU16(b, o, speedLimitKph); o += 2
        putU16(b, o, headingCdeg); o += 2
        putU16(b, o, accuracyDm); o += 2
        putU16(b, o, crossTrackDm); o += 2
        b[o++] = roundaboutExit.toByte()
        b[o++] = road.size.toByte()
        System.arraycopy(road, 0, b, o, road.size); o += road.size
        b[o++] = inst.size.toByte()
        System.arraycopy(inst, 0, b, o, inst.size)
        return b
    }

    /** 解析设备发来的 DeviceCommand 负载（用于触摸切页） */
    data class DeviceCommand(
        val commandId: Int,
        val command: Int,
        val payload: ByteArray,
    )

    fun decodeDeviceCommand(b: ByteArray): DeviceCommand? {
        if (b.size < 7) return null
        if ((b[0].toInt() and 0xFF) != 1) return null
        return DeviceCommand(
            commandId = getU32(b, 1),
            command = b[5].toInt() and 0xFF,
            payload = b.copyOfRange(6, b.size),
        )
    }

    // ---------- 小端读写工具 ----------

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) = putU32(b, o, v.toInt())

    fun getU16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun getU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)
}
