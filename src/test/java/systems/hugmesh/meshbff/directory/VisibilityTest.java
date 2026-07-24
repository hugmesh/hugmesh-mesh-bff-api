package systems.hugmesh.meshbff.directory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the v1 visibility rule table — see {@link Visibility}'s javadoc. */
class VisibilityTest {

    @Test
    void owner_alwaysPermitted_regardlessOfVisibility() {
        assertThat(Visibility.permits(Visibility.PRIVATE, true, false, false)).isTrue();
        assertThat(Visibility.permits(Visibility.FRIENDS, true, false, false)).isTrue();
        assertThat(Visibility.permits(Visibility.CHANNELS, true, false, false)).isTrue();
    }

    @Test
    void superadmin_alwaysPermitted_regardlessOfVisibility() {
        assertThat(Visibility.permits(Visibility.PRIVATE, false, false, true)).isTrue();
    }

    @Test
    void privateVisibility_deniesNonOwner() {
        assertThat(Visibility.permits(Visibility.PRIVATE, false, true, false)).isFalse();
    }

    @Test
    void friendsVisibility_v1EqualsPrivate_deniesNonOwnerEvenSameDomain() {
        // No friendship primitive exists — friends v1 = private (owner only).
        assertThat(Visibility.permits(Visibility.FRIENDS, false, true, false)).isFalse();
    }

    @Test
    void channelsVisibility_permitsAnyCallerSameDomain() {
        assertThat(Visibility.permits(Visibility.CHANNELS, false, true, false)).isTrue();
    }

    @Test
    void channelsVisibility_deniesDifferentDomain() {
        assertThat(Visibility.permits(Visibility.CHANNELS, false, false, false)).isFalse();
    }

    @Test
    void unrecognizedVisibility_failsClosed() {
        assertThat(Visibility.permits("something-invented", false, true, false)).isFalse();
    }

    @Test
    void nullVisibility_treatedAsPrivate() {
        assertThat(Visibility.permits(null, false, true, false)).isFalse();
    }
}
