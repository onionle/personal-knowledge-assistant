package com.example.lc4j.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话历史的落库与查询。
 */
@Service
public class ChatHistoryService {

    private static final String BACKEND_NAME = "langchain4j";

    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public ChatHistoryService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public List<Conversation> listConversations(Long userId) {
        return conversations.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, 0);
    }

    public List<Message> listMessages(Long userId, String convId) {
        Conversation conv = conversations.findById(convId).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId) || conv.getStatus() != 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return messages.findByConversationIdOrderByIdAsc(convId);
    }

    /** 逻辑删除：置 status=1（校验归属），不真删行。 */
    @Transactional
    public void softDelete(Long userId, String convId) {
        Conversation conv = conversations.findById(convId).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return; // 不存在或不是本人的，静默忽略
        }
        conv.setStatus(1);
        conversations.save(conv);
    }

    /** 重命名（校验归属）。 */
    @Transactional
    public void rename(Long userId, String convId, String title) {
        Conversation conv = conversations.findById(convId).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId) || conv.getStatus() != 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        conv.setTitle(title);
        conversations.save(conv);
    }

    /** 取这段会话最近 limit 条消息，作为 LLM 上下文。会话不存在/非本人 → 空列表（不抛错，
     *  方便"新会话第一条消息"的情况）。 */
    public List<Message> recentMessages(Long userId, String convId, int limit) {
        Conversation conv = conversations.findById(convId).orElse(null);
        if (conv == null || !conv.getUserId().equals(userId) || conv.getStatus() != 0) {
            return List.of();
        }
        List<Message> all = messages.findByConversationIdOrderByIdAsc(convId);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    /** 把一轮问答落库（登录用户）。新会话自动建，标题取首条消息。 */
    @Transactional
    public void saveTurn(Long userId, String convId, String userMsg, String assistantMsg) {
        Conversation conv = conversations.findById(convId).orElse(null);
        if (conv == null) {
            conv = new Conversation();
            conv.setId(convId);
            conv.setUserId(userId);
            conv.setTitle(userMsg.length() > 24 ? userMsg.substring(0, 24) : userMsg);
            conv.setBackend(BACKEND_NAME);
            conv.setUpdatedAt(LocalDateTime.now());
            conversations.save(conv);
        } else if (!conv.getUserId().equals(userId)) {
            return; // 不是本人的会话，拒绝写入
        } else {
            conv.setUpdatedAt(LocalDateTime.now());
            conversations.save(conv);
        }
        messages.save(new Message(convId, "user", userMsg));
        messages.save(new Message(convId, "assistant", assistantMsg));
    }
}
