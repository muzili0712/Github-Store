package zed.rainxch.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import zed.rainxch.core.data.dto.ExternalMatchRequest
import zed.rainxch.core.data.local.db.dao.ExternalLinkDao
import zed.rainxch.core.data.local.db.entities.ExternalLinkEntity
import zed.rainxch.core.data.mappers.toRepoMatchResults
import zed.rainxch.core.data.mappers.toRequestItem
import zed.rainxch.core.data.network.ExternalMatchApi
import zed.rainxch.core.domain.repository.ExternalImportRepository
import zed.rainxch.core.domain.system.ExternalAppCandidate
import zed.rainxch.core.domain.system.ExternalAppScanner
import zed.rainxch.core.domain.system.ExternalLinkState
import zed.rainxch.core.domain.system.ImportSummary
import zed.rainxch.core.domain.system.RepoMatchResult
import zed.rainxch.core.domain.system.RepoMatchSource
import zed.rainxch.core.domain.system.RepoMatchSuggestion
import zed.rainxch.core.domain.system.ScanResult

class ExternalImportRepositoryImpl(
    private val scanner: ExternalAppScanner,
    private val externalLinkDao: ExternalLinkDao,
    private val preferences: DataStore<Preferences>,
    private val externalMatchApi: ExternalMatchApi,
) : ExternalImportRepository {
    // Snapshot cache survives only for the lifetime of the process. Decisions
    // (linked / skipped / never-ask) are persisted in `external_links`; the
    // raw candidate metadata (label, fingerprint, hint) is regenerated on the
    // next scan rather than persisted to keep the schema small.
    private val candidateSnapshot = MutableStateFlow<Map<String, ExternalAppCandidate>>(emptyMap())

    override fun pendingCandidatesFlow(): Flow<List<ExternalAppCandidate>> =
        combine(
            candidateSnapshot,
            externalLinkDao.observePendingReview(),
        ) { snapshot, pendingRows ->
            val pendingPackages = pendingRows.map { it.packageName }.toSet()
            pendingPackages.mapNotNull { snapshot[it] }
        }

    override fun pendingCandidateCountFlow(): Flow<Int> = externalLinkDao.observePendingReviewCount()

    override suspend fun scheduleInitialScanIfNeeded() {
        val alreadyScanned = preferences.data.first()[INITIAL_SCAN_COMPLETED_AT_KEY] != null
        if (alreadyScanned) return
        runCatching { runFullScan() }
            .onSuccess { markInitialScanComplete() }
            .onFailure { Logger.w(it) { "Initial external scan failed; will retry on next launch." } }
    }

    override suspend fun runFullScan(): ScanResult {
        val started = nowMillis()
        val granted = scanner.isPermissionGranted()
        val candidates = scanner.snapshot()
        candidateSnapshot.update { candidates.associateBy { it.packageName } }

        val now = nowMillis()
        var newCandidates = 0
        var pendingReview = 0

        candidates.forEach { candidate ->
            val existing = externalLinkDao.get(candidate.packageName)
            val updated = mergeCandidate(existing, candidate, now)
            if (existing == null) newCandidates++
            if (updated.state == ExternalLinkState.PENDING_REVIEW.name) pendingReview++
            externalLinkDao.upsert(updated)
        }

        return ScanResult(
            totalCandidates = candidates.size,
            newCandidates = newCandidates,
            autoLinked = 0, // wired with backend match resolver in Week 2
            pendingReview = pendingReview,
            durationMillis = nowMillis() - started,
            permissionGranted = granted,
        )
    }

    override suspend fun runDeltaScan(changedPackageNames: Set<String>): ScanResult {
        val started = nowMillis()
        val granted = scanner.isPermissionGranted()
        val now = nowMillis()
        var newCandidates = 0
        var pendingReview = 0
        val deltaCandidates = mutableListOf<ExternalAppCandidate>()

        changedPackageNames.forEach { pkg ->
            val candidate = scanner.snapshotSingle(pkg)
            if (candidate == null) {
                externalLinkDao.deleteByPackageName(pkg)
                return@forEach
            }
            deltaCandidates += candidate
            val existing = externalLinkDao.get(pkg)
            val updated = mergeCandidate(existing, candidate, now)
            if (existing == null) newCandidates++
            if (updated.state == ExternalLinkState.PENDING_REVIEW.name) pendingReview++
            externalLinkDao.upsert(updated)
        }

        if (deltaCandidates.isNotEmpty()) {
            candidateSnapshot.update { current ->
                current.toMutableMap().apply {
                    deltaCandidates.forEach { put(it.packageName, it) }
                }
            }
        }

        return ScanResult(
            totalCandidates = deltaCandidates.size,
            newCandidates = newCandidates,
            autoLinked = 0,
            pendingReview = pendingReview,
            durationMillis = nowMillis() - started,
            permissionGranted = granted,
        )
    }

    override suspend fun resolveMatches(candidates: List<ExternalAppCandidate>): List<RepoMatchResult> {
        if (candidates.isEmpty()) return emptyList()

        val backendResults = mutableMapOf<String, MutableList<RepoMatchSuggestion>>()
        for (batch in candidates.chunked(MATCH_BATCH_SIZE)) {
            val request =
                ExternalMatchRequest(
                    platform = "android",
                    candidates = batch.map { it.toRequestItem() },
                )
            externalMatchApi
                .match(request)
                .onSuccess { response ->
                    response.toRepoMatchResults().forEach { result ->
                        backendResults
                            .getOrPut(result.packageName) { mutableListOf() }
                            .addAll(result.suggestions)
                    }
                }.onFailure { Logger.w(it) { "external-match batch failed; continuing" } }
        }

        return candidates.map { candidate ->
            val suggestions = mutableListOf<RepoMatchSuggestion>()
            candidate.manifestHint?.let { hint ->
                suggestions += RepoMatchSuggestion(
                    owner = hint.owner,
                    repo = hint.repo,
                    confidence = hint.confidence,
                    source = RepoMatchSource.MANIFEST,
                )
            }
            backendResults[candidate.packageName]?.let { suggestions += it }
            RepoMatchResult(
                packageName = candidate.packageName,
                suggestions = suggestions
                    .distinctBy { "${it.owner}/${it.repo}" }
                    .sortedByDescending { it.confidence },
            )
        }
    }

    override suspend fun importAutoMatched(matches: List<RepoMatchResult>): ImportSummary {
        var linked = 0
        var failed = 0
        val now = nowMillis()
        matches.forEach { result ->
            val top = result.topSuggestion
            if (top != null && top.confidence >= AUTO_LINK_CONFIDENCE_THRESHOLD) {
                val outcome = runCatching {
                    val existing = externalLinkDao.get(result.packageName)
                    val base = existing ?: ExternalLinkEntity(
                        packageName = result.packageName,
                        state = ExternalLinkState.MATCHED.name,
                        repoOwner = top.owner,
                        repoName = top.repo,
                        matchSource = top.source.name.lowercase(),
                        matchConfidence = top.confidence,
                        signingFingerprint = null,
                        installerKind = null,
                        firstSeenAt = now,
                        lastReviewedAt = now,
                        skipExpiresAt = null,
                    )
                    externalLinkDao.upsert(
                        base.copy(
                            state = ExternalLinkState.MATCHED.name,
                            repoOwner = top.owner,
                            repoName = top.repo,
                            matchSource = top.source.name.lowercase(),
                            matchConfidence = top.confidence,
                            lastReviewedAt = now,
                        ),
                    )
                }
                outcome
                    .onSuccess { linked++ }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        failed++
                        Logger.w(e) { "auto-link upsert failed for ${result.packageName}" }
                    }
            }
        }
        // TODO Week 2 day 11: also call AppsRepository.linkAppToRepo to materialize installed_apps rows
        return ImportSummary(attempted = matches.size, linked = linked, failed = failed)
    }

    override suspend fun linkManually(
        packageName: String,
        owner: String,
        repo: String,
        source: String,
    ): Result<Unit> {
        val now = nowMillis()
        return runCatching {
            val existing = externalLinkDao.get(packageName)
            val base = existing ?: ExternalLinkEntity(
                packageName = packageName,
                state = ExternalLinkState.MATCHED.name,
                repoOwner = owner,
                repoName = repo,
                matchSource = source,
                matchConfidence = 1.0,
                signingFingerprint = null,
                installerKind = null,
                firstSeenAt = now,
                lastReviewedAt = now,
                skipExpiresAt = null,
            )
            externalLinkDao.upsert(
                base.copy(
                    state = ExternalLinkState.MATCHED.name,
                    repoOwner = owner,
                    repoName = repo,
                    matchSource = source,
                    matchConfidence = 1.0,
                    lastReviewedAt = now,
                ),
            )
        }.onFailure { if (it is CancellationException) throw it }
        // TODO Week 2 day 11: AppsRepository.linkAppToRepo
    }

    override suspend fun skipPackage(
        packageName: String,
        neverAsk: Boolean,
    ) {
        val existing = externalLinkDao.get(packageName)
        val state = if (neverAsk) ExternalLinkState.NEVER_ASK else ExternalLinkState.SKIPPED
        val now = nowMillis()
        val skipExpiresAt = if (neverAsk) null else now + SKIP_TTL_MILLIS
        val row =
            existing?.copy(
                state = state.name,
                lastReviewedAt = now,
                skipExpiresAt = skipExpiresAt,
            ) ?: ExternalLinkEntity(
                packageName = packageName,
                state = state.name,
                repoOwner = null,
                repoName = null,
                matchSource = null,
                matchConfidence = null,
                signingFingerprint = null,
                installerKind = null,
                firstSeenAt = now,
                lastReviewedAt = now,
                skipExpiresAt = skipExpiresAt,
            )
        externalLinkDao.upsert(row)
    }

    override suspend fun unlink(packageName: String) {
        externalLinkDao.deleteByPackageName(packageName)
        candidateSnapshot.update { it - packageName }
    }

    override suspend fun rescanSinglePackage(packageName: String): RepoMatchResult? {
        val candidate = scanner.snapshotSingle(packageName) ?: return null
        candidateSnapshot.update { it + (packageName to candidate) }
        return resolveMatches(listOf(candidate)).firstOrNull()
    }

    override suspend fun syncSigningFingerprintSeed() {
        notImplemented("syncSigningFingerprintSeed")
    }

    override suspend fun pruneExpiredSkips() {
        externalLinkDao.pruneExpiredSkips(nowMillis())
    }

    override suspend fun isPermissionGranted(): Boolean = scanner.isPermissionGranted()

    private suspend fun markInitialScanComplete() {
        preferences.edit { prefs ->
            prefs[INITIAL_SCAN_COMPLETED_AT_KEY] = nowMillis()
        }
    }

    private fun mergeCandidate(
        existing: ExternalLinkEntity?,
        candidate: ExternalAppCandidate,
        now: Long,
    ): ExternalLinkEntity {
        if (existing != null && shouldPreserveDecision(existing, now)) {
            return existing.copy(
                signingFingerprint = candidate.signingFingerprint ?: existing.signingFingerprint,
                // installerKind is authoritative per-scan from PackageManager; signingFingerprint may briefly be null on extraction failure, so we hold the previous value.
                installerKind = candidate.installerKind.name,
            )
        }

        val hint = candidate.manifestHint
        return ExternalLinkEntity(
            packageName = candidate.packageName,
            state = ExternalLinkState.PENDING_REVIEW.name,
            repoOwner = hint?.owner ?: existing?.repoOwner,
            repoName = hint?.repo ?: existing?.repoName,
            matchSource = if (hint != null) RepoMatchSource.MANIFEST.name else existing?.matchSource,
            matchConfidence = hint?.confidence ?: existing?.matchConfidence,
            signingFingerprint = candidate.signingFingerprint,
            installerKind = candidate.installerKind.name,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastReviewedAt = now,
            skipExpiresAt = null,
        )
    }

    private fun shouldPreserveDecision(
        existing: ExternalLinkEntity,
        now: Long,
    ): Boolean =
        when (existing.state) {
            ExternalLinkState.MATCHED.name -> true
            ExternalLinkState.NEVER_ASK.name -> true
            ExternalLinkState.SKIPPED.name -> (existing.skipExpiresAt ?: 0) > now
            else -> false
        }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun notImplemented(name: String): Nothing =
        error("ExternalImportRepository.$name is not implemented yet (Week 2/3 of E1).")

    companion object {
        private val INITIAL_SCAN_COMPLETED_AT_KEY = longPreferencesKey("external_import_initial_scan_at")
        private const val SKIP_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        private const val MATCH_BATCH_SIZE = 25
        private const val AUTO_LINK_CONFIDENCE_THRESHOLD = 0.85
    }
}
