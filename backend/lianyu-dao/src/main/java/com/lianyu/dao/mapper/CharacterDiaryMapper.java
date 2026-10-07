package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.dto.CharacterDiaryLastDateRow;
import com.lianyu.dao.entity.CharacterDiary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CharacterDiaryMapper extends BaseMapper<CharacterDiary> {

    /** 批量查每个角色的最后一篇日记时间（任何来源），两天保底等节奏控制使用。 */
    @Select("""
            <script>
            SELECT character_id AS characterId, MAX(created_at) AS lastDiaryAt
            FROM character_diary
            WHERE character_id IN
            <foreach collection='characterIds' item='cid' open='(' separator=',' close=')'>
                #{cid}
            </foreach>
            GROUP BY character_id
            </script>
            """)
    List<CharacterDiaryLastDateRow> selectLastDiaryDates(@Param("characterIds") List<Long> characterIds);
}
