package com.foggy.navigator.session.command;

import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity;
import com.foggy.navigator.session.command.repository.CommandOnceReceiptRepository;
import com.foggy.navigator.session.config.SessionModuleAutoConfiguration;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable once-receipt state authority for an exact, server-verified command binding.
 *
 * <p>This service owns no Provider callback and performs no dispatch. A {@link EffectPermit} with
 * disposition {@link BeginEffectDisposition#PERMITTED} is the only result that allows its caller
 * to proceed to the separately owned Provider-effect gate.</p>
 */
@Service
public class CommandOnceReceiptService {

    public static final String DIGEST_VERSION = "LP_UTF8_SHA256_V1";

    private static final String RECEIPT_DOMAIN = "navi.command-receipt.v1";
    private static final String BINDING_DOMAIN = "navi.command-binding.v1";
    private static final String AUTHORIZATION_BINDING_DOMAIN =
            "navi.command-authorization-binding.v1";

    private static final String BINDING_CONFLICT = "COMMAND_RECEIPT_BINDING_CONFLICT";
    private static final String AUTHORIZATION_CONFLICT =
            "COMMAND_RECEIPT_AUTHORIZATION_CONFLICT";
    private static final String RECEIPT_NOT_FOUND = "COMMAND_RECEIPT_NOT_FOUND";
    private static final String ATTEMPT_MISMATCH = "COMMAND_RECEIPT_ATTEMPT_MISMATCH";
    private static final String RESULT_CONFLICT = "COMMAND_RECEIPT_RESULT_CONFLICT";
    private static final String STATE_CONFLICT = "COMMAND_RECEIPT_STATE_CONFLICT";

    private final CommandOnceReceiptRepository receipts;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority authority;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final List<CommandReceiptTransactionFence> transactionFences;

    public CommandOnceReceiptService(
            CommandOnceReceiptRepository receipts,
            VerifiedCommandAuthorizationDecision.ServerAuthority authority,
            @Qualifier(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK)
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this(receipts, authority, clock, transactionManager, List.of());
    }

    @Autowired
    public CommandOnceReceiptService(
            CommandOnceReceiptRepository receipts,
            VerifiedCommandAuthorizationDecision.ServerAuthority authority,
            @Qualifier(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK)
            Clock clock,
            PlatformTransactionManager transactionManager,
            List<CommandReceiptTransactionFence> transactionFences) {
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.writes = requiresNew(transactionManager, false);
        this.reads = requiresNew(transactionManager, true);
        this.transactionFences = List.copyOf(Objects.requireNonNull(
                transactionFences, "transactionFences must not be null"));
    }

    public PrepareResult prepare(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        Candidate candidate = verifiedCandidate(envelope, decision);
        if (!hasTransactionFenceDomain(candidate.binding())) {
            return prepareWithoutFence(envelope, candidate);
        }
        CommandOnceReceiptEntity.ReceiptState observed = observeReceiptState(candidate);
        try {
            return writes.execute(status -> prepareFencedInTransaction(
                    envelope, candidate, observed));
        } catch (DataIntegrityViolationException duplicate) {
            CommandOnceReceiptEntity.ReceiptState recovered =
                    observeReceiptState(candidate);
            if (recovered == null) {
                throw duplicate;
            }
            return writes.execute(status -> prepareFencedInTransaction(
                    envelope, candidate, recovered));
        }
    }

    public EffectPermit beginEffect(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        Candidate candidate = verifiedCandidate(envelope, decision);
        if (!hasTransactionFenceDomain(candidate.binding())) {
            return writes.execute(status -> beginEffectInTransaction(
                    candidate,
                    CommandReceiptTransactionFence.LockedDomain.allowed()));
        }
        CommandOnceReceiptEntity.ReceiptState observed = observeReceiptState(candidate);
        if (observed == null) {
            throw conflict(RECEIPT_NOT_FOUND);
        }
        return writes.execute(status -> {
            CommandReceiptTransactionFence.LockedDomain lockedDomain =
                    observed == CommandOnceReceiptEntity.ReceiptState.PREPARED
                            ? lockClaimedDomain(candidate.binding())
                            : null;
            return beginEffectInTransaction(candidate, lockedDomain);
        });
    }

    private PrepareResult prepareWithoutFence(
            CanonicalCommandEnvelope envelope,
            Candidate candidate) {
        try {
            return writes.execute(status -> {
                CommandOnceReceiptEntity existing = receipts
                        .findByClientRequestId(candidate.clientRequestId())
                        .orElse(null);
                if (existing != null) {
                    requireStableBinding(existing, candidate);
                    return replayAfterStableBinding(existing, candidate);
                }
                return createPrepared(envelope, candidate);
            });
        } catch (DataIntegrityViolationException duplicate) {
            PrepareResult recovered = reads.execute(status -> receipts
                    .findByClientRequestId(candidate.clientRequestId())
                    .map(existing -> {
                        requireStableBinding(existing, candidate);
                        return replayAfterStableBinding(existing, candidate);
                    })
                    .orElse(null));
            if (recovered == null) {
                throw duplicate;
            }
            return recovered;
        }
    }

    private EffectPermit beginEffectInTransaction(
            Candidate candidate,
            CommandReceiptTransactionFence.LockedDomain lockedDomain) {
        CommandOnceReceiptEntity receipt = locked(candidate.receiptId());
        requireStableBinding(receipt, candidate);
        return switch (receipt.getState()) {
            case PREPARED -> {
                if (lockedDomain == null) {
                    throw conflict(STATE_CONFLICT);
                }
                lockedDomain.requireEligible();
                receipt.beginEffect(UUID.randomUUID().toString(), now());
                receipts.saveAndFlush(receipt);
                yield new EffectPermit(
                        BeginEffectDisposition.PERMITTED,
                        snapshot(receipt));
            }
            case EFFECT_STARTED -> new EffectPermit(
                    BeginEffectDisposition.ALREADY_STARTED,
                    snapshot(receipt));
            case RESULT_RECORDED -> new EffectPermit(
                    BeginEffectDisposition.RESULT_RECORDED,
                    snapshot(receipt));
            case AMBIGUOUS -> new EffectPermit(
                    BeginEffectDisposition.AMBIGUOUS,
                    snapshot(receipt));
        };
    }

    private PrepareResult prepareFencedInTransaction(
            CanonicalCommandEnvelope envelope,
            Candidate candidate,
            CommandOnceReceiptEntity.ReceiptState observed) {
        CommandReceiptTransactionFence.LockedDomain lockedDomain =
                observed == null
                        || observed == CommandOnceReceiptEntity.ReceiptState.PREPARED
                        ? lockClaimedDomain(candidate.binding())
                        : null;
        CommandOnceReceiptEntity existing = receipts
                .findByReceiptIdForUpdate(candidate.receiptId())
                .orElse(null);
        if (existing != null) {
            requireStableBinding(existing, candidate);
            if (existing.getState() == CommandOnceReceiptEntity.ReceiptState.PREPARED) {
                if (lockedDomain == null) {
                    throw conflict(STATE_CONFLICT);
                }
                lockedDomain.requireEligible();
            }
            return replayAfterStableBinding(existing, candidate);
        }
        if (observed != null) {
            throw conflict(RECEIPT_NOT_FOUND);
        }
        if (lockedDomain == null) {
            throw conflict(STATE_CONFLICT);
        }
        lockedDomain.requireEligible();
        return createPrepared(envelope, candidate);
    }

    private PrepareResult createPrepared(
            CanonicalCommandEnvelope envelope,
            Candidate candidate) {
        CommandOnceReceiptEntity created = CommandOnceReceiptEntity.prepared(
                candidate.receiptId(),
                envelope,
                DIGEST_VERSION,
                candidate.bindingDigest(),
                DIGEST_VERSION,
                candidate.authorizationBindingDigest(),
                now());
        receipts.saveAndFlush(created);
        return new PrepareResult(
                PrepareDisposition.CREATED,
                snapshot(created));
    }

    private boolean hasTransactionFenceDomain(
            CanonicalCommandEnvelope.CommandBinding binding) {
        return CommandReceiptTransactionFence
                .requiresOpenApiAgentTaskTerminationFence(binding)
                || transactionFences.stream().anyMatch(fence -> fence.claims(binding));
    }

    private CommandOnceReceiptEntity.ReceiptState observeReceiptState(
            Candidate candidate) {
        return reads.execute(status -> receipts
                .findByClientRequestId(candidate.clientRequestId())
                .map(receipt -> {
                    requireStableBinding(receipt, candidate);
                    return receipt.getState();
                })
                .orElse(null));
    }

    private CommandReceiptTransactionFence.LockedDomain lockClaimedDomain(
            CanonicalCommandEnvelope.CommandBinding binding) {
        List<CommandReceiptTransactionFence> claiming = transactionFences.stream()
                .filter(fence -> fence.claims(binding))
                .toList();
        boolean required = CommandReceiptTransactionFence
                .requiresOpenApiAgentTaskTerminationFence(binding);
        if (claiming.isEmpty()) {
            return required
                    ? CommandReceiptTransactionFence.LockedDomain.rejected(
                    "TERMINATION_MANAGEMENT_DOMAIN_FENCE_MISSING")
                    : CommandReceiptTransactionFence.LockedDomain.allowed();
        }
        if (claiming.size() != 1) {
            return CommandReceiptTransactionFence.LockedDomain.rejected(
                    "TERMINATION_COMMAND_DOMAIN_FENCE_CONFLICT");
        }
        return Objects.requireNonNull(
                claiming.get(0).lock(binding),
                "transaction fence locked domain must not be null");
    }

    public ReceiptSnapshot recordResult(
            String clientRequestId,
            String effectAttemptId,
            String opaqueResultReference,
            String safeCode) {
        String clientIdentity = requireReference(
                clientRequestId,
                CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH,
                "clientRequestId");
        String attempt = requireReference(effectAttemptId, 64, "effectAttemptId");
        String result = requireReference(opaqueResultReference, 320,
                "opaqueResultReference");
        String code = requireReference(safeCode, 128, "safeCode");
        return writes.execute(status -> {
            CommandOnceReceiptEntity receipt = locked(receiptId(clientIdentity));
            requireClientIdentity(receipt, clientIdentity);
            if (receipt.getState() == CommandOnceReceiptEntity.ReceiptState.RESULT_RECORDED) {
                requireAttempt(receipt, attempt);
                if (!Objects.equals(receipt.getOpaqueResultReference(), result)
                        || !Objects.equals(receipt.getSafeCode(), code)) {
                    throw conflict(RESULT_CONFLICT);
                }
                return snapshot(receipt);
            }
            if (receipt.getState() != CommandOnceReceiptEntity.ReceiptState.EFFECT_STARTED) {
                throw conflict(STATE_CONFLICT);
            }
            requireAttempt(receipt, attempt);
            receipt.recordResult(attempt, result, code, now());
            receipts.saveAndFlush(receipt);
            return snapshot(receipt);
        });
    }

    public ReceiptSnapshot markAmbiguous(
            String clientRequestId,
            String effectAttemptId,
            String safeCode) {
        String clientIdentity = requireReference(
                clientRequestId,
                CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH,
                "clientRequestId");
        String attempt = requireReference(effectAttemptId, 64, "effectAttemptId");
        String code = requireReference(safeCode, 128, "safeCode");
        return writes.execute(status -> {
            CommandOnceReceiptEntity receipt = locked(receiptId(clientIdentity));
            requireClientIdentity(receipt, clientIdentity);
            if (receipt.getState() == CommandOnceReceiptEntity.ReceiptState.AMBIGUOUS) {
                requireAttempt(receipt, attempt);
                if (!Objects.equals(receipt.getSafeCode(), code)) {
                    throw conflict(RESULT_CONFLICT);
                }
                return snapshot(receipt);
            }
            if (receipt.getState() != CommandOnceReceiptEntity.ReceiptState.EFFECT_STARTED) {
                throw conflict(STATE_CONFLICT);
            }
            requireAttempt(receipt, attempt);
            receipt.markAmbiguous(attempt, code, now());
            receipts.saveAndFlush(receipt);
            return snapshot(receipt);
        });
    }

    public Optional<ReceiptSnapshot> find(String clientRequestId) {
        String clientIdentity = requireReference(
                clientRequestId,
                CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH,
                "clientRequestId");
        Optional<ReceiptSnapshot> result = reads.execute(status -> receipts
                .findByClientRequestId(clientIdentity)
                .map(CommandOnceReceiptService::snapshot));
        return result == null ? Optional.empty() : result;
    }

    private Candidate verifiedCandidate(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        CanonicalCommandEnvelope.CommandBinding hidden =
                authority.requireVerified(envelope, decision);
        String envelopeBindingDigest = bindingDigest(
                envelope.schemaVersion(), envelope.binding());
        String hiddenBindingDigest = bindingDigest(envelope.schemaVersion(), hidden);
        if (!envelopeBindingDigest.equals(hiddenBindingDigest)) {
            throw conflict(AUTHORIZATION_CONFLICT);
        }
        CanonicalCommandEnvelope.AuthorizationMetadata authorization =
                envelope.authorizationMetadata();
        String authorizationBindingDigest = authorizationBindingDigest(
                authorization,
                envelopeBindingDigest);
        String clientRequestId = envelope.binding().request().clientRequestId();
        return new Candidate(
                clientRequestId,
                receiptId(clientRequestId),
                envelopeBindingDigest,
                authorizationBindingDigest,
                authorization,
                envelope.binding());
    }

    private PrepareResult replayAfterStableBinding(
            CommandOnceReceiptEntity existing,
            Candidate candidate) {
        PrepareDisposition disposition = sameInitialAuthorization(
                existing, candidate.authorization())
                ? PrepareDisposition.EXACT_REPLAY
                : PrepareDisposition.AUTHORIZATION_RENEWAL_ACCEPTED;
        return new PrepareResult(disposition, snapshot(existing));
    }

    private static void requireStableBinding(
            CommandOnceReceiptEntity receipt,
            Candidate candidate) {
        requireClientIdentity(receipt, candidate.clientRequestId());
        if (!Objects.equals(receipt.getReceiptId(), candidate.receiptId())
                || !Objects.equals(receipt.getBindingDigestVersion(), DIGEST_VERSION)
                || !Objects.equals(receipt.getBindingDigest(), candidate.bindingDigest())) {
            throw conflict(BINDING_CONFLICT);
        }
        if (!Objects.equals(
                receipt.getAuthorizationBindingDigestVersion(), DIGEST_VERSION)
                || !Objects.equals(
                receipt.getAuthorizationBindingDigest(),
                candidate.authorizationBindingDigest())) {
            throw conflict(AUTHORIZATION_CONFLICT);
        }
        if (Objects.equals(
                receipt.getAuthorizationDecisionId(),
                candidate.authorization().decisionId())
                && !sameInitialAuthorization(receipt, candidate.authorization())) {
            throw conflict(AUTHORIZATION_CONFLICT);
        }
    }

    private static boolean sameInitialAuthorization(
            CommandOnceReceiptEntity receipt,
            CanonicalCommandEnvelope.AuthorizationMetadata authorization) {
        return Objects.equals(
                receipt.getAuthorizationMetadataSchemaVersion(),
                authorization.schemaVersion())
                && Objects.equals(
                receipt.getAuthorizationDecisionId(),
                authorization.decisionId())
                && Objects.equals(
                receipt.getAuthorizationPolicyVersion(),
                authorization.policyVersion())
                && Objects.equals(
                receipt.getAuthorizationCorrelationId(),
                authorization.correlationId())
                && Objects.equals(receipt.getAuthorizationIssuedAt(), authorization.issuedAt())
                && Objects.equals(
                receipt.getAuthorizationNotBefore(), authorization.notBefore())
                && Objects.equals(receipt.getAuthorizationExpiresAt(), authorization.expiresAt());
    }

    private CommandOnceReceiptEntity locked(String receiptId) {
        return receipts.findByReceiptIdForUpdate(receiptId)
                .orElseThrow(() -> conflict(RECEIPT_NOT_FOUND));
    }

    private static void requireClientIdentity(
            CommandOnceReceiptEntity receipt,
            String expectedClientRequestId) {
        if (!Objects.equals(receipt.getClientRequestId(), expectedClientRequestId)) {
            throw conflict(BINDING_CONFLICT);
        }
    }

    private static void requireAttempt(
            CommandOnceReceiptEntity receipt,
            String expectedAttemptId) {
        if (!Objects.equals(receipt.getEffectAttemptId(), expectedAttemptId)) {
            throw conflict(ATTEMPT_MISMATCH);
        }
    }

    private static ReceiptSnapshot snapshot(CommandOnceReceiptEntity receipt) {
        return new ReceiptSnapshot(
                receipt.getReceiptId(),
                receipt.getClientRequestId(),
                ReceiptState.valueOf(receipt.getReceiptState()),
                receipt.getEffectAttemptId(),
                receipt.getOpaqueResultReference(),
                receipt.getSafeCode(),
                receipt.getAuthorizationDecisionId(),
                receipt.getAuthorizationIssuedAt(),
                receipt.getAuthorizationNotBefore(),
                receipt.getAuthorizationExpiresAt(),
                receipt.getPreparedAt(),
                receipt.getEffectStartedAt(),
                receipt.getResultRecordedAt(),
                receipt.getAmbiguousAt(),
                receipt.getRowVersion());
    }

    private static String receiptId(String clientRequestId) {
        return new CanonicalDigest(RECEIPT_DOMAIN)
                .field(clientRequestId)
                .finish();
    }

    private static String bindingDigest(
            String schemaVersion,
            CanonicalCommandEnvelope.CommandBinding binding) {
        return new CanonicalDigest(BINDING_DOMAIN)
                .field(schemaVersion)
                .field(name(binding.commandKind()))
                .field(name(binding.ingress().ingress()))
                .field(binding.ingress().clientSurface())
                .field(binding.ingress().routeId())
                .field(binding.request().clientRequestId())
                .field(binding.request().idempotencyKey())
                .field(binding.request().correlationId())
                .field(name(binding.actor().kind()))
                .field(name(binding.actor().principalType()))
                .field(name(binding.actor().lane()))
                .field(binding.actor().fingerprint())
                .field(binding.actor().serverProcessAuthorityReference())
                .field(binding.ownership().tenantReference())
                .field(binding.ownership().ownerReference())
                .field(binding.ownership().clientAppReference())
                .field(binding.ownership().upstreamReference())
                .field(name(binding.target().kind()))
                .field(binding.target().targetId())
                .field(binding.target().logicalAgentId())
                .field(binding.target().providerType())
                .field(binding.target().physicalWorkerId())
                .field(binding.target().modelConfigId())
                .field(binding.target().taskId())
                .field(binding.target().sessionId())
                .field(binding.effect().actionId())
                .field(binding.effect().effectScopeReference())
                .finish();
    }

    private static String authorizationBindingDigest(
            CanonicalCommandEnvelope.AuthorizationMetadata authorization,
            String bindingDigest) {
        return new CanonicalDigest(AUTHORIZATION_BINDING_DOMAIN)
                .field(authorization.schemaVersion())
                .field(authorization.policyVersion())
                .field(authorization.correlationId())
                .field(DIGEST_VERSION)
                .field(bindingDigest)
                .finish();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager,
            boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private static String requireReference(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static CommandReceiptConflictException conflict(String safeCode) {
        return new CommandReceiptConflictException(safeCode);
    }

    public enum PrepareDisposition {
        CREATED,
        EXACT_REPLAY,
        AUTHORIZATION_RENEWAL_ACCEPTED
    }

    public enum BeginEffectDisposition {
        PERMITTED,
        ALREADY_STARTED,
        RESULT_RECORDED,
        AMBIGUOUS
    }

    public enum ReceiptState {
        PREPARED,
        EFFECT_STARTED,
        RESULT_RECORDED,
        AMBIGUOUS
    }

    public record ReceiptSnapshot(
            String receiptId,
            String clientRequestId,
            ReceiptState state,
            String effectAttemptId,
            String opaqueResultReference,
            String safeCode,
            String initialAuthorizationDecisionId,
            Instant initialAuthorizationIssuedAt,
            Instant initialAuthorizationNotBefore,
            Instant initialAuthorizationExpiresAt,
            LocalDateTime preparedAt,
            LocalDateTime effectStartedAt,
            LocalDateTime resultRecordedAt,
            LocalDateTime ambiguousAt,
            long rowVersion) {
    }

    public record PrepareResult(
            PrepareDisposition disposition,
            ReceiptSnapshot snapshot) {
    }

    public static final class EffectPermit {

        private final BeginEffectDisposition disposition;
        private final ReceiptSnapshot snapshot;

        private EffectPermit(
                BeginEffectDisposition disposition,
                ReceiptSnapshot snapshot) {
            this.disposition = Objects.requireNonNull(
                    disposition, "disposition must not be null");
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        }

        public BeginEffectDisposition disposition() {
            return disposition;
        }

        public ReceiptSnapshot snapshot() {
            return snapshot;
        }

        public boolean providerEffectPermitted() {
            return disposition == BeginEffectDisposition.PERMITTED;
        }
    }

    public static final class CommandReceiptConflictException extends IllegalStateException {

        private final String safeCode;

        private CommandReceiptConflictException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }

    private record Candidate(
            String clientRequestId,
            String receiptId,
            String bindingDigest,
            String authorizationBindingDigest,
            CanonicalCommandEnvelope.AuthorizationMetadata authorization,
            CanonicalCommandEnvelope.CommandBinding binding) {
    }

    private static final class CanonicalDigest {

        private final MessageDigest digest;

        private CanonicalDigest(String domain) {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 is unavailable", unavailable);
            }
            field(domain);
        }

        private CanonicalDigest field(String value) {
            if (value == null) {
                digest.update((byte) 0);
                return this;
            }
            digest.update((byte) 1);
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            int length = encoded.length;
            digest.update((byte) (length >>> 24));
            digest.update((byte) (length >>> 16));
            digest.update((byte) (length >>> 8));
            digest.update((byte) length);
            digest.update(encoded);
            return this;
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
