package systems.snapdragon.meshbff.directory;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Read-side repository for {@code mesh_node_directory} — {@link MeshDirectoryCache}'s
 * Postgres reload leg. Writes go through {@link MeshNodeDirectoryDao}'s explicit
 * upsert SQL, NOT {@code save()} — this table uses an app-assigned (not
 * database-generated) String primary key, and Spring Data R2DBC's default
 * {@code isNew()} heuristic treats a non-null assigned id as "existing", so a
 * plain {@code save()} on a genuinely new row issues a no-op UPDATE instead of
 * an INSERT. See {@link MeshNodeDirectoryDao} for the upsert.
 */
public interface MeshNodeRepository extends ReactiveCrudRepository<MeshNodeRow, String> {
}
