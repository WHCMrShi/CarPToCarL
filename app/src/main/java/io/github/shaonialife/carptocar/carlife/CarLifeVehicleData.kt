package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.iap2.PascdGear

/**
 * Guide chapter 12: integer km/h, optional timestamp, module VELOCITY=1.
 * OPPO 8.6.8 CarDataSubscribeManager + generated protobuf bytecode confirm the command
 * IDs and field tags. Its START/STOP are a single VehicleInfo; do not infer the older list format.
 * See docs/carlife-pascd-integration-20261117.md for evidence and version differences.
 */
internal object CarLifeVehicleData {
    const val SPEED = 0x1800F
    const val SUBSCRIBE = 0x10031
    const val SUBSCRIBE_DONE = 0x18032
    const val START = 0x10033
    const val STOP = 0x10034
    const val VELOCITY = 1
    const val GEAR = 0x18029
    const val GEAR_MODULE = 4

    /** OPPO GearInfo field1/int32; guide 12.1.1.6 defines N/P/D/L/R=1/2/3/4/5. */
    fun gear(gear: PascdGear): ByteArray = CarLifeProtocol.pbVarint(1, when (gear) {
        PascdGear.N -> 1
        PascdGear.P -> 2
        PascdGear.D -> 3
        PascdGear.R -> 5
    })

    fun speed(kmh: Int): ByteArray {
        // Optional timestamp omitted: PASCD time is not Unix time.
        return CarLifeProtocol.pbVarint(1, kmh)
    }

    fun capabilities(modules: Set<Int>): ByteArray =
        CarLifeProtocol.pbVarint(1, modules.size) + modules.sorted().fold(ByteArray(0)) { bytes, module ->
            bytes + CarLifeProtocol.pbBytes(2,
                CarLifeProtocol.pbVarint(1, module) +
                    CarLifeProtocol.pbVarint(2, 1) + // supported; old proto calls this bool supportFlag
                    CarLifeProtocol.pbVarint(3, 1), // source nominally 1 Hz; never synthesize samples
            )
        }

    data class Control(val module: Int, val frequency: Int)
    fun control(payload: ByteArray): Control? {
        val fields = CarLifeProtobuf.parse(payload)
        if (fields.error != null) return null
        if (fields.items.any { it.number == 2 && it.wire == 2 }) return null
        // Do not truncate oversized protobuf integers into valid module IDs/frequencies.
        fun integer(id: Int): Int? {
            val value = fields.items.lastOrNull { it.number == id && it.wire == 0 }?.value ?: return null
            return value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        }
        val module = integer(1) ?: return null
        val frequency = if (fields.items.any { it.number == 3 }) integer(3) ?: return null else 0
        return Control(module, frequency)
    }
}
