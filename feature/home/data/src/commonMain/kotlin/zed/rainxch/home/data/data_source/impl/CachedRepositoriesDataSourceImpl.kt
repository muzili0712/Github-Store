package zed.rainxch.home.data.data_source.impl

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.home.data.data_source.CachedRepositoriesDataSource
import zed.rainxch.home.data.dto.CachedGithubRepoSummary
import zed.rainxch.home.data.dto.CachedRepoResponse
import zed.rainxch.home.domain.model.HomeCategory
import zed.rainxch.home.domain.model.HomePlatform
import kotlin.let
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class CachedRepositoriesDataSourceImpl(
    private val logger: GitHubStoreLogger,
) : CachedRepositoriesDataSource {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val httpClient =
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }

    private val cacheMutex = Mutex()
    private val memoryCache = mutableMapOf<CacheKey, CacheEntry>()

    private data class CacheEntry(
        val data: CachedRepoResponse,
        val fetchedAt: Instant,
    )

    override suspend fun getCachedTrendingRepos(platform: HomePlatform): CachedRepoResponse? =
        fetchCachedReposForCategory(platform, HomeCategory.TRENDING)

    override suspend fun getCachedHotReleaseRepos(platform: HomePlatform): CachedRepoResponse? =
        fetchCachedReposForCategory(platform, HomeCategory.HOT_RELEASE)

    override suspend fun getCachedMostPopularRepos(platform: HomePlatform): CachedRepoResponse? =
        fetchCachedReposForCategory(platform, HomeCategory.MOST_POPULAR)

    private suspend fun fetchCachedReposForCategory(
        platform: HomePlatform,
        category: HomeCategory,
    ): CachedRepoResponse? {
        val cacheKey = CacheKey(platform, category)

        val cached = cacheMutex.withLock { memoryCache[cacheKey] }
        if (cached != null) {
            val age = Clock.System.now() - cached.fetchedAt
            if (age < CACHE_TTL) {
                logger.debug("Memory cache hit for $cacheKey (age: ${age.inWholeSeconds}s)")
                return cached.data
            } else {
                logger.debug("Memory cache expired for $cacheKey (age: ${age.inWholeSeconds}s)")
            }
        }

        return withContext(Dispatchers.IO) {
            if (platform == HomePlatform.All) {
                val paths =
                    when (category) {
                        HomeCategory.TRENDING -> {
                            listOf(
                                "cached-data/trending/android.json",
                                "cached-data/trending/windows.json",
                                "cached-data/trending/macos.json",
                                "cached-data/trending/linux.json",
                            )
                        }

                        HomeCategory.HOT_RELEASE -> {
                            listOf(
                                "cached-data/new-releases/android.json",
                                "cached-data/new-releases/windows.json",
                                "cached-data/new-releases/macos.json",
                                "cached-data/new-releases/linux.json",
                            )
                        }

                        HomeCategory.MOST_POPULAR -> {
                            listOf(
                                "cached-data/most-popular/android.json",
                                "cached-data/most-popular/windows.json",
                                "cached-data/most-popular/macos.json",
                                "cached-data/most-popular/linux.json",
                            )
                        }
                    }

                val responses =
                    coroutineScope {
                        paths
                            .map { path ->
                                async {
                                    val url = "https://raw.githubusercontent.com/OpenHub-Store/api/main/$path"
                                    try {
                                        logger.debug("Fetching from: $url")
                                        val response: HttpResponse = httpClient.get(url)
                                        if (response.status.isSuccess()) {
                                            json.decodeFromString<CachedRepoResponse>(response.bodyAsText())
                                        } else {
                                            logger.error("HTTP ${response.status.value} from $url")
                                            null
                                        }
                                    } catch (e: SerializationException) {
                                        logger.error("Parse error from $url: ${e.message}")
                                        null
                                    } catch (e: Exception) {
                                        logger.error("Error with $url: ${e.message}")
                                        null
                                    }
                                }
                            }.awaitAll()
                            .filterNotNull()
                    }

                if (responses.isEmpty()) {
                    logger.error("All mirrors failed for $cacheKey")
                    return@withContext null
                }

                val mergedRepos =
                    responses
                        .asSequence()
                        .flatMap { it.repositories }
                        .distinctBy { it.id }
                        .sortedWith(
                            compareByDescending<CachedGithubRepoSummary> { it.trendingScore }
                                .thenByDescending { it.popularityScore }
                                .thenByDescending { it.latestReleaseDate },
                        ).toList()

                val merged =
                    CachedRepoResponse(
                        category = responses.first().category,
                        platform = "all",
                        lastUpdated = responses.maxOf { it.lastUpdated },
                        totalCount = mergedRepos.size,
                        repositories = mergedRepos,
                    )

                cacheMutex.withLock {
                    memoryCache[cacheKey] = CacheEntry(data = merged, fetchedAt = Clock.System.now())
                }

                merged
            } else {
                val platformName =
                    when (platform) {
                        HomePlatform.Android -> "android"
                        HomePlatform.Windows -> "windows"
                        HomePlatform.Macos -> "macos"
                        HomePlatform.Linux -> "linux"
                        HomePlatform.All -> error("Unreachable: All is handled above")
                    }

                val path =
                    when (category) {
                        HomeCategory.TRENDING -> "cached-data/trending/$platformName.json"
                        HomeCategory.HOT_RELEASE -> "cached-data/new-releases/$platformName.json"
                        HomeCategory.MOST_POPULAR -> "cached-data/most-popular/$platformName.json"
                    }

                val url = "https://raw.githubusercontent.com/OpenHub-Store/api/main/$path"

                try {
                    logger.debug("Fetching from: $url")
                    val response: HttpResponse = httpClient.get(url)

                    if (response.status.isSuccess()) {
                        val parsed = json.decodeFromString<CachedRepoResponse>(response.bodyAsText())

                        cacheMutex.withLock {
                            memoryCache[cacheKey] = CacheEntry(data = parsed, fetchedAt = Clock.System.now())
                        }

                        return@withContext parsed
                    } else {
                        logger.error("HTTP ${response.status.value} from $url")
                    }
                } catch (e: SerializationException) {
                    logger.error("Parse error from $url: ${e.message}")
                } catch (e: Exception) {
                    logger.error("Error with $url: ${e.message}")
                }

                logger.error("Fetch failed for $cacheKey")
                null
            }
        }
    }

    private companion object {
        private val CACHE_TTL = 5.minutes
    }

    private data class CacheKey(
        val platform: HomePlatform,
        val category: HomeCategory,
    )
}
