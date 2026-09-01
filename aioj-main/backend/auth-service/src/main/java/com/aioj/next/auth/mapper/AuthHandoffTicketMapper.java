package com.aioj.next.auth.mapper;

import com.aioj.next.auth.entity.AuthHandoffTicketEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

public interface AuthHandoffTicketMapper extends BaseMapper<AuthHandoffTicketEntity> {
    @Update("""
            UPDATE auth_handoff_tickets
            SET consumed_at = #{consumedAt}
            WHERE ticket_hash = #{ticketHash}
              AND consumed_at IS NULL
              AND expires_at > #{consumedAt}
            """)
    int consume(@Param("ticketHash") String ticketHash, @Param("consumedAt") Instant consumedAt);
}
