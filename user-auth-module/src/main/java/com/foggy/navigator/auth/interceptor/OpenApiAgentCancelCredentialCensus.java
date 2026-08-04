package com.foggy.navigator.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerMapping;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-free credential-family census for the dual-lane OpenAPI Agent cancel route.
 *
 * <p>The census runs before JWT/API-key resolution. It records only the selected lane and
 * header aliases; raw credential values remain exclusively on the servlet request.</p>
 */
public final class OpenApiAgentCancelCredentialCensus {

    public static final String ROUTE_PATTERN =
            "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel";
    public static final String DECISION_ATTRIBUTE =
            OpenApiAgentCancelCredentialCensus.class.getName() + ".decision";

    public static final String CREDENTIAL_REQUIRED =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_REQUIRED";
    public static final String CREDENTIAL_MIXED =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_MIXED";
    public static final String CREDENTIAL_AMBIGUOUS =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_AMBIGUOUS";
    public static final String CREDENTIAL_MALFORMED =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_MALFORMED";
    public static final String CREDENTIAL_LANE_REJECTED =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_LANE_REJECTED";
    public static final String CREDENTIAL_CENSUS_MISSING =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_CENSUS_MISSING";
    public static final String CREDENTIAL_CENSUS_DRIFT =
            "OPEN_API_AGENT_CANCEL_CREDENTIAL_CENSUS_DRIFT";

    private static final String AUTHORIZATION = "Authorization";
    private static final String QUERY_TOKEN = "token";
    private static final String API_KEY = "X-API-Key";
    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String REQUEST_ID = "X-Navigator-Client-Request-Id";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final List<String> APP_KEY_HEADERS = List.of(
            "X-Client-App-Key", "X-App-Key", "X-Foggy-App-Key");
    private static final List<String> ACCESS_TOKEN_HEADERS = List.of(
            "X-Client-App-Access-Token",
            "X-App-Access-Token",
            "X-Foggy-App-Access-Token");
    private static final List<String> UPSTREAM_USER_HEADERS = List.of(
            "X-Upstream-User-Id",
            "X-Foggy-Upstream-User-Id",
            "X-Client-Upstream-User-Id");
    private static final List<String> FOREIGN_CREDENTIAL_HEADERS = List.of(
            "X-Navigator-API-Key",
            "X-Sharing-Key",
            "X-Navi-Principal-Credential",
            "X-Navi-Admin-Key",
            "X-Navi-Admin-Api-Key",
            "X-Navi-Operator-Key",
            "X-Navi-Operator-Api-Key",
            "X-Client-App-Control-Key",
            "X-Client-App-Secret",
            "X-App-Secret",
            "X-Foggy-App-Secret",
            "X-Task-Token",
            "X-Task-Scoped-Token",
            "X-Worker-Token",
            "X-Navigator-Worker-Id",
            "X-Navigator-Worker-Credential",
            "X-Navigator-Worker-Lease-Id",
            "X-Navigator-Expected-Physical-Worker-Id",
            "X-Worker-Id",
            "X-Worker-Local-Path",
            "X-Navi-Tenant",
            "X-Navigator-Tenant-Id",
            "X-Navigator-Client-App-Id",
            "X-Tenant",
            "X-Platform-Admin-Key",
            "X-System-Admin-Key",
            "X-Operator-Token",
            "X-Principal-Token",
            "X-TMS-Agent-Token",
            "X-World-Sim-Token",
            "X-World-Sim-User-Token");
    private static final Set<String> FOREIGN_QUERY_PARAMETERS = Set.of(
            "tenantid",
            "targettenantid",
            "clientappid",
            "upstreamsystemid",
            "sourcesystem",
            "sourcetenantid",
            "owneruserid",
            "userid",
            "provider",
            "providertype",
            "workerid",
            "force");
    private static final Pattern FALLBACK_ROUTE = Pattern.compile(
            "^/api/v1/open/agents/[^/]+/tasks/[^/]+/cancel$");

    private OpenApiAgentCancelCredentialCensus() {
    }

    /** Returns {@code null} for every route outside the exact Agent cancel ingress. */
    @Nullable
    public static Decision inspect(HttpServletRequest request) {
        if (!matches(request)) {
            return null;
        }

        if (hasFormCredentialCarrier(request)) {
            return Decision.reject(CREDENTIAL_LANE_REJECTED);
        }
        QueryCensus query = query(request.getQueryString());
        if (query.malformed()) {
            return Decision.reject(CREDENTIAL_MALFORMED);
        }
        if (hasForeignCredential(request) || hasForeignQueryParameter(query)) {
            return Decision.reject(CREDENTIAL_LANE_REJECTED);
        }
        HeaderProbe requestId = header(request, REQUEST_ID);
        if (requestId.repeated()) {
            return Decision.reject(CREDENTIAL_AMBIGUOUS);
        }

        HeaderProbe authorization = header(request, AUTHORIZATION);
        ParameterProbe queryToken = query.parameter(QUERY_TOKEN);
        HeaderProbe apiKey = header(request, API_KEY);
        HeaderGroup appKey = headerGroup(request, APP_KEY_HEADERS);
        HeaderGroup accessToken = headerGroup(request, ACCESS_TOKEN_HEADERS);
        HeaderGroup upstreamUser = headerGroup(request, UPSTREAM_USER_HEADERS);

        int managementSources = presentCount(
                authorization.present(), queryToken.present(), apiKey.present());
        boolean runtimePresent = appKey.present() || accessToken.present() || upstreamUser.present();
        if (managementSources > 0 && runtimePresent) {
            return Decision.reject(CREDENTIAL_MIXED);
        }
        if (managementSources > 1
                || authorization.repeated()
                || queryToken.repeated()
                || apiKey.repeated()
                || appKey.ambiguous()
                || accessToken.ambiguous()
                || upstreamUser.ambiguous()) {
            return Decision.reject(CREDENTIAL_AMBIGUOUS);
        }

        if (managementSources == 1) {
            if (authorization.present()
                    && (!authorization.hasText()
                    || !authorization.value().startsWith(BEARER_PREFIX)
                    || authorization.value().substring(BEARER_PREFIX.length()).isBlank())) {
                return Decision.reject(CREDENTIAL_MALFORMED);
            }
            if ((queryToken.present() && !queryToken.hasText())
                    || (apiKey.present() && !apiKey.hasText())) {
                return Decision.reject(CREDENTIAL_MALFORMED);
            }
            ManagementSource source = authorization.present()
                    ? ManagementSource.BEARER
                    : queryToken.present()
                    ? ManagementSource.QUERY_TOKEN
                    : ManagementSource.API_KEY;
            return Decision.forManagement(source);
        }

        if (runtimePresent) {
            if (header(request, TENANT_ID).present()) {
                return Decision.reject(CREDENTIAL_LANE_REJECTED);
            }
            if (!appKey.present() || !accessToken.present() || !upstreamUser.present()) {
                return Decision.reject(CREDENTIAL_REQUIRED);
            }
            if (!appKey.hasText() || !accessToken.hasText() || !upstreamUser.hasText()) {
                return Decision.reject(CREDENTIAL_MALFORMED);
            }
            return Decision.forRuntimeAccess(
                    appKey.selectedName(),
                    accessToken.selectedName(),
                    upstreamUser.selectedName());
        }
        return Decision.reject(CREDENTIAL_REQUIRED);
    }

    public static void store(HttpServletRequest request, Decision decision) {
        Objects.requireNonNull(request, "request must not be null")
                .setAttribute(DECISION_ATTRIBUTE,
                        Objects.requireNonNull(decision, "decision must not be null"));
    }

    public static Decision requireStored(HttpServletRequest request) {
        Object value = Objects.requireNonNull(request, "request must not be null")
                .getAttribute(DECISION_ATTRIBUTE);
        if (value instanceof Decision decision) {
            return decision;
        }
        throw new SecurityException(CREDENTIAL_CENSUS_MISSING);
    }

    /**
     * Returns the exact raw-query credential selected by a content-free management decision.
     * Servlet parameter APIs are deliberately not used because they merge query and form data.
     */
    public static String requireSelectedManagementQueryToken(
            HttpServletRequest request,
            Decision decision) {
        if (request == null
                || decision == null
                || !decision.management()
                || decision.managementSource() != ManagementSource.QUERY_TOKEN) {
            throw new SecurityException(CREDENTIAL_CENSUS_DRIFT);
        }
        QueryCensus query = query(request.getQueryString());
        ParameterProbe token = query.parameter(QUERY_TOKEN);
        if (query.malformed()
                || !token.present()
                || token.repeated()
                || !token.hasText()) {
            throw new SecurityException(CREDENTIAL_CENSUS_DRIFT);
        }
        return token.value();
    }

    private static boolean matches(HttpServletRequest request) {
        if (request == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return ROUTE_PATTERN.equals(pattern.toString());
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return FALLBACK_ROUTE.matcher(uri).matches();
    }

    private static boolean hasForeignCredential(HttpServletRequest request) {
        for (String name : FOREIGN_CREDENTIAL_HEADERS) {
            if (header(request, name).present()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForeignQueryParameter(QueryCensus query) {
        return query.parameters().stream()
                .map(QueryParameter::name)
                .map(OpenApiAgentCancelCredentialCensus::normalizeName)
                .anyMatch(FOREIGN_QUERY_PARAMETERS::contains);
    }

    private static boolean hasFormCredentialCarrier(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("application/x-www-form-urlencoded")
                || normalized.startsWith("multipart/");
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replace("-", "")
                .replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static int presentCount(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static HeaderGroup headerGroup(HttpServletRequest request, List<String> names) {
        List<HeaderProbe> present = names.stream()
                .map(name -> header(request, name))
                .filter(HeaderProbe::present)
                .toList();
        if (present.isEmpty()) {
            return HeaderGroup.absent();
        }
        HeaderProbe selected = present.get(0);
        return new HeaderGroup(
                true,
                present.size() != 1 || selected.repeated(),
                selected.hasText(),
                selected.name());
    }

    private static HeaderProbe header(HttpServletRequest request, String name) {
        List<String> values = new ArrayList<>();
        Enumeration<String> enumeration = request.getHeaders(name);
        if (enumeration != null) {
            values.addAll(Collections.list(enumeration));
        }
        if (values.isEmpty()) {
            String single = request.getHeader(name);
            if (single != null) {
                values.add(single);
            }
        }
        if (values.isEmpty()) {
            return HeaderProbe.absent(name);
        }
        return new HeaderProbe(
                name,
                true,
                values.size() != 1 || values.stream()
                        .anyMatch(value -> value != null && value.contains(",")),
                values.size() == 1 ? values.get(0) : null);
    }

    private static QueryCensus query(@Nullable String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return QueryCensus.empty();
        }
        List<QueryParameter> parameters = new ArrayList<>();
        try {
            for (String pair : rawQuery.split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String encodedName = separator >= 0 ? pair.substring(0, separator) : pair;
                String encodedValue = separator >= 0 ? pair.substring(separator + 1) : "";
                parameters.add(new QueryParameter(
                        URLDecoder.decode(encodedName, StandardCharsets.UTF_8),
                        URLDecoder.decode(encodedValue, StandardCharsets.UTF_8)));
            }
            return new QueryCensus(List.copyOf(parameters), false);
        } catch (IllegalArgumentException malformed) {
            return new QueryCensus(List.of(), true);
        }
    }

    public enum Lane {
        MANAGEMENT,
        RUNTIME_ACCESS,
        REJECTED
    }

    public enum ManagementSource {
        BEARER,
        QUERY_TOKEN,
        API_KEY
    }

    /** Content-free decision; record output contains aliases and safe codes only. */
    public record Decision(
            Lane lane,
            @Nullable String rejectionCode,
            @Nullable ManagementSource managementSource,
            @Nullable String appKeyHeader,
            @Nullable String accessTokenHeader,
            @Nullable String upstreamUserHeader) {

        public Decision {
            Objects.requireNonNull(lane, "lane must not be null");
            if (lane == Lane.REJECTED) {
                if (rejectionCode == null || rejectionCode.isBlank()) {
                    throw new IllegalArgumentException("rejection code is required");
                }
                managementSource = null;
                appKeyHeader = null;
                accessTokenHeader = null;
                upstreamUserHeader = null;
            } else if (rejectionCode != null) {
                throw new IllegalArgumentException("accepted decision cannot have a rejection code");
            } else if (lane == Lane.MANAGEMENT && managementSource == null) {
                throw new IllegalArgumentException("management credential source is required");
            } else if (lane == Lane.RUNTIME_ACCESS
                    && (isBlank(appKeyHeader)
                    || isBlank(accessTokenHeader)
                    || isBlank(upstreamUserHeader))) {
                throw new IllegalArgumentException("runtime header aliases are required");
            } else if (lane == Lane.RUNTIME_ACCESS) {
                managementSource = null;
            }
        }

        static Decision forManagement(ManagementSource source) {
            return new Decision(
                    Lane.MANAGEMENT, null, source, null, null, null);
        }

        static Decision forRuntimeAccess(
                String appKeyHeader,
                String accessTokenHeader,
                String upstreamUserHeader) {
            return new Decision(
                    Lane.RUNTIME_ACCESS,
                    null,
                    null,
                    appKeyHeader,
                    accessTokenHeader,
                    upstreamUserHeader);
        }

        static Decision reject(String safeCode) {
            return new Decision(
                    Lane.REJECTED, safeCode, null, null, null, null);
        }

        public boolean management() {
            return lane == Lane.MANAGEMENT;
        }

        public boolean runtimeAccess() {
            return lane == Lane.RUNTIME_ACCESS;
        }

        public boolean rejected() {
            return lane == Lane.REJECTED;
        }
    }

    private record HeaderProbe(
            String name,
            boolean present,
            boolean repeated,
            @Nullable String value) {
        static HeaderProbe absent(String name) {
            return new HeaderProbe(name, false, false, null);
        }

        boolean hasText() {
            return value != null && !value.isBlank();
        }
    }

    private record ParameterProbe(
            boolean present,
            boolean repeated,
            @Nullable String value) {
        static ParameterProbe absent() {
            return new ParameterProbe(false, false, null);
        }

        boolean hasText() {
            return value != null && !value.isBlank();
        }
    }

    private record QueryParameter(String name, String value) {
    }

    private record QueryCensus(
            List<QueryParameter> parameters,
            boolean malformed) {
        static QueryCensus empty() {
            return new QueryCensus(List.of(), false);
        }

        ParameterProbe parameter(String name) {
            List<String> values = parameters.stream()
                    .filter(parameter -> name.equals(parameter.name()))
                    .map(QueryParameter::value)
                    .toList();
            if (values.isEmpty()) {
                return ParameterProbe.absent();
            }
            return new ParameterProbe(
                    true,
                    values.size() != 1,
                    values.size() == 1 ? values.get(0) : null);
        }
    }

    private record HeaderGroup(
            boolean present,
            boolean ambiguous,
            boolean hasText,
            @Nullable String selectedName) {
        static HeaderGroup absent() {
            return new HeaderGroup(false, false, false, null);
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
