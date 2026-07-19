package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;

/** Explicit append operation; there is no update or delete counterpart. */
public interface AuthorizationDecisionRepositoryCustom {

    AuthorizationDecisionEntity append(AuthorizationDecisionEntity decision);
}
