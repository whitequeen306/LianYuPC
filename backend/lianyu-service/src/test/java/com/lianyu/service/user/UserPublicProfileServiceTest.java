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
}
