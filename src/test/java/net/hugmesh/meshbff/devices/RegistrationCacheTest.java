package net.hugmesh.meshbff.devices;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** {@link RegistrationCache} — the fail-soft (last-good) reload contract, and the
 *  case-insensitive nodeId / localpart lookups the mesh directory + devices/mine
 *  controllers depend on. No Spring context (constructs the cache directly with a
 *  mocked {@link UserBffMeshDeviceClient}, no circuit-breaker factory). */
class RegistrationCacheTest {

    private RegistrationCache newCache(UserBffMeshDeviceClient client) {
        return new RegistrationCache(client, null);
    }

    @Test
    void primeForTest_seedsSnapshot_lookupsWork() {
        RegistrationCache cache = newCache(Mockito.mock(UserBffMeshDeviceClient.class));
        cache.primeForTest(List.of(new Registration("!a1b2c3d4", "alice", "alice", "private")));

        assertThat(cache.registrationFor("!A1B2C3D4")).isNotNull(); // case-insensitive nodeId
        assertThat(cache.registrationFor("!a1b2c3d4").localpart()).isEqualTo("alice");
        assertThat(cache.registrationFor("!deadbeef")).isNull();
        assertThat(cache.byLocalpart("alice")).hasSize(1);
        assertThat(cache.byLocalpart("bob")).isEmpty();
        assertThat(cache.everSucceeded()).isTrue();
    }

    @Test
    void refresh_onFailure_keepsLastGoodSnapshot_neverBlanks() {
        UserBffMeshDeviceClient client = Mockito.mock(UserBffMeshDeviceClient.class);
        RegistrationCache cache = newCache(client);
        cache.primeForTest(List.of(new Registration("!a1b2c3d4", "alice", "alice", "private")));

        when(client.fetchAll()).thenReturn(Mono.error(new RuntimeException("user-bff unreachable")));
        cache.refreshNow().block();

        // The failed reload must NOT wipe the last-good snapshot.
        assertThat(cache.registrationFor("!a1b2c3d4")).isNotNull();
    }

    @Test
    void refresh_onSuccess_replacesSnapshot() {
        UserBffMeshDeviceClient client = Mockito.mock(UserBffMeshDeviceClient.class);
        RegistrationCache cache = newCache(client);
        cache.primeForTest(List.of(new Registration("!a1b2c3d4", "alice", "alice", "private")));

        when(client.fetchAll()).thenReturn(Mono.just(List.of(
                new Registration("!deadbeef", "bob", "bob", "channels"))));
        cache.refreshNow().block();

        assertThat(cache.registrationFor("!a1b2c3d4")).isNull();
        assertThat(cache.registrationFor("!deadbeef")).isNotNull();
    }
}
