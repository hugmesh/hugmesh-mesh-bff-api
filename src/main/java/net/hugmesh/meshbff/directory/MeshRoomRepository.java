package net.hugmesh.meshbff.directory;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Read-side repository for {@code mesh_room} — {@link MeshDirectoryCache}'s
 * Postgres reload leg. See {@link MeshNodeRepository}'s javadoc for why writes
 * go through {@link MeshNodeDirectoryDao}'s explicit upsert SQL instead of
 * {@code save()}.
 */
public interface MeshRoomRepository extends ReactiveCrudRepository<MeshRoomRow, String> {
}
