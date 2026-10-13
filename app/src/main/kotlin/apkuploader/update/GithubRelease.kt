package apkuploader.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
)
