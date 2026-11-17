package com.agent.codereviewagent.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 一个仓库在每个指令位置只有一个自定义版本；禁用时保留其文本。 */
@Mapper
public interface RepositorySkillMapper {
    record Entry(String slot, String content, boolean enabled) {}
    @Select("SELECT slot, content, enabled FROM repository_skill WHERE profile_id=#{id} ORDER BY slot")
    List<Entry> list(long id);
    @Insert("""
            INSERT INTO repository_skill(profile_id,slot,content,enabled) VALUES(#{id},#{slot},#{content},#{enabled})
            ON CONFLICT(profile_id,slot) DO UPDATE SET content=excluded.content, enabled=excluded.enabled
            """)
    void store(@Param("id") long id,@Param("slot") String slot,@Param("content") String content,@Param("enabled") boolean enabled);
    @Delete("DELETE FROM repository_skill WHERE profile_id=#{id} AND slot=#{slot}")
    void remove(@Param("id") long id,@Param("slot") String slot);
}
