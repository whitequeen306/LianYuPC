package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.dto.MomentsDailyCountRow;
import com.lianyu.dao.entity.MomentsPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MomentsPostMapper extends BaseMapper<MomentsPost> {
    @Select("""
            <script>
            SELECT user_id AS userId,
                   character_id AS characterId,
                   COUNT(*) AS total
            FROM moments_post
            WHERE created_at &gt;= #{start}
              AND user_id IN
                <foreach collection='userIds' item='uid' open='(' separator=',' close=')'>
                    #{uid}
                </foreach>
              AND character_id IN
                <foreach collection='characterIds' item='cid' open='(' separator=',' close=')'>
                    #{cid}
                </foreach>
            GROUP BY user_id, character_id
            </script>
            """)
    List<MomentsDailyCountRow> selectTodayCountsByUsersAndCharacters(@Param("start") LocalDateTime start,
                                                                     @Param("userIds") List<Long> userIds,
                                                                     @Param("characterIds") List<Long> characterIds);

    /**
     * 缺少角色路人评论的动态：角色帖需 ≥2 角色；用户帖只需 ≥1 角色即可由角色跟评。
     */
    @Select("""
            SELECT p.id
            FROM moments_post p
            WHERE p.created_at < #{before}
              AND (
                (
                  p.author_type = 'CHARACTER'
                  AND (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) >= 2
                  AND NOT EXISTS (
                    SELECT 1 FROM moments_comment mc
                    WHERE mc.post_id = p.id
                      AND mc.source_type = 'AUTO_PEER_COMMENT'
                      AND mc.character_id IS NOT NULL
                      AND mc.character_id <> p.character_id
                  )
                )
                OR (
                  p.author_type = 'USER'
                  AND (SELECT COUNT(*) FROM `character` c WHERE c.owner_user_id = p.user_id) >= 1
                  AND NOT EXISTS (
                    SELECT 1 FROM moments_comment mc
                    WHERE mc.post_id = p.id
                      AND mc.source_type = 'AUTO_PEER_COMMENT'
                      AND mc.character_id IS NOT NULL
                  )
                )
              )
            ORDER BY p.id ASC
            LIMIT #{limit}
            """)
    List<Long> selectPostIdsNeedingPeerComments(@Param("before") LocalDateTime before,
                                                @Param("limit") int limit);
}
