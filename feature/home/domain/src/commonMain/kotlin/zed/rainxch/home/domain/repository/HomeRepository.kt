package zed.rainxch.home.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.PaginatedDiscoveryRepositories
import zed.rainxch.home.domain.model.HomePlatform

interface HomeRepository {
    fun getTrendingRepositories(
        platform: HomePlatform,
        page: Int,
    ): Flow<PaginatedDiscoveryRepositories>

    fun getHotReleaseRepositories(
        platform: HomePlatform,
        page: Int,
    ): Flow<PaginatedDiscoveryRepositories>

    fun getMostPopular(
        platform: HomePlatform,
        page: Int,
    ): Flow<PaginatedDiscoveryRepositories>
}
