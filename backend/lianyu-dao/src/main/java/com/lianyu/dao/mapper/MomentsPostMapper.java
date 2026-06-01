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
}
