package com.lianyu.service.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.dto.PublicCharacterCard;
import com.lianyu.service.dto.PublicUserProfileResponse;
import com.lianyu.service.dto.UpdateUserSettingsRequest;
import com.lianyu.service.dto.UserSettingsResponse;
import com.lianyu.service.storage.FileStorageService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPublicProfileService {

    private final UserMapper userMapper;
    private final CharacterMapper characterMapper;
    private final FileStorageService fileStorageService;

    public PublicUserProfileResponse getPublicProfile(Long viewerId, Long targetUserId) {
        User user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        boolean isSelf = Objects.equals(viewerId, targetUserId);
        boolean show = UserSettingsResolver.showCharactersOnProfile(user.getSettingsJson());
        List<PublicCharacterCard> cards = List.of();
        boolean hidden = !show;
        if (show) {
            hidden = false;
            cards = listPublicCharacters(targetUserId);
        }
        return PublicUserProfileResponse.builder()
                .userId(user.getId())
                .nickname(user.getNickname())
                .avatarUrl(fileStorageService.resolvePublicUrl(user.getAvatarUrl()))
                .showCharactersOnProfile(show)
                .charactersHidden(hidden)
                .characters(cards)
                .self(isSelf)
                .build();
    }

    public UserSettingsResponse getMySettings(Long userId) {
        User user = requireUser(userId);
        return UserSettingsResponse.builder()
                .showCharactersOnProfile(UserSettingsResolver.showCharactersOnProfile(user.getSettingsJson()))
                .communityPushEnabled(UserSettingsResolver.communityPushEnabled(user.getSettingsJson()))
                .build();
    }

    @Transactional
    public UserSettingsResponse updateMySettings(Long userId, UpdateUserSettingsRequest request) {
        User user = requireUser(userId);
        boolean dirty = false;
        if (request.getShowCharactersOnProfile() != null) {
            user.setSettingsJson(UserSettingsResolver.withShowCharacters(
                    user.getSettingsJson(), request.getShowCharactersOnProfile()));
            dirty = true;
        }
        if (request.getCommunityPushEnabled() != null) {
            user.setSettingsJson(UserSettingsResolver.withCommunityPushEnabled(
                    user.getSettingsJson(), request.getCommunityPushEnabled()));
            dirty = true;
        }
        if (dirty) {
            userMapper.updateById(user);
        }
        return getMySettings(userId);
    }

    private List<PublicCharacterCard> listPublicCharacters(Long ownerUserId) {
        List<Character> characters = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, ownerUserId)
                .orderByDesc(Character::getCreatedAt));
        return characters.stream()
                .map(c -> PublicCharacterCard.builder()
                        .characterId(c.getId())
                        .name(c.getName())
                        .avatarUrl(fileStorageService.resolvePublicUrl(c.getAvatarUrl()))
                        .companionshipDays(companionshipDays(c.getCreatedAt()))
                        .build())
                .toList();
    }

    static int companionshipDays(LocalDateTime createdAt) {
        if (createdAt == null) {
            return 1;
        }
        long days = ChronoUnit.DAYS.between(createdAt.toLocalDate(), LocalDate.now()) + 1;
        return (int) Math.max(1, days);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}
