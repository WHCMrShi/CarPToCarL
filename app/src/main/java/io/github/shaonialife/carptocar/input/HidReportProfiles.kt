package io.github.shaonialife.carptocar.input

/** 每条事件连接独享；/info 替换描述符时失效，缺描述符仍允许按报文长度回退。 */
internal class HidReportProfiles {
    class Profile(
        val descriptor: ByteArray?,
        val keys: ConsumerKeys.KeyMap?,
        val touch: TouchReports.Format?,
    )

    private val profiles = HashMap<String, Profile>()
    private val unknown = Profile(null, null, null)

    fun get(
        uuid: String?,
        descriptor: ByteArray?,
    ): Profile {
        if (uuid == null) return unknown
        val cached = profiles[uuid]
        if (cached != null && cached.descriptor === descriptor) return cached
        return Profile(
            descriptor,
            descriptor?.let(ConsumerKeys::parseDescriptor),
            descriptor?.let(TouchReports::formatFromDescriptor),
        ).also { profiles[uuid] = it }
    }
}
