package io.github.git13166956007.dsh.agent;

import io.github.git13166956007.dsh.context.ContextManager;
import io.github.git13166956007.dsh.model.ModelRegistry;
import io.github.git13166956007.dsh.run.Run;
import io.github.git13166956007.dsh.run.RunManager;
import io.github.git13166956007.dsh.run.RunKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps a durable sub-agent conversation while each turn remains a normal Run. */
public final class SubAgentSessionManager {
    private final SubAgentSessionStore store;
    private final ContextManager contexts;
    private final SubAgentRunner runner;
    private final SubAgentProfileRegistry profiles;
    private final ModelRegistry models;
    private final RunManager runs;
    private final Map<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Map<String, String> activeRuns = new ConcurrentHashMap<String, String>();

    public SubAgentSessionManager(SubAgentSessionStore store, ContextManager contexts, SubAgentRunner runner,
                                  SubAgentProfileRegistry profiles, ModelRegistry models, RunManager runs) {
        this.store = store;
        this.contexts = contexts;
        this.runner = runner;
        this.profiles = profiles;
        this.models = models;
        this.runs = runs;
    }

    public List<SubAgentSession> list() throws Exception {
        return store.list().stream().map(SubAgentSession::from).toList();
    }

    public SubAgentSession find(String id) throws Exception {
        SubAgentSessionData session = store.find(id);
        return session == null ? null : SubAgentSession.from(session);
    }

    public SubAgentSession create(String profileId) throws Exception {
        SubAgentProfileData profile = profiles.resolve(profileId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        contexts.open(id, "Sub-agent: " + profile.name());
        SubAgentSessionData session = new SubAgentSessionData(id, profile.id(), id,
                SubAgentSessionStatus.OPEN, now, now);
        store.save(session);
        locks.put(id, new Object());
        return SubAgentSession.from(session);
    }

    public AgentRunHandle send(String sessionId, String prompt, String apiKey) throws Exception {
        String normalizedPrompt = required(prompt, "prompt");
        Object lock = locks.computeIfAbsent(sessionId, ignored -> new Object());
        synchronized (lock) {
            SubAgentSessionData session = requireOpen(sessionId);
            String previousRun = activeRuns.get(sessionId);
            if (previousRun != null) {
                throw new IllegalStateException("sub-agent session is busy: " + sessionId);
            }
            SubAgentProfileData profile = profiles.resolve(session.profileId());
            List<ChatMessage> history = contexts.history(session.conversationId(), modelWindow(profile),
                    models.tokenizer(profile.modelId()));
            contexts.append(session.conversationId(), ChatMessage.user(normalizedPrompt));
            AgentRunHandle handle = runner.startForExecution(normalizedPrompt, apiKey, profile.id(), history,
                    AgentRunContext.child(null, RunKind.SUB_AGENT, session.conversationId(), null, null, profile.id()));
            activeRuns.put(sessionId, handle.runId());
            touch(session);
            CompletableFuture<AgentRunResult> lifecycleResult = handle.result()
                    .whenComplete((result, error) -> finish(sessionId, handle.runId(), result, error));
            return new AgentRunHandle(handle.runId(), lifecycleResult, handle::cancel);
        }
    }

    /** Completes the session bookkeeping after an approval endpoint resumes a turn. */
    public void onApprovalResult(AgentRunResult result) throws Exception {
        if (result == null || result.runId() == null) return;
        Run run = runs.find(result.runId());
        if (run == null || run.kind() != RunKind.SUB_AGENT || run.conversationId() == null) return;
        String sessionId = sessionForConversation(run.conversationId());
        if (sessionId == null) return;
        if (result.pendingApproval() == null) {
            activeRuns.remove(sessionId, result.runId());
            touch(store.find(sessionId));
        }
    }

    public void onApprovalFailure(String runId) throws Exception {
        if (runId == null) return;
        Run run = runs.find(runId);
        if (run == null || run.conversationId() == null) return;
        String sessionId = sessionForConversation(run.conversationId());
        if (sessionId == null) return;
        activeRuns.remove(sessionId, runId);
        touch(store.find(sessionId));
    }

    public void trackRecoveredRun(Run run) throws Exception {
        if (run == null || run.conversationId() == null) return;
        String sessionId = sessionForConversation(run.conversationId());
        if (sessionId != null && store.find(sessionId) != null) activeRuns.put(sessionId, run.id());
    }

    public void onRunResult(String runId, AgentRunResult result, Throwable error) throws Exception {
        if (runId == null) return;
        Run run = runs.find(runId);
        if (run == null || run.conversationId() == null) return;
        String sessionId = sessionForConversation(run.conversationId());
        if (sessionId != null) finish(sessionId, runId, result, error);
    }

    public SubAgentSession close(String id) throws Exception {
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            SubAgentSessionData current = require(id);
            String runId = activeRuns.get(id);
            if (runId != null) runner.cancel(runId);
            SubAgentSessionData closed = new SubAgentSessionData(current.id(), current.profileId(),
                    current.conversationId(), SubAgentSessionStatus.CLOSED, current.createdAt(), Instant.now());
            store.save(closed);
            return SubAgentSession.from(closed);
        }
    }

    public boolean delete(String id) throws Exception {
        Object lock = locks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            if (store.find(id) == null) return false;
            String runId = activeRuns.get(id);
            if (runId != null) runner.cancel(runId);
            store.delete(id);
            activeRuns.remove(id);
            locks.remove(id);
            return true;
        }
    }

    private void finish(String sessionId, String runId, AgentRunResult result, Throwable error) {
        if (error != null || result == null) {
            activeRuns.remove(sessionId, runId);
            try { touch(store.find(sessionId)); } catch (Exception ignored) { }
            return;
        }
        if (result.pendingApproval() != null) return;
        try {
            Run run = runs.find(runId);
            if (run != null && run.conversationId() != null && result.answer() != null && !result.answer().isBlank()) {
                contexts.append(run.conversationId(), ChatMessage.assistant(result.answer(), List.of(),
                        result.reasoningContent()));
            }
            activeRuns.remove(sessionId, runId);
            touch(store.find(sessionId));
        } catch (Exception ignored) {
            // Run persistence remains authoritative if the conversation write fails.
        }
    }

    private String sessionForConversation(String conversationId) throws Exception {
        return store.list().stream().filter(session -> conversationId.equals(session.conversationId()))
                .map(SubAgentSessionData::id).findFirst().orElse(null);
    }

    private int modelWindow(SubAgentProfileData profile) {
        return models.resolve(profile.modelId()).contextWindow();
    }

    private void touch(SubAgentSessionData session) throws Exception {
        if (session == null) return;
        store.save(new SubAgentSessionData(session.id(), session.profileId(), session.conversationId(),
                session.status(), session.createdAt(), Instant.now()));
    }

    private SubAgentSessionData requireOpen(String id) throws Exception {
        SubAgentSessionData session = require(id);
        if (session.status() != SubAgentSessionStatus.OPEN) {
            throw new IllegalStateException("sub-agent session is closed: " + id);
        }
        return session;
    }

    private SubAgentSessionData require(String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("session id must not be blank");
        SubAgentSessionData session = store.find(id);
        if (session == null) throw new IllegalArgumentException("unknown sub-agent session: " + id);
        return session;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
