package com.aioj.next.ai.persistence.mapper;

import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiTurnDigestMapper extends BaseMapper<AiTurnDigestEntity> {

    /**
     * Latest digest version per turn for a conversation, newest turns first. Callers must
     * always read through this projection: older versions stay queryable for audit but are
     * never the retrieval source (design doc §6.3, digest_version semantics).
     */
    @Select("""
            SELECT d.* FROM ai_turn_digests d
            JOIN (
                SELECT turn_id, MAX(digest_version) AS max_version
                FROM ai_turn_digests
                WHERE user_id = #{userId} AND conversation_id = #{conversationId}
                GROUP BY turn_id
            ) latest ON latest.turn_id = d.turn_id AND latest.max_version = d.digest_version
            WHERE d.user_id = #{userId} AND d.conversation_id = #{conversationId}
            ORDER BY d.created_at DESC
            LIMIT #{limit}
            """)
    List<AiTurnDigestEntity> selectLatestForConversation(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("limit") int limit
    );

    /**
     * Latest digest version per turn across ALL conversations of one user, newest first
     * (design doc §10 P4-1 cross-conversation recall). Same latest-version projection as
     * {@link #selectLatestForConversation}; ownership is still hard-scoped to userId.
     */
    @Select("""
            SELECT d.* FROM ai_turn_digests d
            JOIN (
                SELECT turn_id, MAX(digest_version) AS max_version
                FROM ai_turn_digests
                WHERE user_id = #{userId}
                GROUP BY turn_id
            ) latest ON latest.turn_id = d.turn_id AND latest.max_version = d.digest_version
            WHERE d.user_id = #{userId}
            ORDER BY d.created_at DESC
            LIMIT #{limit}
            """)
    List<AiTurnDigestEntity> selectLatestForUser(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );
}
