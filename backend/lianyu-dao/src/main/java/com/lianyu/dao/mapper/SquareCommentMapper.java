package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.entity.SquareComment;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SquareCommentMapper extends BaseMapper<SquareComment> {

    /**
     * Latest {@code limitPerTemplate} comments per template (MySQL 8 window function).
     */
    @Select("""
            <script>
            SELECT id, template_id, user_id, content, created_at, updated_at
            FROM (
                SELECT sc.id, sc.template_id, sc.user_id, sc.content, sc.created_at, sc.updated_at,
                       ROW_NUMBER() OVER (PARTITION BY sc.template_id ORDER BY sc.created_at DESC) AS rn
                FROM square_comment sc
                WHERE sc.template_id IN
                <foreach collection='templateIds' item='id' open='(' separator=',' close=')'>
                    #{id}
                </foreach>
            ) ranked
            WHERE ranked.rn &lt;= #{limitPerTemplate}
            ORDER BY ranked.template_id ASC, ranked.created_at DESC
            </script>
            """)
    List<SquareComment> selectLatestByTemplateIds(
            @Param("templateIds") Collection<Long> templateIds,
            @Param("limitPerTemplate") int limitPerTemplate);
}
