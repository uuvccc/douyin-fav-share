package com.example.myapplication.data

/**
 * 访客模式输入解析：把用户粘贴的输入（主页链接 / sec_uid / 抖音号 / 短链）
 * 解析成可打开用户主页的 sec_uid。
 *
 * 纯逻辑、无 Android 依赖，便于单元测试。
 *
 * 背景：`douyin.com/user/{id}` 只接受 sec_uid（通常含 `MS4wLjAB` 特征前缀），
 * 不接受纯数字的 uid/抖音号；纯数字必须走搜索页解析，因此这里对纯数字一律不直接返回。
 */
object GuestInputParser {

    /** 从输入中提取 sec_uid；无法确定时返回 null。 */
    fun extractSecUid(input: String): String? {
        // 主页链接：douyin.com/user/{secUid}
        secUidFromUrl(input)?.let { return it }
        // 特征明显的 sec_uid（通常含 MS4wLjAB 或较长）
        if (input.contains("MS4wLjAB") ||
            (input.length >= 24 && Regex("^[A-Za-z0-9_\\-]+$").matches(input))
        ) {
            return input
        }
        // 注意：纯数字（如 54132528295）是 uid/抖音号，不是 sec_uid。
        // douyin.com/user/{id} 只接受 sec_uid，直接用纯数字会打开「用户不存在」页，
        // 因此不能在这里直接返回，必须走搜索页解析。
        return null
    }

    /** 从 URL 中提取用户主页 sec_uid；是 self 或不是用户主页时返回 null。 */
    fun secUidFromUrl(url: String?): String? {
        val id = Regex("douyin\\.com/user/([A-Za-z0-9_\\-]+)").find(url ?: "")?.groupValues?.get(1)
        return if (id.isNullOrEmpty() || id == "self") null else id
    }

    /** 从 DOM 提取脚本返回值中解析 sec_uid。 */
    fun secUidFromDomValue(value: String?): String? =
        Regex("\"secUid\"\\s*:\\s*\"([^\"]+)\"").find(value ?: "")
            ?.groupValues?.get(1)
}
