package com.example.sai.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    // status=0 只取未删除的
    List<Conversation> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, int status);
}
