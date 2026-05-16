package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamRouteDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamRouteEntity;
import com.foggy.navigator.business.agent.model.form.UpsertClientAppUpstreamRouteForm;
import com.foggy.navigator.business.agent.repository.ClientAppUpstreamRouteRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ClientAppUpstreamRouteService {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Pattern UPSTREAM_REF_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String CONTROLLED_HEADER_PREFIX = "x-navigator-";
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "authorization",
            "proxy-authorization"
    );

    private final ClientAppUpstreamRouteRepository routeRepository;
    private final ClientAppService clientAppService;

    @Transactional(readOnly = true)
    public List<ClientAppUpstreamRouteDTO> listRoutes(String tenantId, String clientAppId) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        return routeRepository.findByTenantIdAndClientAppIdOrderByUpstreamRefAsc(tenantId, clientAppId).stream()
                .map(ClientAppUpstreamRouteDTO::fromEntity)
                .toList();
    }

    @Transactional
    public ClientAppUpstreamRouteDTO upsertRoute(String tenantId, String clientAppId, String upstreamRef,
                                                 String actorUserId, UpsertClientAppUpstreamRouteForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        validateUpstreamRef(upstreamRef);
        String baseUrl = trimToNull(form.getBaseUrl());
        requireText(baseUrl, "baseUrl is required");
        validateBaseUrl(baseUrl);
        String userTokenHeader = trimToNull(form.getUserTokenHeader());
        if (StringUtils.hasText(userTokenHeader)) {
            validateControlledHeaderName(userTokenHeader);
        }
        String status = normalizeStatus(StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED);
        clientAppService.requireClientApp(tenantId, clientAppId);

        ClientAppUpstreamRouteEntity entity = routeRepository
                .findByTenantIdAndClientAppIdAndUpstreamRef(tenantId, clientAppId, upstreamRef)
                .orElseGet(ClientAppUpstreamRouteEntity::new);
        if (entity.getId() == null) {
            entity.setTenantId(tenantId);
            entity.setClientAppId(clientAppId);
            entity.setUpstreamRef(upstreamRef);
            entity.setCreatedBy(actorUserId);
        }
        entity.setBaseUrl(baseUrl);
        entity.setUserTokenHeader(userTokenHeader);
        entity.setStatus(status);
        entity.setDescription(trimToNull(form.getDescription()));
        return ClientAppUpstreamRouteDTO.fromEntity(routeRepository.save(entity));
    }

    @Transactional
    public ClientAppUpstreamRouteDTO updateStatus(String tenantId, String clientAppId, String upstreamRef, String status) {
        validateUpstreamRef(upstreamRef);
        String normalizedStatus = normalizeStatus(status);
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppUpstreamRouteEntity route = routeRepository
                .findByTenantIdAndClientAppIdAndUpstreamRef(tenantId, clientAppId, upstreamRef)
                .orElseThrow(() -> new IllegalArgumentException("upstream route not found: " + upstreamRef));
        route.setStatus(normalizedStatus);
        return ClientAppUpstreamRouteDTO.fromEntity(routeRepository.save(route));
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedUpstreamRoute> resolveEnabledRoute(String tenantId, String clientAppId, String upstreamRef) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(clientAppId) || !StringUtils.hasText(upstreamRef)) {
            return Optional.empty();
        }
        validateUpstreamRef(upstreamRef);
        return routeRepository
                .findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
                        tenantId, clientAppId, upstreamRef, STATUS_ENABLED)
                .map(route -> new ResolvedUpstreamRoute(route.getBaseUrl(), route.getUserTokenHeader()));
    }

    private void validateUpstreamRef(String upstreamRef) {
        if (!StringUtils.hasText(upstreamRef) || !UPSTREAM_REF_PATTERN.matcher(upstreamRef).matches()) {
            throw new IllegalArgumentException("upstreamRef must match [A-Za-z0-9._-]{1,128}");
        }
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ENABLED.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new IllegalArgumentException("unsupported upstream route status: " + status);
        }
        return normalized;
    }

    private void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid upstream base URL", e);
        }
        if (!StringUtils.hasText(uri.getScheme())
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Upstream base URL must use http or https");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("Upstream base URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Upstream base URL must not include user info");
        }
    }

    private void validateControlledHeaderName(String headerName) {
        String normalized = headerName.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(normalized) || normalized.startsWith(CONTROLLED_HEADER_PREFIX)) {
            throw new IllegalArgumentException("Forbidden upstream route header: " + headerName);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @Data
    public static class ResolvedUpstreamRoute {
        private final String baseUrl;
        private final String userTokenHeader;
    }
}
