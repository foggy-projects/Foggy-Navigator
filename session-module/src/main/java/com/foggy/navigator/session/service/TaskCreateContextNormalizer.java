package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Canonicalizes a strict local-A2A context before Provider effect. Existing
 * context and Session rows are read-only; only a missing context and, when
 * necessary, its new disposable Session are inserted.
 */
@Service
public class TaskCreateContextNormalizer {

    public static final String INTERNAL_PROOF_METADATA_KEY =
            TaskCreateContextNormalizer.class.getName() + ".canonical-context-proof";

    private static final String ACCESS_DENIED = "Resource access denied";
    private static final String CONTEXT_CHANGED = "CONTEXT_CHANGED_CONCURRENTLY_RETRY";
    private static final String EXTERNAL_A2A = "EXTERNAL_A2A";

    private final AgentConversationContextRepository contextRepository;
    private final SessionRepository sessionRepository;
    private final SessionTaskRepository sessionTaskRepository;
    private final SessionCodingAgentRepository codingAgentRepository;
    private final SessionTaskResourceAccessService resourceAccessService;
    private final List<TaskLookupProvider> taskLookupProviders;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    public TaskCreateContextNormalizer(
            AgentConversationContextRepository contextRepository,
            SessionRepository sessionRepository,
            SessionTaskRepository sessionTaskRepository,
            SessionCodingAgentRepository codingAgentRepository,
            SessionTaskResourceAccessService resourceAccessService,
            List<? extends TaskLookupProvider> taskLookupProviders,
            PlatformTransactionManager transactionManager) {
        this.contextRepository = Objects.requireNonNull(contextRepository, "contextRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.sessionTaskRepository = Objects.requireNonNull(sessionTaskRepository, "sessionTaskRepository");
        this.codingAgentRepository = Objects.requireNonNull(codingAgentRepository, "codingAgentRepository");
        this.resourceAccessService = Objects.requireNonNull(resourceAccessService, "resourceAccessService");
        this.taskLookupProviders = List.copyOf(
                Objects.requireNonNull(taskLookupProviders, "taskLookupProviders"));
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
    }

    /** Only reads durable state and builds an immutable existing/synthetic view. */
    @Nullable
    Inspection inspect(
            TaskDispatchRequest request,
            AgentResolveContext context,
            boolean directProviderRequested) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "resolve context must not be null");
        String ownerUserId = requireText(context.getUserId(), "authenticated userId is required");
        String tenantId = trimToNull(context.getTenantId());
        String requestedSessionId = mergeExact(
                "sessionId", request.getSessionId(), context.getSessionId());
        SessionEntity requestedSession = requestedSessionId == null
                ? null
                : resourceAccessService.requireOwnedSession(
                        requestedSessionId, ownerUserId, tenantId);
        String requestedContextId = rawText(request.getContextId());
        AgentConversationContextEntity byId = requestedContextId == null
                ? null : contextRepository.findById(requestedContextId).orElse(null);
        if (byId != null && !ownerUserId.equals(trimToNull(byId.getUserId()))) {
            throw new SecurityException(ACCESS_DENIED);
        }
        String logicalAgentId = mergeExact(
                "agentId",
                request.getAgentId(),
                requestedSession == null ? null : requestedSession.getAgentId(),
                byId == null ? null : byId.getTargetAgentId());
        String requestAlias = rawText(request.getContextAlias());

        if (logicalAgentId == null || DirectoryAgentId.isDirectoryAgent(logicalAgentId)) {
            if (requestAlias != null) {
                throw conflict("contextAlias requires a real logical A2A Agent");
            }
            return null;
        }
        CodingAgentEntity agent = requireOwnedAgent(
                logicalAgentId, ownerUserId, tenantId);
        if (EXTERNAL_A2A.equals(agent.getAgentType()) || directProviderRequested) {
            if (requestAlias != null) {
                throw conflict("contextAlias is not supported by Direct/external A2A");
            }
            return null;
        }
        if (trimToNull(agent.getWorkerId()) == null) {
            throw conflict("local A2A Agent has no exact physical Worker");
        }

        if (byId != null) {
            requireContextOwner(byId, ownerUserId, logicalAgentId);
        }
        AgentConversationContextEntity byAlias = requestAlias == null
                ? null
                : contextRepository
                .findByContextAliasAndUserIdAndTargetAgentId(
                        requestAlias, ownerUserId, logicalAgentId)
                .filter(row -> requestAlias.equals(row.getContextAlias()))
                .orElse(null);

        if (byId != null && byAlias != null
                && !Objects.equals(byId.getContextId(), byAlias.getContextId())) {
            throw conflict("contextId and contextAlias resolve to different contexts");
        }
        if (byId != null && requestAlias != null && byAlias == null) {
            throw conflict("an existing context cannot be assigned a new alias");
        }

        AgentConversationContextEntity existing = byAlias != null ? byAlias : byId;
        if (existing != null) {
            SessionFacts session = requireExistingContextSession(
                    existing,
                    requestedSessionId,
                    ownerUserId,
                    tenantId,
                    logicalAgentId);
            return Inspection.existing(
                    requestedContextId,
                    requestAlias,
                    ownerUserId,
                    tenantId,
                    logicalAgentId,
                    existing,
                    session);
        }

        String contextId = requestedContextId != null
                ? requestedContextId : UUID.randomUUID().toString();
        SessionFacts session = requestedSession != null
                ? SessionFacts.from(requestedSession)
                : SessionFacts.synthetic(
                        UUID.randomUUID().toString(), ownerUserId, tenantId, logicalAgentId);
        requireOwnerAndAgent(session, ownerUserId, tenantId, logicalAgentId);
        return Inspection.pending(
                requestedContextId,
                contextId,
                requestAlias,
                ownerUserId,
                tenantId,
                logicalAgentId,
                session,
                requestedSession == null);
    }

    CanonicalContextProof sealForResolution(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        requirePlanMatchesInspection(inspection, plan);
        if (inspection.existingContext != null) {
            validateExistingContextPlan(inspection, plan);
            boolean taskRowsPristine = requirePristineTaskRowsIfEligible(
                    inspection.existingContext, inspection.sessionFacts, plan);
            return proofFrom(
                    inspection,
                    inspection.existingContext,
                    inspection.sessionFacts,
                    plan,
                    false,
                    taskRowsPristine);
        }
        return claimPending(inspection, plan);
    }

    /**
     * Runs after Provider task persistence but before receipt result recording.
     * It changes only agentSessionRef and never invokes entity save/merge.
     */
    void completeAgentSessionRef(
            CanonicalContextProof proof,
            String returnedTaskId,
            @Nullable String providerSessionRef) {
        Objects.requireNonNull(proof, "canonical context proof is required");
        String taskId = requireRawText(returnedTaskId, "returned Task id is required");
        String desiredRef = rawText(providerSessionRef);
        if (desiredRef == null) {
            if (!requiresNew(() -> completionMatches(
                    proof, taskId, proof.agentSessionRef, true))) {
                throw contextChanged();
            }
            return;
        }
        if (proof.agentSessionRef != null
                && !proof.agentSessionRef.equals(desiredRef)) {
            throw contextChanged();
        }

        int updated = requiresNew(() -> executeCompletionUpdate(
                proof, taskId, desiredRef));
        if (updated == 1) {
            return;
        }
        if (updated != 0 || !requiresNew(() -> completionMatches(
                proof, taskId, desiredRef, false))) {
            throw contextChanged();
        }
    }

    private CanonicalContextProof claimPending(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        requirePlanMatchesInspection(inspection, plan);
        validatePendingSessionPlan(inspection.sessionFacts, plan, inspection.createSession);
        SessionEntity sessionCandidate = inspection.createSession
                ? newSessionCandidate(inspection, plan) : null;
        SessionFacts finalSession = inspection.createSession
                ? SessionFacts.from(sessionCandidate) : inspection.sessionFacts;
        AgentConversationContextEntity contextCandidate =
                newContextCandidate(inspection, plan, finalSession.id());
        boolean taskRowsPristine = requirePristineTaskRowsIfEligible(
                contextCandidate, finalSession, plan);

        try {
            requiresNew(() -> {
                if (sessionCandidate != null) {
                    entityManager.persist(sessionCandidate);
                }
                entityManager.persist(contextCandidate);
                entityManager.flush();
                return null;
            });
            return proofFrom(
                    inspection, contextCandidate, finalSession, plan, true,
                    taskRowsPristine);
        } catch (RuntimeException failure) {
            if (!isIntegrityConflict(failure)) {
                throw failure;
            }
            return requiresNew(() -> adoptWinner(inspection, plan, failure));
        }
    }

    private CanonicalContextProof adoptWinner(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            RuntimeException insertFailure) {
        AgentConversationContextEntity winner = inspection.requestAlias == null
                ? contextRepository.findById(inspection.canonicalContextId).orElse(null)
                : contextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                        inspection.requestAlias,
                        inspection.ownerUserId,
                        inspection.logicalAgentId)
                .filter(row -> inspection.requestAlias.equals(row.getContextAlias()))
                .orElse(null);
        if (winner == null) {
            throw insertFailure;
        }
        requireContextOwner(winner, inspection.ownerUserId, inspection.logicalAgentId);
        SessionFacts winnerSession = requireExistingContextSession(
                winner,
                null,
                inspection.ownerUserId,
                inspection.tenantId,
                inspection.logicalAgentId);
        validateWinnerAgainstPlan(winner, winnerSession, inspection, plan);
        if (isFreshWinner(winner, winnerSession)) {
            throw contextChanged();
        }
        boolean taskRowsPristine = requirePristineTaskRowsIfEligible(
                winner, winnerSession, plan);
        return proofFrom(
                inspection, winner, winnerSession, plan, false,
                taskRowsPristine);
    }

    private int executeCompletionUpdate(
            CanonicalContextProof proof,
            String taskId,
            String desiredRef) {
        StringBuilder jpql = new StringBuilder("""
                update AgentConversationContextEntity c
                   set c.agentSessionRef = :desiredRef
                 where c.contextId = :contextId
                   and c.userId = :userId
                   and c.targetAgentId = :agentId
                   and c.agentType = :providerType
                   and c.navigatorSessionId = :sessionId
                """);
        appendNullablePredicate(jpql, "c.contextAlias", "contextAlias", proof.contextAlias);
        appendNullablePredicate(jpql, "c.agentSessionRef", "oldRef", proof.agentSessionRef);
        jpql.append("""
                   and exists (
                       select s.id from SessionEntity s
                        where s.id = :sessionId
                          and s.userId = :userId
                          and s.agentId = :agentId
                          and s.providerType = :providerType
                          and s.currentWorkerId = :workerId
                          and s.latestTaskId = :taskId
                          and s.deletedAt is null
                """);
        appendNullablePredicate(jpql, "s.tenantId", "tenantId", proof.tenantId);
        appendNullablePredicate(jpql, "s.currentDirectoryId", "directoryId", proof.directoryId);
        appendNullablePredicate(jpql, "s.authModelConfigId", "modelConfigId", proof.sessionModelConfigId);
        appendNullablePredicate(jpql, "s.latestModel", "model", proof.model);
        appendNullablePredicate(jpql, "s.status", "sessionStatus", proof.sessionStatus);
        jpql.append(")");

        var update = entityManager.createQuery(jpql.toString())
                .setParameter("desiredRef", desiredRef)
                .setParameter("contextId", proof.contextId)
                .setParameter("userId", proof.ownerUserId)
                .setParameter("agentId", proof.logicalAgentId)
                .setParameter("providerType", proof.providerType)
                .setParameter("sessionId", proof.navigatorSessionId)
                .setParameter("workerId", proof.physicalWorkerId)
                .setParameter("taskId", taskId);
        setNullableParameter(update, "contextAlias", proof.contextAlias);
        setNullableParameter(update, "oldRef", proof.agentSessionRef);
        setNullableParameter(update, "tenantId", proof.tenantId);
        setNullableParameter(update, "directoryId", proof.directoryId);
        setNullableParameter(update, "modelConfigId", proof.sessionModelConfigId);
        setNullableParameter(update, "model", proof.model);
        setNullableParameter(update, "sessionStatus", proof.sessionStatus);
        return update.executeUpdate();
    }

    private boolean completionMatches(
            CanonicalContextProof proof,
            String taskId,
            @Nullable String desiredRef,
            boolean acceptFrozenRef) {
        AgentConversationContextEntity context = contextRepository
                .findById(proof.contextId).orElse(null);
        SessionEntity session = sessionRepository
                .findById(proof.navigatorSessionId).orElse(null);
        if (context == null || session == null
                || !proof.matchesContext(context,
                acceptFrozenRef ? proof.agentSessionRef : desiredRef)) {
            return false;
        }
        return proof.matchesCompletedSession(session, taskId);
    }

    private SessionEntity newSessionCandidate(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        SessionEntity session = new SessionEntity();
        session.setId(inspection.sessionFacts.id());
        session.setUserId(inspection.ownerUserId);
        session.setTenantId(inspection.tenantId);
        session.setAgentId(inspection.logicalAgentId);
        session.setProviderType(plan.providerType());
        session.setBindingSource("EXPLICIT_AGENT");
        session.setStatus("ACTIVE");
        session.setInteractionState("PROCESSING");
        session.setPinned(false);
        session.setCurrentWorkerId(isAppServer(plan.providerType())
                ? null : plan.physicalWorkerId());
        session.setCurrentDirectoryId(plan.directoryId());
        session.setAuthModelConfigId(plan.modelConfigId());
        session.setLatestModel(plan.model());
        return session;
    }

    private AgentConversationContextEntity newContextCandidate(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            String navigatorSessionId) {
        AgentConversationContextEntity context = new AgentConversationContextEntity();
        context.setContextId(inspection.canonicalContextId);
        context.setContextAlias(inspection.requestAlias);
        context.setUserId(inspection.ownerUserId);
        context.setTargetAgentId(inspection.logicalAgentId);
        context.setAgentType(plan.providerType());
        context.setNavigatorSessionId(navigatorSessionId);
        return context;
    }

    private CanonicalContextProof proofFrom(
            Inspection inspection,
            AgentConversationContextEntity context,
            SessionFacts session,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            boolean currentRequestCreated,
            boolean taskRowsPristine) {
        boolean firstTurn = rawText(context.getAgentSessionRef()) == null
                && session.latestTaskId() == null;
        boolean fieldPristineAffinityCandidate = isAppServer(plan.providerType())
                && isPristineAppServerCandidate(context, session);
        if (fieldPristineAffinityCandidate && !taskRowsPristine) {
            throw conflict("App Server task absence was not proven before Provider effect");
        }
        boolean affinityCandidate = fieldPristineAffinityCandidate && taskRowsPristine;
        return new CanonicalContextProof(
                requireRawText(context.getContextId(), "contextId is required"),
                rawText(context.getContextAlias()),
                inspection.ownerUserId,
                inspection.tenantId,
                inspection.logicalAgentId,
                plan.providerType(),
                requireText(session.id(), "Navigator Session id is required"),
                rawText(context.getAgentSessionRef()),
                plan.physicalWorkerId(),
                plan.directoryId(),
                trimToNull(session.modelConfigId()),
                plan.model(),
                session.status(),
                firstTurn,
                currentRequestCreated,
                affinityCandidate);
    }

    /**
     * Proves the two task stores used by the App Server runtime-initialization
     * guard are empty before the once-effect permit is consumed. This is a
     * read-only fail-closed check; it never repairs an existing Session.
     */
    private boolean requirePristineTaskRowsIfEligible(
            AgentConversationContextEntity context,
            SessionFacts session,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        if (!isAppServer(plan.providerType())
                || !isPristineAppServerCandidate(context, session)) {
            return false;
        }
        for (String codexProviderType : List.of(
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER)) {
            List<TaskLookupProvider> exactProviders = taskLookupProviders.stream()
                    .filter(provider -> codexProviderType.equals(
                            trimToNull(provider.getProviderType())))
                    .toList();
            if (exactProviders.size() != 1) {
                throw conflict("Codex task lookup coverage is not uniquely available: "
                        + codexProviderType);
            }
            List<?> providerTasks = exactProviders.get(0)
                    .listTasksBySession(session.id());
            if (providerTasks == null || !providerTasks.isEmpty()) {
                throw conflict("App Server Session is not pristine in Codex task stores");
            }
        }
        List<?> unifiedTasks = sessionTaskRepository
                .findBySessionIdOrderByCreatedAtDesc(session.id());
        if (unifiedTasks == null || !unifiedTasks.isEmpty()) {
            throw conflict("App Server Session is not pristine in the unified task store");
        }
        return true;
    }

    private void validateExistingContextPlan(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        String contextProvider = requireText(
                inspection.existingContext.getAgentType(), "context provider is missing");
        requireExact("context.provider", contextProvider, plan.providerType());
        validatePendingSessionPlan(inspection.sessionFacts, plan, false);
        if (inspection.sessionFacts.currentWorkerId() == null
                && !isPristineAppServerCandidate(
                inspection.existingContext, inspection.sessionFacts)) {
            throw conflict("existing context Session has no exact Worker binding");
        }
    }

    private void validatePendingSessionPlan(
            SessionFacts session,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            boolean synthetic) {
        requireOwnerAndAgent(
                session, plan.ownerUserId(), plan.tenantId(), plan.logicalAgentId());
        if (session.deleted() || "DELETED".equalsIgnoreCase(session.status())) {
            throw new SecurityException(ACCESS_DENIED);
        }
        if (synthetic) {
            return;
        }
        requireExact("session.providerType", session.providerType(), plan.providerType());
        boolean pristineAppServer = isAppServer(plan.providerType())
                && session.currentWorkerId() == null
                && session.latestTaskId() == null
                && rawText(session.providerStateJson()) == null;
        if (!pristineAppServer) {
            requireExact("session.currentWorkerId", session.currentWorkerId(), plan.physicalWorkerId());
        }
        requireExact("session.currentDirectoryId", session.directoryId(), plan.directoryId());
        requireExact("session.modelConfigId", session.modelConfigId(), plan.modelConfigId());
        requireExact("session.model", session.model(), plan.model());
        requireExact("session.status", session.status(), "ACTIVE");
    }

    private void validateWinnerAgainstPlan(
            AgentConversationContextEntity winner,
            SessionFacts session,
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        requireExact("winner.contextAlias", winner.getContextAlias(), inspection.requestAlias);
        requireExact("winner.provider", winner.getAgentType(), plan.providerType());
        validatePendingSessionPlan(session, plan, false);
        if (inspection.sessionFacts.persisted()
                && !inspection.sessionFacts.id().equals(session.id())) {
            throw contextChanged();
        }
    }

    private boolean isFreshWinner(
            AgentConversationContextEntity context,
            SessionFacts session) {
        return rawText(context.getAgentSessionRef()) == null
                && session.latestTaskId() == null
                && rawText(session.providerStateJson()) == null;
    }

    private boolean isPristineAppServerCandidate(
            AgentConversationContextEntity context,
            SessionFacts session) {
        return rawText(context.getAgentSessionRef()) == null
                && session.currentWorkerId() == null
                && session.latestTaskId() == null
                && rawText(session.providerStateJson()) == null;
    }

    private SessionFacts requireExistingContextSession(
            AgentConversationContextEntity context,
            @Nullable String requestedSessionId,
            String ownerUserId,
            @Nullable String tenantId,
            String logicalAgentId) {
        requireContextOwner(context, ownerUserId, logicalAgentId);
        requireText(context.getAgentType(), "context provider is missing");
        String sessionId = requireText(
                context.getNavigatorSessionId(), "context Navigator Session is missing");
        if (requestedSessionId != null && !requestedSessionId.equals(sessionId)) {
            throw conflict("context and request resolve to different Sessions");
        }
        SessionFacts facts = SessionFacts.from(resourceAccessService.requireOwnedSession(
                sessionId, ownerUserId, tenantId));
        requireOwnerAndAgent(facts, ownerUserId, tenantId, logicalAgentId);
        requireExact("context/session provider", context.getAgentType(), facts.providerType());
        return facts;
    }

    private CodingAgentEntity requireOwnedAgent(
            String agentId,
            String ownerUserId,
            @Nullable String tenantId) {
        CodingAgentEntity agent = codingAgentRepository
                .findByAgentIdAndUserId(agentId, ownerUserId)
                .orElseThrow(() -> new SecurityException(ACCESS_DENIED));
        if (!ownerUserId.equals(trimToNull(agent.getUserId()))
                || !Objects.equals(tenantId, trimToNull(agent.getTenantId()))) {
            throw new SecurityException(ACCESS_DENIED);
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("Agent is disabled: " + agentId);
        }
        return agent;
    }

    private void requireContextOwner(
            AgentConversationContextEntity context,
            String ownerUserId,
            String logicalAgentId) {
        if (!ownerUserId.equals(context.getUserId())
                || !logicalAgentId.equals(context.getTargetAgentId())) {
            throw new SecurityException(ACCESS_DENIED);
        }
    }

    private void requireOwnerAndAgent(
            SessionFacts session,
            String ownerUserId,
            @Nullable String tenantId,
            @Nullable String logicalAgentId) {
        if (!ownerUserId.equals(session.ownerUserId())
                || !Objects.equals(tenantId, session.tenantId())
                || !Objects.equals(logicalAgentId, session.logicalAgentId())) {
            throw new SecurityException(ACCESS_DENIED);
        }
    }

    private void requirePlanMatchesInspection(
            Inspection inspection,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        if (plan.executionRoute() != TaskCreateTargetResolver.ExecutionRoute.A2A
                || !inspection.ownerUserId.equals(plan.ownerUserId())
                || !Objects.equals(inspection.tenantId, plan.tenantId())
                || !inspection.logicalAgentId.equals(plan.logicalAgentId())
                || !inspection.sessionFacts.id().equals(plan.sessionId())) {
            throw contextChanged();
        }
    }

    private <T> T requiresNew(Supplier<T> work) {
        if (entityManager == null) {
            throw new IllegalStateException(
                    "EntityManager is unavailable; context normalization is fail-closed");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private static boolean isIntegrityConflict(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof DataIntegrityViolationException
                    || cursor instanceof EntityExistsException
                    || cursor.getClass().getSimpleName().contains("ConstraintViolation")
                    || cursor.getClass().getSimpleName().contains("IntegrityConstraint")) {
                return true;
            }
        }
        return false;
    }

    private static void appendNullablePredicate(
            StringBuilder jpql,
            String field,
            String parameter,
            @Nullable String value) {
        jpql.append(" and ").append(field);
        if (value == null) {
            jpql.append(" is null\n");
        } else {
            jpql.append(" = :").append(parameter).append('\n');
        }
    }

    private static void setNullableParameter(
            jakarta.persistence.Query query,
            String name,
            @Nullable String value) {
        if (value != null) {
            query.setParameter(name, value);
        }
    }

    private static boolean isAppServer(String providerType) {
        return ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER.equals(providerType);
    }

    private static void requireExact(
            String field,
            @Nullable String actual,
            @Nullable String expected) {
        if (!Objects.equals(trimToNull(actual), trimToNull(expected))) {
            throw conflict(field + " does not match the resolved plan");
        }
    }

    @Nullable
    private static String rawText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireRawText(@Nullable String value, String message) {
        String text = rawText(value);
        if (text == null) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(@Nullable String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw conflict(message);
        }
        return normalized;
    }

    @Nullable
    private static String mergeExact(String field, @Nullable String... values) {
        String resolved = null;
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                continue;
            }
            if (resolved != null && !resolved.equals(normalized)) {
                throw conflict(field + " resolves to conflicting values");
            }
            resolved = normalized;
        }
        return resolved;
    }

    private static IllegalArgumentException conflict(String detail) {
        return new IllegalArgumentException("CONTEXT_BINDING_CONFLICT: " + detail);
    }

    private static IllegalStateException contextChanged() {
        return new IllegalStateException(CONTEXT_CHANGED);
    }

    static final class Inspection {
        @Nullable private final String requestedContextId;
        private final String canonicalContextId;
        @Nullable private final String requestAlias;
        private final String ownerUserId;
        @Nullable private final String tenantId;
        private final String logicalAgentId;
        @Nullable private final AgentConversationContextEntity existingContext;
        private final SessionFacts sessionFacts;
        private final boolean createSession;

        private Inspection(
                @Nullable String requestedContextId,
                String canonicalContextId,
                @Nullable String requestAlias,
                String ownerUserId,
                @Nullable String tenantId,
                String logicalAgentId,
                @Nullable AgentConversationContextEntity existingContext,
                SessionFacts sessionFacts,
                boolean createSession) {
            this.requestedContextId = requestedContextId;
            this.canonicalContextId = canonicalContextId;
            this.requestAlias = requestAlias;
            this.ownerUserId = ownerUserId;
            this.tenantId = tenantId;
            this.logicalAgentId = logicalAgentId;
            this.existingContext = existingContext;
            this.sessionFacts = sessionFacts;
            this.createSession = createSession;
        }

        private static Inspection existing(
                @Nullable String requestedContextId,
                @Nullable String requestAlias,
                String ownerUserId,
                @Nullable String tenantId,
                String logicalAgentId,
                AgentConversationContextEntity context,
                SessionFacts session) {
            return new Inspection(
                    requestedContextId,
                    requireRawText(context.getContextId(), "contextId is required"),
                    requestAlias,
                    ownerUserId,
                    tenantId,
                    logicalAgentId,
                    context,
                    session,
                    false);
        }

        private static Inspection pending(
                @Nullable String requestedContextId,
                String canonicalContextId,
                @Nullable String requestAlias,
                String ownerUserId,
                @Nullable String tenantId,
                String logicalAgentId,
                SessionFacts session,
                boolean createSession) {
            return new Inspection(
                    requestedContextId,
                    canonicalContextId,
                    requestAlias,
                    ownerUserId,
                    tenantId,
                    logicalAgentId,
                    null,
                    session,
                    createSession);
        }

        void applyForResolution(TaskDispatchRequest request, AgentResolveContext context) {
            request.setContextId(canonicalContextId);
            request.setContextAlias(null);
            request.setAgentId(logicalAgentId);
            request.setSessionId(sessionFacts.id());
            context.setSessionId(sessionFacts.id());
        }

        SessionFacts sessionForResolution(String sessionId) {
            if (!sessionFacts.id().equals(sessionId)) {
                throw contextChanged();
            }
            return sessionFacts;
        }

        String canonicalContextId() {
            return canonicalContextId;
        }
    }

    static record SessionFacts(
            String id,
            String ownerUserId,
            @Nullable String tenantId,
            @Nullable String logicalAgentId,
            @Nullable String providerType,
            @Nullable String currentWorkerId,
            @Nullable String directoryId,
            @Nullable String modelConfigId,
            @Nullable String model,
            @Nullable String latestTaskId,
            @Nullable String providerStateJson,
            @Nullable String status,
            boolean deleted,
            boolean persisted) {

        static SessionFacts from(SessionEntity session) {
            return new SessionFacts(
                    requireText(session.getId(), "Session id is required"),
                    requireText(session.getUserId(), "Session owner is required"),
                    trimToNull(session.getTenantId()),
                    trimToNull(session.getAgentId()),
                    trimToNull(session.getProviderType()),
                    trimToNull(session.getCurrentWorkerId()),
                    trimToNull(session.getCurrentDirectoryId()),
                    trimToNull(session.getAuthModelConfigId()),
                    trimToNull(session.getLatestModel()),
                    trimToNull(session.getLatestTaskId()),
                    session.getProviderStateJson(),
                    trimToNull(session.getStatus()),
                    session.getDeletedAt() != null,
                    true);
        }

        private static SessionFacts synthetic(
                String id,
                String ownerUserId,
                @Nullable String tenantId,
                String logicalAgentId) {
            return new SessionFacts(
                    id,
                    ownerUserId,
                    tenantId,
                    logicalAgentId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ACTIVE",
                    false,
                    false);
        }
    }

    /** Process-local proof; clients cannot construct it or serialize it as authority. */
    public static final class CanonicalContextProof {
        private final String contextId;
        @Nullable private final String contextAlias;
        private final String ownerUserId;
        @Nullable private final String tenantId;
        private final String logicalAgentId;
        private final String providerType;
        private final String navigatorSessionId;
        @Nullable private final String agentSessionRef;
        private final String physicalWorkerId;
        @Nullable private final String directoryId;
        @Nullable private final String sessionModelConfigId;
        @Nullable private final String model;
        @Nullable private final String sessionStatus;
        private final boolean firstTurn;
        private final boolean currentRequestCreated;
        private final boolean runtimeAffinityInitializationEligible;

        private CanonicalContextProof(
                String contextId,
                @Nullable String contextAlias,
                String ownerUserId,
                @Nullable String tenantId,
                String logicalAgentId,
                String providerType,
                String navigatorSessionId,
                @Nullable String agentSessionRef,
                String physicalWorkerId,
                @Nullable String directoryId,
                @Nullable String sessionModelConfigId,
                @Nullable String model,
                @Nullable String sessionStatus,
                boolean firstTurn,
                boolean currentRequestCreated,
                boolean runtimeAffinityInitializationEligible) {
            this.contextId = requireRawText(contextId, "contextId is required");
            this.contextAlias = rawText(contextAlias);
            this.ownerUserId = requireText(ownerUserId, "ownerUserId is required");
            this.tenantId = trimToNull(tenantId);
            this.logicalAgentId = requireText(logicalAgentId, "logicalAgentId is required");
            this.providerType = requireText(providerType, "providerType is required");
            this.navigatorSessionId = requireText(navigatorSessionId, "Session id is required");
            this.agentSessionRef = rawText(agentSessionRef);
            this.physicalWorkerId = requireText(physicalWorkerId, "physical Worker is required");
            this.directoryId = trimToNull(directoryId);
            this.sessionModelConfigId = trimToNull(sessionModelConfigId);
            this.model = trimToNull(model);
            this.sessionStatus = trimToNull(sessionStatus);
            this.firstTurn = firstTurn;
            this.currentRequestCreated = currentRequestCreated;
            this.runtimeAffinityInitializationEligible = runtimeAffinityInitializationEligible;
        }

        public String contextId() { return contextId; }
        @Nullable public String contextAlias() { return contextAlias; }
        public String ownerUserId() { return ownerUserId; }
        @Nullable public String tenantId() { return tenantId; }
        public String logicalAgentId() { return logicalAgentId; }
        public String providerType() { return providerType; }
        public String navigatorSessionId() { return navigatorSessionId; }
        @Nullable public String agentSessionRef() { return agentSessionRef; }
        public String physicalWorkerId() { return physicalWorkerId; }
        @Nullable public String directoryId() { return directoryId; }
        @Nullable public String sessionModelConfigId() { return sessionModelConfigId; }
        @Nullable public String model() { return model; }
        public boolean firstTurn() { return firstTurn; }
        public boolean currentRequestCreated() { return currentRequestCreated; }
        public boolean runtimeAffinityInitializationEligible() {
            return runtimeAffinityInitializationEligible;
        }

        private boolean matchesContext(
                AgentConversationContextEntity context,
                @Nullable String expectedRef) {
            return contextId.equals(context.getContextId())
                    && Objects.equals(contextAlias, rawText(context.getContextAlias()))
                    && ownerUserId.equals(context.getUserId())
                    && logicalAgentId.equals(context.getTargetAgentId())
                    && providerType.equals(trimToNull(context.getAgentType()))
                    && navigatorSessionId.equals(trimToNull(context.getNavigatorSessionId()))
                    && Objects.equals(expectedRef, rawText(context.getAgentSessionRef()));
        }

        private boolean matchesCompletedSession(SessionEntity session, String taskId) {
            return navigatorSessionId.equals(trimToNull(session.getId()))
                    && ownerUserId.equals(trimToNull(session.getUserId()))
                    && Objects.equals(tenantId, trimToNull(session.getTenantId()))
                    && logicalAgentId.equals(trimToNull(session.getAgentId()))
                    && providerType.equals(trimToNull(session.getProviderType()))
                    && physicalWorkerId.equals(trimToNull(session.getCurrentWorkerId()))
                    && Objects.equals(directoryId, trimToNull(session.getCurrentDirectoryId()))
                    && Objects.equals(sessionModelConfigId, trimToNull(session.getAuthModelConfigId()))
                    && Objects.equals(model, trimToNull(session.getLatestModel()))
                    && Objects.equals(sessionStatus, trimToNull(session.getStatus()))
                    && taskId.equals(trimToNull(session.getLatestTaskId()))
                    && session.getDeletedAt() == null;
        }
    }
}
