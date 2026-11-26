package tachiyomi.domain.importer.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.importer.repository.ImportSessionRepository

@Inject
class DeleteImportSession(
    private val repository: ImportSessionRepository,
) {
    suspend fun await(id: String) = repository.delete(id)
}
