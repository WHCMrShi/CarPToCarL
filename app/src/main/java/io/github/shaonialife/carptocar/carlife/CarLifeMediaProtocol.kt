package io.github.shaonialife.carptocar.carlife

/** 手机版本同时存在单条媒体消息和列表封装；按字段 1 的 wire type 判别，禁止猜测嵌套。 */
internal object CarLifeMediaProtocol {
    fun parseMedia(payload: ByteArray): List<CarLifeMediaInfo> {
        val f = CarLifeProtobuf.parse(payload)
        if (f.error != null) return emptyList()
        return when (f.items.firstOrNull { it.number == 1 }?.wire) {
            2 -> listOfNotNull(media(f))
            0 -> {
                val entries = f.items.filter { it.number == 2 && it.wire == 2 }
                if (f.int(1) != entries.size) emptyList()
                else entries.mapNotNull { item ->
                    CarLifeProtobuf.parse(item.bytes!!).takeIf { it.error == null }?.let(::media)
                }
            }
            else -> emptyList()
        }
    }

    private fun media(f: CarLifeProtobuf.Fields): CarLifeMediaInfo? {
        fun text(n: Int) = f.bytes(n)?.toString(Charsets.UTF_8).orEmpty()
        val song = text(2)
        val artist = text(3)
        if (song.isBlank() && artist.isBlank()) return null
        return CarLifeMediaInfo(text(1), song, artist, text(4), f.bytes(5),
            f.int(6) ?: 0, f.int(7) ?: 0, text(8), f.int(9) ?: 0)
    }

    /** CarlifeMediaProgressBar 是 field 1 的 protobuf varint；无效包不伪造成 0。 */
    fun progress(payload: ByteArray): Int? = CarLifeProtobuf.parse(payload).let { f ->
        if (f.error != null) null else f.int(1)?.takeIf { it >= 0 }
    }
}
