package zed.rainxch.home.data.data_source

import zed.rainxch.home.data.dto.CachedRepoResponse
import zed.rainxch.home.domain.model.HomePlatform

interface CachedRepositoriesDataSource {
    suspend fun getCachedTrendingRepos(platform: HomePlatform): CachedRepoResponse?

    suspend fun getCachedHotReleaseRepos(platform: HomePlatform): CachedRepoResponse?

    suspend fun getCachedMostPopularRepos(platform: HomePlatform): CachedRepoResponse?
}
