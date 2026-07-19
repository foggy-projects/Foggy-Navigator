package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.auth.authorization.LegacyAuthorizationContextAdapter;
import com.foggy.navigator.common.authorization.AuthorizationContextV1;
import com.foggy.navigator.common.authorization.AuthorizationDecisionAuditStore;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.AuthorizationShadowEvaluator;
import com.foggy.navigator.common.authorization.LegacyEnforcementOutcome;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.auth.authorization.AuthorizationIngressRouteResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * P1A observes the final legacy result but never becomes an authorization
 * gate. All evaluator and audit failures are intentionally swallowed so this
 * interceptor cannot alter a legacy response, status, exception, or side
 * effect.
 */
@Component
@RequiredArgsConstructor
public class AuthorizationShadowInterceptor implements HandlerInterceptor {

    private static final String ELIGIBLE_ATTRIBUTE =
            AuthorizationShadowInterceptor.class.getName() + ".eligible";
    private static final String COMPLETED_ATTRIBUTE =
            AuthorizationShadowInterceptor.class.getName() + ".completed";
    private static final String RESOLVED_ROUTE_ATTRIBUTE =
            AuthorizationShadowInterceptor.class.getName() + ".resolvedRoute";
    static final String LEGACY_RX_OUTCOME_ATTRIBUTE =
            AuthorizationShadowInterceptor.class.getName() + ".legacyRxOutcome";

    private final LegacyAuthorizationContextAdapter contextAdapter;
    private final AuthorizationIngressRouteResolver ingressRouteResolver;
    private final AuthorizationShadowEvaluator shadowEvaluator;
    private final AuthorizationDecisionAuditStore auditStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            AuthorizationRouteManifestEntry resolvedRoute = ingressRouteResolver.resolve(request).orElse(null);
            if (resolvedRoute != null) {
                request.setAttribute(RESOLVED_ROUTE_ATTRIBUTE, resolvedRoute);
            }
            if (handler instanceof HandlerMethod || resolvedRoute != null) {
                request.setAttribute(ELIGIBLE_ATTRIBUTE, Boolean.TRUE);
            }
        } catch (RuntimeException ignored) {
            // Route observation is strictly best-effort and cannot reject a legacy request.
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        AuthorizationRouteManifestEntry resolvedRoute = resolvedRoute(request);
        if (!(handler instanceof HandlerMethod || resolvedRoute != null)
                || !Boolean.TRUE.equals(request.getAttribute(ELIGIBLE_ATTRIBUTE))
                || Boolean.TRUE.equals(request.getAttribute(COMPLETED_ATTRIBUTE))) {
            return;
        }
        request.setAttribute(COMPLETED_ATTRIBUTE, Boolean.TRUE);

        try {
            AuthorizationContextV1 context = resolvedRoute == null
                    ? contextAdapter.adapt(request)
                    : contextAdapter.adapt(request, resolvedRoute);
            PolicyDecisionV1 canonicalDecision = shadowEvaluator.evaluate(context);
            LegacyEnforcementOutcome legacyOutcome = legacyOutcome(request, response.getStatus());
            if (isWebSocket(resolvedRoute)) {
                legacyOutcome = webSocketLegacyOutcome(response.getStatus());
            }
            auditStore.appendShadow(
                    context,
                    canonicalDecision,
                    legacyOutcome,
                    response.getStatus());
        } catch (RuntimeException ignored) {
            // Deliberately no logging: a shadow failure must not reveal request material or affect legacy handling.
        }
    }

    static void recordLegacyRxOutcome(HttpServletRequest request, boolean successful) {
        if (request != null) {
            request.setAttribute(LEGACY_RX_OUTCOME_ATTRIBUTE,
                    successful ? LegacyEnforcementOutcome.ALLOW : LegacyEnforcementOutcome.UNKNOWN);
        }
    }

    private static LegacyEnforcementOutcome legacyOutcome(HttpServletRequest request, int status) {
        if (status >= 400 && status < 600) {
            return LegacyEnforcementOutcome.DENY;
        }
        Object rxOutcome = request.getAttribute(LEGACY_RX_OUTCOME_ATTRIBUTE);
        if (rxOutcome instanceof LegacyEnforcementOutcome outcome) {
            return outcome;
        }
        if (status >= 200 && status < 400) {
            return LegacyEnforcementOutcome.ALLOW;
        }
        return LegacyEnforcementOutcome.UNKNOWN;
    }

    private static AuthorizationRouteManifestEntry resolvedRoute(HttpServletRequest request) {
        Object value = request.getAttribute(RESOLVED_ROUTE_ATTRIBUTE);
        return value instanceof AuthorizationRouteManifestEntry entry ? entry : null;
    }

    private static boolean isWebSocket(AuthorizationRouteManifestEntry route) {
        return route != null && "WEBSOCKET".equalsIgnoreCase(route.httpMethod());
    }

    /**
     * A WebSocket upgrade has no ordinary MVC response semantics. In P1A only
     * the explicit switching-protocols status is observed as an allow; an
     * unset/default status must remain unknown rather than becoming a shadow
     * allow.
     */
    private static LegacyEnforcementOutcome webSocketLegacyOutcome(int status) {
        if (status == 101) {
            return LegacyEnforcementOutcome.ALLOW;
        }
        if (status >= 400 && status < 600) {
            return LegacyEnforcementOutcome.DENY;
        }
        return LegacyEnforcementOutcome.UNKNOWN;
    }

}
