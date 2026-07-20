package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CharacterSquareTemplateMapper extends BaseMapper<CharacterSquareTemplate> {

    @Update("UPDATE character_square_template SET add_count = add_count + 1 WHERE id = #{id}")
    int incrementAddCount(@Param("id") Long id);
}
