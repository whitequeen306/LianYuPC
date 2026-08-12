package com.lianyu.service.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPublicProfileServiceTest {

    @Test
    void companionshipDaysAtLeastOne() {
        assertEquals(1, UserPublicProfileService.companionshipDays(null));
        assertEquals(1, UserPublicProfileService.companionshipDays(LocalDateTime.now()));
        assertTrue(UserPublicProfileService.companionshipDays(
                LocalDate.now().minusDays(9).atStartOfDay()) >= 10);
    }

    @Test
    void showCharactersDefaultsFalse() {
        assertFalse(UserSettingsResolver.showCharactersOnProfile(null));
        assertTrue(UserSettingsResolver.showCharactersOnProfile(
                UserSettingsResolver.withShowCharacters(null, true)));
    }

    @Test
    void communityPushDefaultsTrueUntilExplicitlyDisabled() {
        assertTrue(UserSettingsResolver.communityPushEnabled(null));
        assertTrue(UserSettingsResolver.communityPushEnabled(
                UserSettingsResolver.withCommunityPushEnabled(null, true)));
        assertFalse(UserSettingsResolver.communityPushEnabled(
                UserSettingsResolver.withCommunityPushEnabled(null, false)));
    }

    @Test
    void visionSourceDefaultsToPlatform() {
        assertEquals("platform", UserSettingsResolver.visionSource(null).mode());
        assertEquals(null, UserSettingsResolver.visionSource(null).provider());
        assertEquals("platform", UserSettingsResolver.visionSource(
                UserSettingsResolver.withVisionSource(null, "bogus", "VL1")).mode());
    }

    @Test
    void visionSourceFollowTextDropsProvider() {
        var vs = UserSettingsResolver.visionSource(
                UserSettingsResolver.withVisionSource(null, "followText", "VL1"));
        assertEquals("followText", vs.mode());
        assertEquals(null, vs.provider());
    }

    @Test
    void visionSourceProviderRoundTrips() {
        var settings = UserSettingsResolver.withVisionSource(null, "provider", " VL1 ");
        var vs = UserSettingsResolver.visionSource(settings);
        assertEquals("provider", vs.mode());
        assertEquals("VL1", vs.provider());

        // provider 模式下 provider 缺失 → 读回为 null（后端路由会回退平台）
        var noProvider = UserSettingsResolver.visionSource(
                UserSettingsResolver.withVisionSource(null, "provider", " "));
        assertEquals("provider", noProvider.mode());
        assertEquals(null, noProvider.provider());
    }
}
