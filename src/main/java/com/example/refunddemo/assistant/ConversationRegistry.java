package com.example.refunddemo.assistant;

import com.example.refunddemo.refund.RefundWorkflowException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Demo business associations. Chat transcripts live in Spring AI SessionService. */
public final class ConversationRegistry {
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private static final Pattern ORDER = Pattern.compile("(?<![A-Za-z0-9_-])O-[0-9]{1,20}(?![A-Za-z0-9_-])");

    public Conversation getOrCreate(String id) {
        validateId(id);
        return conversations.computeIfAbsent(id, ignored -> new Conversation());
    }

    public Conversation require(String id) {
        validateId(id);
        var conversation = conversations.get(id);
        if (conversation == null) throw new RefundWorkflowException(RefundWorkflowException.Kind.NOT_FOUND, "未找到会话");
        return conversation;
    }

    public static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("ID 必须为 1-80 个英文字母、数字、下划线或连字符");
        }
    }

    public static final class Conversation {
        final ReentrantLock turnLock = new ReentrantLock();
        final Set<String> mentionedOrders = ConcurrentHashMap.newKeySet();
        // Accessed under synchronized(refundThreads), including inside a tool-executor thread.
        final Map<String, String> refundThreads = new LinkedHashMap<>();
        final Map<String, CompletedRequest> requests = new LinkedHashMap<>();

        void observeUser(String message) {
            ORDER.matcher(message).results().map(java.util.regex.MatchResult::group).forEach(mentionedOrders::add);
        }
    }

    record CompletedRequest(String input, ChatTurnResult result) { }
}
