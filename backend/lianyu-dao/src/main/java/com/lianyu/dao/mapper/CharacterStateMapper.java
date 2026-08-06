package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.entity.CharacterState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CharacterStateMapper extends BaseMapper<CharacterState> {

    /**
     * 单语句 upsert 默认情绪行。并发首聊同一 (character_id, user_id) 时，
     * select-then-insert 会在 uk_char_user 上形成「S 间隙锁 + insert intention 互等」死锁；
     * upsert 只在已存在行上短暂串行，无 S→X 锁升级环。
     */
    @Insert("INSERT INTO character_state (character_id, user_id, current_emotion, emotion_intensity,"
            + " status_text, emotion_updated_at, created_at, updated_at)"
            + " VALUES (#{characterId}, #{userId}, #{currentEmotion}, #{emotionIntensity},"
            + " #{statusText}, #{emotionUpdatedAt}, NOW(3), NOW(3))"
            + " ON DUPLICATE KEY UPDATE id = id")
    int upsertDefault(CharacterState state);
}