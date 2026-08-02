-- ARCH-001-ACT-001 current schema baseline.
-- Generated during implementation from the candidate launcher against an
-- isolated MySQL 8.0.44 fixture. Runtime activation must apply this tracked,
-- digest-sealed file; it must never use Hibernate create/update.

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `agent_config` (
  `last_active_at` datetime(6) DEFAULT NULL,
  `registered_at` datetime(6) DEFAULT NULL,
  `status` varchar(32) DEFAULT NULL,
  `type` varchar(32) DEFAULT NULL,
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `config_json` text,
  PRIMARY KEY (`id`),
  KEY `idx_agent_config_name` (`name`),
  KEY `idx_agent_config_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `agent_consultations` (
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `source` varchar(16) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `context_id` varchar(64) DEFAULT NULL,
  `id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `sharing_key_id` varchar(64) DEFAULT NULL,
  `target_agent_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `target_agent_name` varchar(128) DEFAULT NULL,
  `answer` text,
  `question` text NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_consult_session` (`session_id`),
  KEY `idx_consult_user` (`user_id`),
  KEY `idx_consult_agent` (`target_agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `agent_conversation_contexts` (
  `created_at` datetime(6) NOT NULL,
  `last_accessed_at` datetime(6) NOT NULL,
  `agent_type` varchar(32) NOT NULL,
  `context_id` varchar(64) NOT NULL,
  `navigator_session_id` varchar(64) DEFAULT NULL,
  `target_agent_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `context_alias` varchar(128) DEFAULT NULL,
  `agent_session_ref` varchar(256) DEFAULT NULL,
  `client_context_json` text,
  PRIMARY KEY (`context_id`),
  UNIQUE KEY `idx_acc_alias_user_agent` (`context_alias`,`user_id`,`target_agent_id`),
  KEY `idx_acc_user_agent` (`user_id`,`target_agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `agent_model_override` (
  `created_at` datetime(6) NOT NULL,
  `agent_id` varchar(50) NOT NULL,
  `id` varchar(64) NOT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_amo_tenant_agent` (`tenant_id`,`agent_id`),
  KEY `idx_amo_tenant_id` (`tenant_id`),
  KEY `idx_amo_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `agent_tasks` (
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `task_type` varchar(32) NOT NULL,
  `parent_session_id` varchar(64) NOT NULL,
  `source_agent_id` varchar(64) NOT NULL,
  `target_agent_id` varchar(64) NOT NULL,
  `target_session_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `external_task_id` varchar(128) DEFAULT NULL,
  `prompt` text,
  `result_summary` text,
  PRIMARY KEY (`task_id`),
  KEY `idx_agent_task_parent_session` (`parent_session_id`),
  KEY `idx_agent_task_user_id` (`user_id`),
  KEY `idx_agent_task_status` (`status`),
  KEY `idx_agent_task_external` (`external_task_id`,`task_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `api_credential` (
  `is_active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `category` varchar(50) NOT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `auth_header_name` varchar(100) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `api_key` varchar(512) NOT NULL,
  `extra_headers` varchar(1000) DEFAULT NULL,
  `base_url` varchar(255) DEFAULT NULL,
  `auth_type` enum('API_KEY','BASIC_AUTH','BEARER_TOKEN','CUSTOM_HEADER') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_credential_tenant_id` (`tenant_id`),
  KEY `idx_credential_category` (`category`),
  KEY `idx_credential_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `api_keys` (
  `enabled` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `last_used_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `api_key` varchar(128) NOT NULL,
  `name` varchar(256) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_api_key` (`api_key`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_credential` (
  `generation` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `issued_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `rotated_at` datetime(6) DEFAULT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `credential_fingerprint` varchar(64) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `credential_lane` varchar(64) NOT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `principal_record_id` varchar(64) NOT NULL,
  `principal_type` varchar(64) NOT NULL,
  `revoke_reason_digest` varchar(64) DEFAULT NULL,
  `rotation_of_credential_id` varchar(64) DEFAULT NULL,
  `principal_id` varchar(128) NOT NULL,
  `revoked_by_principal_id` varchar(128) DEFAULT NULL,
  `action_set_ref` varchar(160) NOT NULL,
  `verifier_reference` varchar(192) NOT NULL,
  PRIMARY KEY (`credential_id`),
  UNIQUE KEY `idx_auth_credential_verifier_ref` (`verifier_reference`),
  KEY `idx_auth_credential_principal_lane_status_exp` (`principal_id`,`credential_lane`,`status`,`expires_at`),
  KEY `idx_auth_credential_instance_status` (`navigator_instance_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_decision` (
  `created_at` datetime(6) NOT NULL,
  `evaluated_at` datetime(6) NOT NULL,
  `decision` varchar(16) NOT NULL,
  `legacy_decision` varchar(16) DEFAULT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `evaluation_mode` varchar(32) NOT NULL,
  `action_catalog_version` varchar(64) NOT NULL,
  `credential_fingerprint` varchar(64) DEFAULT NULL,
  `credential_lane` varchar(64) DEFAULT NULL,
  `decision_id` varchar(64) NOT NULL,
  `impact_digest` varchar(64) DEFAULT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `principal_fingerprint` varchar(64) DEFAULT NULL,
  `principal_type` varchar(64) NOT NULL,
  `request_digest` varchar(64) DEFAULT NULL,
  `schema_version` varchar(64) NOT NULL,
  `target_fingerprint` varchar(64) DEFAULT NULL,
  `diff_code` varchar(96) DEFAULT NULL,
  `correlation_id` varchar(128) NOT NULL,
  `server_build` varchar(128) NOT NULL,
  `action_id` varchar(160) NOT NULL,
  `legacy_reason_code` varchar(160) DEFAULT NULL,
  `reason_code` varchar(160) NOT NULL,
  `target_type` varchar(160) DEFAULT NULL,
  `route_id` varchar(192) NOT NULL,
  PRIMARY KEY (`decision_id`),
  KEY `idx_auth_decision_correlation` (`correlation_id`),
  KEY `idx_auth_decision_principal` (`principal_type`,`principal_fingerprint`),
  KEY `idx_auth_decision_credential` (`credential_lane`,`credential_fingerprint`),
  KEY `idx_auth_decision_action` (`action_id`),
  KEY `idx_auth_decision_target` (`target_type`,`target_fingerprint`),
  KEY `idx_auth_decision_result_reason` (`decision`,`reason_code`),
  KEY `idx_auth_decision_evaluated_at` (`evaluated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_management_token` (
  `credential_generation` int NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `issued_at` datetime(6) NOT NULL,
  `platform_grant_version` bigint DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `impact_digest` varchar(64) DEFAULT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `platform_grant_id` varchar(64) DEFAULT NULL,
  `reason_digest` varchar(64) DEFAULT NULL,
  `target_digest` varchar(64) DEFAULT NULL,
  `token_id` varchar(64) NOT NULL,
  `approval_reference` varchar(128) DEFAULT NULL,
  `security_action_nonce` varchar(128) DEFAULT NULL,
  `token_hash` varchar(128) NOT NULL,
  `action_id` varchar(160) DEFAULT NULL,
  `audience` varchar(160) NOT NULL,
  `token_reference` varchar(192) NOT NULL,
  PRIMARY KEY (`token_id`),
  UNIQUE KEY `uk_auth_mgmt_token_hash` (`token_hash`),
  UNIQUE KEY `uk_auth_mgmt_token_ref` (`token_reference`),
  UNIQUE KEY `uk_auth_mgmt_security_nonce` (`security_action_nonce`),
  KEY `idx_auth_mgmt_token_credential_purpose_status_exp` (`credential_id`,`purpose`,`status`,`expires_at`),
  KEY `idx_auth_mgmt_token_instance_status` (`navigator_instance_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_platform_grant` (
  `created_at` datetime(6) NOT NULL,
  `issued_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `platform_grant_id` varchar(64) NOT NULL,
  `principal_record_id` varchar(64) NOT NULL,
  `reason_digest` varchar(64) DEFAULT NULL,
  `tenant_scope_mode` varchar(64) NOT NULL,
  `approval_reference` varchar(128) DEFAULT NULL,
  `principal_id` varchar(128) NOT NULL,
  `source_reference` varchar(128) DEFAULT NULL,
  `upstream_system_id` varchar(128) NOT NULL,
  `action_set_ref` varchar(160) NOT NULL,
  PRIMARY KEY (`platform_grant_id`),
  UNIQUE KEY `uk_auth_platform_grant_scope` (`navigator_instance_id`,`environment_profile`,`principal_id`,`upstream_system_id`),
  KEY `idx_auth_platform_grant_instance_upstream_status` (`navigator_instance_id`,`upstream_system_id`,`status`),
  KEY `idx_auth_platform_grant_principal_status` (`principal_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_principal` (
  `created_at` datetime(6) NOT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `principal_record_id` varchar(64) NOT NULL,
  `principal_type` varchar(64) NOT NULL,
  `upstream_trust_profile` varchar(64) NOT NULL,
  `principal_id` varchar(128) NOT NULL,
  `source_upstream_system_id` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`principal_record_id`),
  UNIQUE KEY `uk_auth_principal_scope` (`navigator_instance_id`,`principal_type`,`principal_id`),
  KEY `idx_auth_principal_instance_type_status` (`navigator_instance_id`,`principal_type`,`status`),
  KEY `idx_auth_principal_upstream_status` (`source_upstream_system_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `authorization_tenant_authority` (
  `created_at` datetime(6) NOT NULL,
  `resolved_at` datetime(6) NOT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `environment_profile` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `navigator_instance_id` varchar(64) NOT NULL,
  `tenant_authority_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `migration_reference` varchar(128) DEFAULT NULL,
  `source_reference` varchar(128) NOT NULL,
  `upstream_system_id` varchar(128) NOT NULL,
  PRIMARY KEY (`tenant_authority_id`),
  UNIQUE KEY `uk_auth_tenant_authority_scope` (`navigator_instance_id`,`tenant_id`),
  KEY `idx_auth_tenant_authority_instance_upstream_status` (`navigator_instance_id`,`upstream_system_id`,`status`),
  KEY `idx_auth_tenant_authority_upstream_status` (`upstream_system_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `biz_worker_identity` (
  `credential_version` int NOT NULL,
  `credential_expires_at` datetime(6) DEFAULT NULL,
  `credential_issued_at` datetime(6) DEFAULT NULL,
  `credential_revoked_at` datetime(6) DEFAULT NULL,
  `credential_rotated_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `registered_at` datetime(6) NOT NULL,
  `row_version` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `health_status` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `version` varchar(64) DEFAULT NULL,
  `worker_backend` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `owner_id` varchar(128) NOT NULL,
  `token_hash` varchar(128) DEFAULT NULL,
  `base_url` varchar(512) NOT NULL,
  `capabilities_json` text,
  `labels_json` text,
  `owner_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKinguo6cikxjikpijy12sl5qt3` (`worker_id`),
  KEY `idx_bwi_backend` (`worker_backend`),
  KEY `idx_bwi_status` (`status`),
  KEY `idx_bwi_owner` (`owner_type`,`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `biz_worker_pool` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `health_status` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `pool_id` varchar(64) NOT NULL,
  `routing_policy` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_backend` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `owner_id` varchar(128) NOT NULL,
  `capabilities_json` text,
  `labels_json` text,
  `owner_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKfdy1jf3603gl4fan06t9jprkw` (`pool_id`),
  KEY `idx_bwp_tenant` (`tenant_id`),
  KEY `idx_bwp_backend` (`worker_backend`),
  KEY `idx_bwp_status` (`status`),
  KEY `idx_bwp_owner` (`owner_type`,`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `biz_worker_pool_member` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `pool_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_worker_pool_member` (`pool_id`,`worker_id`),
  KEY `idx_bwpm_pool` (`pool_id`),
  KEY `idx_bwpm_worker` (`worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_agent_session` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_accessed_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `context_id` varchar(64) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `latest_task_id` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `worker_provider_type` varchar(64) DEFAULT NULL,
  `account_id` varchar(128) DEFAULT NULL,
  `agent_id` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) DEFAULT NULL,
  `upstream_user_id` varchar(128) NOT NULL,
  `client_context_json` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_agent_session_context` (`tenant_id`,`client_app_id`,`upstream_user_id`,`context_id`),
  UNIQUE KEY `uk_biz_agent_session_session` (`tenant_id`,`client_app_id`,`upstream_user_id`,`session_id`),
  KEY `idx_biz_agent_session_tenant_app_user` (`tenant_id`,`client_app_id`,`upstream_user_id`),
  KEY `idx_biz_agent_session_last_accessed` (`last_accessed_at`),
  KEY `idx_biz_agent_session_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_agent_task` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `agent_id` varchar(64) NOT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `navigator_effective_user_id` varchar(64) NOT NULL,
  `requested_model_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `worker_pool_id` varchar(64) NOT NULL,
  `worker_provider_type` varchar(64) DEFAULT NULL,
  `worker_session_id` varchar(64) DEFAULT NULL,
  `worker_task_id` varchar(64) DEFAULT NULL,
  `model` varchar(128) DEFAULT NULL,
  `requested_model_variant` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) DEFAULT NULL,
  `upstream_user_id` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKdt41t6ugrm724hjo36y0valom` (`task_id`),
  KEY `idx_biz_task_tenant` (`tenant_id`),
  KEY `idx_biz_task_client_app` (`client_app_id`),
  KEY `idx_biz_task_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_function` (
  `approval_required` bit(1) NOT NULL,
  `idempotency_required` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `current_version` varchar(64) DEFAULT NULL,
  `risk_level` varchar(64) DEFAULT NULL,
  `status` varchar(64) NOT NULL,
  `business_object_id` varchar(128) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `domain` varchar(128) NOT NULL,
  `exposure` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `function_id` varchar(255) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6xsdtsr6sgm9uwe7k7kvcc88h` (`tenant_id`,`function_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_function_runtime_audit` (
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `audit_id` varchar(64) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `function_version` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `status` varchar(64) DEFAULT NULL,
  `suspend_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) DEFAULT NULL,
  `worker_pool_id` varchar(64) DEFAULT NULL,
  `client_app_id` varchar(128) DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `input_hash` varchar(128) DEFAULT NULL,
  `output_hash` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `upstream_user_id` varchar(128) DEFAULT NULL,
  `error_message` varchar(512) DEFAULT NULL,
  `function_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKd04to05l29n2h30rrbgj07onj` (`audit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_function_suspension` (
  `approved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `version` varchar(32) NOT NULL,
  `approval_id` varchar(64) DEFAULT NULL,
  `business_execution_status` varchar(64) DEFAULT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `suspend_id` varchar(64) NOT NULL,
  `suspension_type` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_notification_status` varchar(64) DEFAULT NULL,
  `worker_pool_id` varchar(64) DEFAULT NULL,
  `worker_session_id` varchar(64) DEFAULT NULL,
  `worker_task_id` varchar(64) DEFAULT NULL,
  `approved_by` varchar(128) DEFAULT NULL,
  `function_id` varchar(128) NOT NULL,
  `skill_id` varchar(128) NOT NULL,
  `upstream_user_id` varchar(128) NOT NULL,
  `comment` varchar(512) DEFAULT NULL,
  `idempotency_key` varchar(255) DEFAULT NULL,
  `input_json` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKen5dbny2weo8kcgi43jmgxy3j` (`suspend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_function_version` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `version` varchar(64) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `adapter_config_json` text,
  `function_id` varchar(255) NOT NULL,
  `input_schema_json` text,
  `llm_visible_summary` text,
  `manifest_json` text,
  `output_schema_json` text,
  `schema_visible_summary` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKrvyad6he8iywmv7ba0fn7v6ie` (`tenant_id`,`function_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_object` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `created_by` varchar(64) NOT NULL,
  `domain` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `updated_by` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `object_id` varchar(128) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1fr606n6063fl86i22d1rbtqb` (`tenant_id`,`object_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_task_scoped_token` (
  `generation` int NOT NULL,
  `token_version` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issued_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `row_version` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `caller_authority_type` varchar(48) DEFAULT NULL,
  `audience` varchar(64) NOT NULL,
  `caller_access_token_id` varchar(64) DEFAULT NULL,
  `caller_credential_id` varchar(64) DEFAULT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `identity_assurance` varchar(64) NOT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `navigator_effective_user_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `token_id` varchar(64) NOT NULL,
  `worker_pool_id` varchar(64) NOT NULL,
  `worker_session_id` varchar(64) DEFAULT NULL,
  `worker_task_id` varchar(64) DEFAULT NULL,
  `navigator_instance_id` varchar(128) DEFAULT NULL,
  `revoked_by` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) DEFAULT NULL,
  `token_hash` varchar(128) NOT NULL,
  `upstream_user_id` varchar(128) DEFAULT NULL,
  `worker_id` varchar(128) DEFAULT NULL,
  `worker_lease_id` varchar(128) DEFAULT NULL,
  `revoke_reason` varchar(512) DEFAULT NULL,
  `function_scope_json` longtext NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKb3gkpu88nxdly6kau2j406ptv` (`token_id`),
  KEY `idx_biz_token_task` (`task_id`),
  KEY `idx_biz_token_tenant_worker_task` (`tenant_id`,`worker_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `business_task_terminal_state` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `revocation_completed_at` datetime(6) DEFAULT NULL,
  `terminal_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `terminal_status` varchar(32) NOT NULL,
  `business_task_id` varchar(64) DEFAULT NULL,
  `navigator_effective_user_id` varchar(64) DEFAULT NULL,
  `provider_task_user_id` varchar(64) NOT NULL,
  `source_agent_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_task_id` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_terminal_tenant_worker_task` (`tenant_id`,`worker_task_id`),
  KEY `idx_biz_terminal_tenant_business_task` (`tenant_id`,`business_task_id`),
  KEY `idx_biz_terminal_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_agent_teams_configs` (
  `is_default` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `config_id` varchar(64) NOT NULL,
  `directory_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `config` text NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKoxspe643ejava446xrfy8e0ce` (`config_id`),
  KEY `idx_atc_directory_id` (`directory_id`),
  KEY `idx_atc_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_conversation_configs` (
  `pinned` bit(1) NOT NULL,
  `auth_bound_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pinned_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auth_mode` varchar(32) DEFAULT NULL,
  `interaction_state` varchar(32) DEFAULT NULL,
  `agent_teams_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `base_url` varchar(512) DEFAULT NULL,
  `auth_token` text,
  `custom_title` varchar(255) DEFAULT NULL,
  `tags` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKe5imjjwbf66mwrtim0bqn7vdm` (`session_id`),
  KEY `idx_ccc_user_worker` (`user_id`,`worker_id`),
  KEY `idx_ccc_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_cross_project_phases` (
  `cost_usd` decimal(10,4) DEFAULT NULL,
  `phase_index` int NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `started_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `claude_task_id` varchar(64) DEFAULT NULL,
  `context_id` varchar(64) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `phase_id` varchar(64) NOT NULL,
  `phase_session_id` varchar(64) DEFAULT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `worktree_directory_id` varchar(64) DEFAULT NULL,
  `claude_session_id` varchar(128) DEFAULT NULL,
  `worktree_branch` varchar(128) DEFAULT NULL,
  `phase_name` varchar(256) NOT NULL,
  `handoff_artifact` text,
  `incoming_context` text,
  `prompt` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1n51m0espw5kyl4d85idj6p94` (`phase_id`),
  KEY `idx_cxpp_context_id` (`context_id`),
  KEY `idx_cxpp_directory_id` (`directory_id`),
  KEY `idx_cxpp_claude_task_id` (`claude_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_cross_project_tasks` (
  `current_phase_index` int DEFAULT NULL,
  `total_cost_usd` decimal(10,4) DEFAULT NULL,
  `total_phases` int DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `execution_mode` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `context_id` varchar(64) NOT NULL,
  `initial_directory_id` varchar(64) DEFAULT NULL,
  `initial_session_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `title` varchar(256) NOT NULL,
  `description` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmota2nu124hptyqn59xs5clwq` (`context_id`),
  KEY `idx_cxpt_user_id` (`user_id`),
  KEY `idx_cxpt_status` (`status`),
  KEY `idx_cxpt_initial_session` (`initial_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_tasks` (
  `abort_requested` bit(1) DEFAULT NULL,
  `cost_usd` decimal(10,4) DEFAULT NULL,
  `file_checkpointing_enabled` bit(1) DEFAULT NULL,
  `last_acked_seq` int DEFAULT NULL,
  `num_turns` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `created_at_epoch_ms` bigint DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `input_tokens` bigint DEFAULT NULL,
  `last_alive_at` datetime(6) DEFAULT NULL,
  `last_output_at` datetime(6) DEFAULT NULL,
  `output_tokens` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `source` varchar(32) DEFAULT NULL,
  `status` varchar(32) DEFAULT NULL,
  `agent_teams_config_id` varchar(64) DEFAULT NULL,
  `context_id` varchar(64) DEFAULT NULL,
  `dedup_key` varchar(64) DEFAULT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `model` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `claude_session_id` varchar(128) DEFAULT NULL,
  `worker_task_id` varchar(128) DEFAULT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  `checkpoints` mediumtext,
  `error_message` text,
  `prompt` text NOT NULL,
  `result_text` mediumtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKh47n60yx6e89ngtf4cissghx9` (`task_id`),
  KEY `idx_ct_session_id` (`session_id`),
  KEY `idx_ct_worker_id` (`worker_id`),
  KEY `idx_ct_user_id` (`user_id`),
  KEY `idx_ct_tenant_id` (`tenant_id`),
  KEY `idx_ct_directory_id` (`directory_id`),
  KEY `idx_ct_dedup_key` (`dedup_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `claude_workers` (
  `ssh_port` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_heartbeat` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auth_mode` varchar(32) DEFAULT NULL,
  `status` varchar(32) DEFAULT NULL,
  `worker_version` varchar(32) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `ssh_username` varchar(128) DEFAULT NULL,
  `code_server_folder_prefix` varchar(256) DEFAULT NULL,
  `hostname` varchar(256) DEFAULT NULL,
  `auth_token` varchar(512) NOT NULL,
  `base_url` varchar(512) NOT NULL,
  `code_server_internal_url` varchar(512) DEFAULT NULL,
  `code_server_public_url` varchar(512) DEFAULT NULL,
  `code_server_password` text,
  `codex_config` text,
  `gemini_config` text,
  `ssh_password` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKp6jge9xshh3vd1dycf1o63v2m` (`worker_id`),
  KEY `idx_cw_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `capability_domain` varchar(64) DEFAULT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `owner_user_id` varchar(64) DEFAULT NULL,
  `provisioning_credential_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `upstream_client_app_namespace` varchar(128) DEFAULT NULL,
  `upstream_ref` varchar(128) DEFAULT NULL,
  `upstream_system_id` varchar(128) DEFAULT NULL,
  `description` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKq1k8vwdionxs6x8nxr6bmbcd7` (`client_app_id`),
  KEY `idx_client_app_tenant` (`tenant_id`),
  KEY `idx_client_app_status` (`status`),
  KEY `idx_client_app_upstream` (`upstream_system_id`,`upstream_client_app_namespace`,`tenant_id`,`upstream_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_control_credential` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_used_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `effective_user_id` varchar(64) DEFAULT NULL,
  `issued_by_user_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `control_key_hash` varchar(128) NOT NULL,
  `description` text,
  `scopes` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9ay0obn1o7dj56ont126xkd3j` (`credential_id`),
  UNIQUE KEY `UK5h9p9fysie3fw5gp8osa5r037` (`control_key_hash`),
  KEY `idx_cacc_client_app` (`client_app_id`),
  KEY `idx_cacc_tenant` (`tenant_id`),
  KEY `idx_cacc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_function_grant` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `version` varchar(64) NOT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `grant_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `function_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKliae8f8xguqi5vijeryeuvt4s` (`tenant_id`,`client_app_id`,`function_id`,`version`),
  UNIQUE KEY `UKrdum92l45k7um2tmw9lqhvxxy` (`grant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_model_config_grant` (
  `is_default` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `grant_scope` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_app_model_config_grant` (`client_app_id`,`model_config_id`),
  KEY `idx_camcg_client_app` (`client_app_id`),
  KEY `idx_camcg_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_provisioning_credential` (
  `max_uses` int NOT NULL,
  `used_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `capability_domain` varchar(64) DEFAULT NULL,
  `credential_id` varchar(64) NOT NULL,
  `issued_by_user_id` varchar(64) DEFAULT NULL,
  `owner_user_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `audit_tag` varchar(128) DEFAULT NULL,
  `token_hash` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKrqpgrwcgx51vrp4qoayvdhcqj` (`credential_id`),
  UNIQUE KEY `UK4eiiqbmup2lbaq5eu8ndtrqle` (`token_hash`),
  KEY `idx_capc_tenant` (`tenant_id`),
  KEY `idx_capc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_runtime_access_token` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `revoked_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_request_id` varchar(36) DEFAULT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `token_id` varchar(64) NOT NULL,
  `app_key` varchar(96) NOT NULL,
  `token_hash` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmyulj2fn6dxh6ske7mxbqih69` (`token_id`),
  UNIQUE KEY `UKkuvgtk08yhlxv2g7hx74q5e9j` (`token_hash`),
  KEY `idx_carat_client_app` (`client_app_id`),
  KEY `idx_carat_tenant` (`tenant_id`),
  KEY `idx_carat_app_key` (`app_key`),
  KEY `idx_carat_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_runtime_credential` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_app_id` varchar(64) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `app_key` varchar(96) NOT NULL,
  `secret_hash` varchar(128) NOT NULL,
  `description` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKosvc41djjx5nbnke3rgljrput` (`credential_id`),
  UNIQUE KEY `UK7minjtengnkgppvd16al63d0g` (`app_key`),
  KEY `idx_carc_client_app` (`client_app_id`),
  KEY `idx_carc_tenant` (`tenant_id`),
  KEY `idx_carc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_skill_grant` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `grant_id` varchar(128) NOT NULL,
  `skill_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8m8bkkq8khc1q45yodp3ec7p1` (`tenant_id`,`client_app_id`,`skill_id`),
  UNIQUE KEY `UK7a4n12o8r0bvkhonkuvwlk7i` (`grant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_upstream_route` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `upstream_ref` varchar(128) NOT NULL,
  `user_token_header` varchar(128) DEFAULT NULL,
  `description` varchar(512) DEFAULT NULL,
  `base_url` varchar(1024) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_app_upstream_route` (`tenant_id`,`client_app_id`,`upstream_ref`),
  KEY `idx_caur_client_app` (`client_app_id`),
  KEY `idx_caur_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `client_app_upstream_user_grant` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `grant_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `upstream_user_token` varchar(2048) DEFAULT NULL,
  `upstream_user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKdokjwdaaifcuj12vl15eqqplo` (`tenant_id`,`client_app_id`,`upstream_user_id`),
  UNIQUE KEY `UKetor15g4rukobyesfye0fjk6l` (`grant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `codex_app_server_endpoints` (
  `last_runtime_revision` int DEFAULT NULL,
  `configuration_version` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_synced_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `last_sync_status` varchar(32) NOT NULL,
  `endpoint_id` varchar(48) NOT NULL,
  `last_runtime_id` varchar(64) DEFAULT NULL,
  `worker_id` varchar(64) NOT NULL,
  `endpoint_url` varchar(512) NOT NULL,
  `auth_token_ciphertext` text NOT NULL,
  `last_sync_message` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_case_endpoint_id` (`endpoint_id`),
  KEY `idx_case_worker` (`worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `codex_runtime_revisions` (
  `enabled` bit(1) NOT NULL,
  `priority` int NOT NULL,
  `reported_runtime_revision` int DEFAULT NULL,
  `revision` int NOT NULL,
  `rollout_percentage` int NOT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_capability_at` datetime(6) DEFAULT NULL,
  `routing_epoch` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `contract_version` varchar(32) DEFAULT NULL,
  `readiness_status` varchar(32) NOT NULL,
  `routing_policy` varchar(32) NOT NULL,
  `runtime_source` varchar(32) NOT NULL,
  `runtime_type` varchar(32) NOT NULL,
  `endpoint_id` varchar(48) DEFAULT NULL,
  `capability_fingerprint` varchar(64) DEFAULT NULL,
  `cli_version` varchar(64) DEFAULT NULL,
  `expected_cli_version` varchar(64) NOT NULL,
  `reported_runtime_id` varchar(64) DEFAULT NULL,
  `runtime_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `expected_schema_digest` varchar(128) DEFAULT NULL,
  `instance_id` varchar(128) DEFAULT NULL,
  `schema_digest` varchar(128) DEFAULT NULL,
  `endpoint_url` varchar(512) NOT NULL,
  `auth_token_ciphertext` text NOT NULL,
  `capability_manifest_json` text,
  `readiness_message` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_crr_runtime_revision` (`runtime_id`,`revision`),
  KEY `idx_crr_worker_type` (`worker_id`,`runtime_type`),
  KEY `idx_crr_routing` (`enabled`,`readiness_status`,`routing_policy`),
  KEY `idx_crr_endpoint` (`endpoint_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `codex_tasks` (
  `cost_usd` decimal(10,6) DEFAULT NULL,
  `last_acked_seq` int DEFAULT NULL,
  `num_turns` int DEFAULT NULL,
  `runtime_revision` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `created_at_epoch_ms` bigint DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `input_tokens` bigint DEFAULT NULL,
  `last_alive_at` datetime(6) DEFAULT NULL,
  `last_output_at` datetime(6) DEFAULT NULL,
  `output_tokens` bigint DEFAULT NULL,
  `routing_epoch` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `runtime_acceptance_state` varchar(32) DEFAULT NULL,
  `runtime_type` varchar(32) DEFAULT NULL,
  `source` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `runtime_request_hash` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `model` varchar(128) DEFAULT NULL,
  `runtime_id` varchar(128) DEFAULT NULL,
  `runtime_instance_id` varchar(128) DEFAULT NULL,
  `worker_task_id` varchar(128) DEFAULT NULL,
  `codex_thread_id` varchar(256) DEFAULT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  `error_message` text,
  `prompt` text,
  `result_text` mediumtext,
  `runtime_request_ciphertext` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKctglpd3ngcij0m28dx3i6ptgn` (`task_id`),
  KEY `idx_cxt_user_id` (`user_id`),
  KEY `idx_cxt_worker_id` (`worker_id`),
  KEY `idx_cxt_status` (`status`),
  KEY `idx_cxt_provider_status` (`provider_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `coding_agent_directories` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` varchar(64) NOT NULL,
  `directory_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cad_tenant_agent_directory` (`tenant_id`,`agent_id`,`directory_id`),
  KEY `idx_cad_agent_id` (`tenant_id`,`agent_id`),
  KEY `idx_cad_directory_id` (`tenant_id`,`directory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `coding_agent_models` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` varchar(64) NOT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cam_tenant_agent_model` (`tenant_id`,`agent_id`,`model_config_id`),
  KEY `idx_cam_agent_id` (`tenant_id`,`agent_id`),
  KEY `idx_cam_model_config_id` (`tenant_id`,`model_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `coding_agent_workers` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_pool_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_caw_tenant_agent_worker` (`tenant_id`,`agent_id`,`worker_pool_id`),
  KEY `idx_caw_agent_id` (`tenant_id`,`agent_id`),
  KEY `idx_caw_worker_pool_id` (`tenant_id`,`worker_pool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `coding_agents` (
  `enabled` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `agent_type` varchar(32) NOT NULL,
  `agent_id` varchar(64) NOT NULL,
  `client_app_id` varchar(64) DEFAULT NULL,
  `default_directory_id` varchar(64) DEFAULT NULL,
  `default_model` varchar(64) DEFAULT NULL,
  `default_model_config_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `default_branch` varchar(128) DEFAULT NULL,
  `name` varchar(128) NOT NULL,
  `owner_id` varchar(128) DEFAULT NULL,
  `endpoint_url` varchar(512) DEFAULT NULL,
  `agent_profile` text,
  `auth_scheme` text,
  `description` text,
  `project_summary` text,
  `skills` text,
  `owner_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ca_tenant_agent_id` (`tenant_id`,`agent_id`),
  KEY `idx_ca_user_id` (`user_id`),
  KEY `idx_ca_worker_id` (`worker_id`),
  KEY `idx_ca_owner` (`owner_type`,`owner_id`),
  KEY `idx_ca_client_app` (`tenant_id`,`client_app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `deleted_claude_sessions` (
  `deleted_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `claude_session_id` varchar(128) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_dcs_worker_user` (`worker_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `error_diagnostic_shares` (
  `access_count` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `last_access_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `created_by` varchar(64) NOT NULL,
  `diagnostic_id` varchar(64) NOT NULL,
  `share_id` varchar(64) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`share_id`),
  UNIQUE KEY `idx_eds_token_hash` (`token_hash`),
  KEY `idx_eds_diagnostic_id` (`diagnostic_id`),
  KEY `idx_eds_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `error_diagnostics` (
  `http_status` int DEFAULT NULL,
  `recoverable` bit(1) NOT NULL,
  `redaction_version` int NOT NULL,
  `retry_count` int DEFAULT NULL,
  `schema_version` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `category` varchar(32) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `runtime_type` varchar(32) DEFAULT NULL,
  `runtime_phase` varchar(48) NOT NULL,
  `diagnostic_id` varchar(64) NOT NULL,
  `owner_user_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `worker_label` varchar(128) DEFAULT NULL,
  `error_code` varchar(160) NOT NULL,
  `exception_type` varchar(160) DEFAULT NULL,
  `provider_status` varchar(160) DEFAULT NULL,
  `safe_message` varchar(512) NOT NULL,
  `diagnostic_text` text,
  PRIMARY KEY (`diagnostic_id`),
  KEY `idx_ed_task_id` (`task_id`),
  KEY `idx_ed_session_id` (`session_id`),
  KEY `idx_ed_owner_scope` (`owner_user_id`,`tenant_id`),
  KEY `idx_ed_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `external_user_mappings` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `external_user_id` varchar(128) NOT NULL,
  `external_display_name` varchar(256) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_eum_tenant_ext` (`tenant_id`,`external_user_id`),
  KEY `idx_eum_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `gemini_tasks` (
  `cost_usd` decimal(10,6) DEFAULT NULL,
  `last_acked_seq` int DEFAULT NULL,
  `num_turns` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `input_tokens` bigint DEFAULT NULL,
  `last_alive_at` datetime(6) DEFAULT NULL,
  `last_output_at` datetime(6) DEFAULT NULL,
  `output_tokens` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `source` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `model` varchar(128) DEFAULT NULL,
  `worker_task_id` varchar(128) DEFAULT NULL,
  `gemini_session_id` varchar(256) DEFAULT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  `error_message` text,
  `prompt` text,
  `result_text` mediumtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6yvkajuytoljah2mdmbsqrlf4` (`task_id`),
  KEY `idx_gmt_user_id` (`user_id`),
  KEY `idx_gmt_worker_id` (`worker_id`),
  KEY `idx_gmt_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `git_provider_config` (
  `is_active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `username` varchar(100) DEFAULT NULL,
  `access_token` varchar(512) NOT NULL,
  `base_url` varchar(255) DEFAULT NULL,
  `provider_type` enum('GITEE','GITHUB','GITLAB') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_git_tenant_id` (`tenant_id`),
  KEY `idx_git_provider_type` (`provider_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `langgraph_approvals` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reviewed_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `approval_result` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `approval_type` varchar(64) NOT NULL,
  `reviewed_by` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `comment` text,
  `payload` text,
  `summary` text,
  PRIMARY KEY (`id`),
  KEY `idx_lga_task_id` (`task_id`),
  KEY `idx_lga_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `langgraph_tasks` (
  `recoverable` bit(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `context_id` varchar(64) DEFAULT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `interruption_reason` varchar(64) DEFAULT NULL,
  `model` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_deadline_at` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `task_sub_status` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  `error_message` text,
  `interruption_message` text,
  `prompt` text NOT NULL,
  `result_text` mediumtext,
  `structured_output` mediumtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKg2hq72gewewhygyc4dr31qra` (`task_id`),
  KEY `idx_lgt_session_id` (`session_id`),
  KEY `idx_lgt_worker_id` (`worker_id`),
  KEY `idx_lgt_user_id` (`user_id`),
  KEY `idx_lgt_directory_id` (`directory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `langgraph_workers` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_heartbeat` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auth_mode` varchar(32) DEFAULT NULL,
  `status` varchar(32) DEFAULT NULL,
  `worker_version` varchar(32) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `hostname` varchar(256) DEFAULT NULL,
  `auth_token` varchar(512) NOT NULL,
  `base_url` varchar(512) NOT NULL,
  `provider_ext` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKs3a8v4hd21bla7ydgb8och0mv` (`worker_id`),
  KEY `idx_lgw_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_activation_targets` (
  `owner_protocol` int NOT NULL,
  `worker_protocol` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `destroyed_at` datetime(6) DEFAULT NULL,
  `last_observed_at` datetime(6) DEFAULT NULL,
  `reserved_at` datetime(6) DEFAULT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(24) NOT NULL,
  `provider_evidence_lane` varchar(32) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `target_class` varchar(48) NOT NULL,
  `candidate_patch_sha256` varchar(64) NOT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `prompt_sha256` varchar(64) NOT NULL,
  `reserved_session_id` varchar(64) DEFAULT NULL,
  `reserved_task_id` varchar(64) DEFAULT NULL,
  `target_commit` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_version` varchar(64) NOT NULL,
  `generation_id` varchar(96) NOT NULL,
  `proof_id` varchar(96) DEFAULT NULL,
  `run_id` varchar(96) NOT NULL,
  `safe_reason_code` varchar(96) DEFAULT NULL,
  `target_id` varchar(96) NOT NULL,
  `controller_inventory_digest` varchar(128) NOT NULL,
  `manifest_digest` varchar(128) NOT NULL,
  `model` varchar(128) NOT NULL,
  `physical_worker_id` varchar(128) NOT NULL,
  `worker_instance_epoch` varchar(128) DEFAULT NULL,
  `worker_state_generation` varchar(128) DEFAULT NULL,
  `writer_instance_id` varchar(128) NOT NULL,
  `codex_home_key` varchar(256) NOT NULL,
  `required_capabilities_json` text NOT NULL,
  PRIMARY KEY (`target_id`),
  UNIQUE KEY `UKf5q8wxv9rdfey79xid0xwxd5x` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_effect_outbox` (
  `authorized_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `row_version` bigint NOT NULL,
  `ownership_mode` varchar(16) DEFAULT NULL,
  `effect_state` varchar(24) NOT NULL,
  `aggregate_type` varchar(32) NOT NULL,
  `binding_digest_version` varchar(32) DEFAULT NULL,
  `effect_class` varchar(32) NOT NULL,
  `provider_type` varchar(32) DEFAULT NULL,
  `aggregate_id` varchar(64) NOT NULL,
  `effect_claim` varchar(64) DEFAULT NULL,
  `effect_type` varchar(64) NOT NULL,
  `operation_id` varchar(64) DEFAULT NULL,
  `dispatch_id` varchar(96) DEFAULT NULL,
  `effect_authorization_proof_version` varchar(96) DEFAULT NULL,
  `effect_id` varchar(96) NOT NULL,
  `proof_id` varchar(96) DEFAULT NULL,
  `writer_generation_id` varchar(96) DEFAULT NULL,
  `binding_digest` varchar(128) DEFAULT NULL,
  `controller_inventory_digest` varchar(128) DEFAULT NULL,
  `instance_epoch` varchar(128) DEFAULT NULL,
  `physical_worker_id` varchar(128) DEFAULT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  `state_generation` varchar(128) DEFAULT NULL,
  `aggregate_reference_id` varchar(160) DEFAULT NULL,
  `idempotency_key` varchar(160) NOT NULL,
  `content_free_payload_json` text NOT NULL,
  PRIMARY KEY (`effect_id`),
  UNIQUE KEY `uk_leo_idempotency` (`idempotency_key`),
  KEY `idx_leo_state` (`effect_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_facts` (
  `schema_version` int NOT NULL,
  `recorded_at` datetime(6) NOT NULL,
  `source_sequence` bigint NOT NULL,
  `ownership_mode` varchar(16) NOT NULL,
  `aggregate_type` varchar(32) NOT NULL,
  `safe_binding_digest_version` varchar(32) DEFAULT NULL,
  `operation_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) DEFAULT NULL,
  `dispatch_id` varchar(96) DEFAULT NULL,
  `fact_id` varchar(96) NOT NULL,
  `fact_type` varchar(96) NOT NULL,
  `safe_reason_code` varchar(96) DEFAULT NULL,
  `aggregate_id` varchar(128) NOT NULL,
  `instance_epoch` varchar(128) DEFAULT NULL,
  `physical_worker_id` varchar(128) DEFAULT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  `safe_binding_digest` varchar(128) DEFAULT NULL,
  `state_generation` varchar(128) DEFAULT NULL,
  `idempotency_key` varchar(160) NOT NULL,
  `content_free_payload_json` text NOT NULL,
  PRIMARY KEY (`fact_id`),
  UNIQUE KEY `uk_lf_idempotency` (`idempotency_key`),
  KEY `idx_lf_aggregate_cursor` (`aggregate_type`,`aggregate_id`,`source_sequence`),
  KEY `idx_lf_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_writer_exclusivity_proofs` (
  `acquired_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `last_verified_at` datetime(6) NOT NULL,
  `proof_version` bigint NOT NULL,
  `row_version` bigint NOT NULL,
  `status` varchar(24) NOT NULL,
  `generation_id` varchar(96) NOT NULL,
  `proof_id` varchar(96) NOT NULL,
  `controller_inventory_digest` varchar(128) NOT NULL,
  `holder_instance_id` varchar(128) NOT NULL,
  `quarantine_cursor` varchar(160) DEFAULT NULL,
  PRIMARY KEY (`proof_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_writer_exclusivity_references` (
  `acquired_at` datetime(6) NOT NULL,
  `released_at` datetime(6) DEFAULT NULL,
  `aggregate_type` varchar(16) NOT NULL,
  `proof_id` varchar(96) NOT NULL,
  `release_reason` varchar(96) DEFAULT NULL,
  `aggregate_id` varchar(128) NOT NULL,
  `reference_id` varchar(160) NOT NULL,
  PRIMARY KEY (`reference_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_writer_generations` (
  `minimum_owner_protocol` int NOT NULL,
  `activated_at` datetime(6) DEFAULT NULL,
  `row_version` bigint NOT NULL,
  `active_slot` varchar(16) DEFAULT NULL,
  `status` varchar(24) NOT NULL,
  `target_commit` varchar(64) NOT NULL,
  `generation_id` varchar(96) NOT NULL,
  `run_id` varchar(96) DEFAULT NULL,
  `target_id` varchar(96) DEFAULT NULL,
  `controller_inventory_digest` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`generation_id`),
  UNIQUE KEY `UK2wy0yerbnu3wnjh8yy8ctowej` (`active_slot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `lifecycle_writer_instance_registrations` (
  `owner_protocol` int NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `last_heartbeat_at` datetime(6) NOT NULL,
  `registered_at` datetime(6) DEFAULT NULL,
  `row_version` bigint DEFAULT NULL,
  `status` varchar(24) DEFAULT NULL,
  `target_commit` varchar(64) NOT NULL,
  `generation_id` varchar(96) NOT NULL,
  `run_id` varchar(96) DEFAULT NULL,
  `target_id` varchar(96) DEFAULT NULL,
  `controller_inventory_digest` varchar(128) DEFAULT NULL,
  `instance_id` varchar(128) NOT NULL,
  PRIMARY KEY (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `llm_model_config` (
  `enabled` bit(1) NOT NULL,
  `is_default` bit(1) NOT NULL,
  `sort_order` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `worker_backend` varchar(32) DEFAULT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `model_name` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `runtime_budget_preset_key` varchar(100) DEFAULT NULL,
  `created_by_credential_id` varchar(128) DEFAULT NULL,
  `created_by_principal_id` varchar(128) DEFAULT NULL,
  `owner_id` varchar(128) NOT NULL,
  `api_key` varchar(512) DEFAULT NULL,
  `available_models` text,
  `base_url` varchar(255) DEFAULT NULL,
  `env_vars` text,
  `runtime_budget_override_json` text,
  `category` enum('CODING','GENERAL','REASONING','VISION') NOT NULL,
  `created_by_principal_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') DEFAULT NULL,
  `owner_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') NOT NULL,
  `scope` enum('GLOBAL','RESTRICTED') DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_llm_tenant_id` (`tenant_id`),
  KEY `idx_llm_category` (`category`),
  KEY `idx_llm_is_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `model_worker_access` (
  `created_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `model_config_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mwa_model_worker` (`model_config_id`,`worker_id`),
  KEY `idx_mwa_model_config_id` (`model_config_id`),
  KEY `idx_mwa_worker_id` (`worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `native_subtask_states` (
  `contract_version` int NOT NULL,
  `depth` int NOT NULL,
  `last_event_seq` int NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `event_updated_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `started_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `activity` varchar(64) DEFAULT NULL,
  `message` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `parent_subtask_id` varchar(128) DEFAULT NULL,
  `role` varchar(128) DEFAULT NULL,
  `subtask_id` varchar(128) NOT NULL,
  `label` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_native_subtask_task_child` (`task_id`,`subtask_id`),
  KEY `idx_native_subtask_task` (`task_id`),
  KEY `idx_native_subtask_session` (`session_id`),
  KEY `idx_native_subtask_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `runtime_request_audit` (
  `admission_completed` bit(1) DEFAULT NULL,
  `business_function_dispatched` bit(1) DEFAULT NULL,
  `dispatch_count` int DEFAULT NULL,
  `effective_function_count` int DEFAULT NULL,
  `effective_tool_count` int DEFAULT NULL,
  `http_request_received` bit(1) DEFAULT NULL,
  `model_dispatched` bit(1) DEFAULT NULL,
  `recovery_count` int DEFAULT NULL,
  `requested_function_count` int DEFAULT NULL,
  `requested_tool_count` int DEFAULT NULL,
  `retry_count` int DEFAULT NULL,
  `runtime_dispatched` bit(1) DEFAULT NULL,
  `runtime_token_exchange_count` int DEFAULT NULL,
  `runtime_token_issued` bit(1) DEFAULT NULL,
  `runtime_token_request_received` bit(1) DEFAULT NULL,
  `safe_smoke_request_received` bit(1) DEFAULT NULL,
  `standard_ask_request_received` bit(1) DEFAULT NULL,
  `synthetic_evidence_created` bit(1) DEFAULT NULL,
  `task_created` bit(1) DEFAULT NULL,
  `task_token_function_scope_empty` bit(1) DEFAULT NULL,
  `task_token_issued` bit(1) DEFAULT NULL,
  `terminal` bit(1) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `received_at` datetime(6) NOT NULL,
  `operation` varchar(32) NOT NULL,
  `client_request_id` varchar(36) NOT NULL,
  `correlation_id` varchar(36) NOT NULL,
  `parent_client_request_id` varchar(36) DEFAULT NULL,
  `status` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) DEFAULT NULL,
  `task_token_status` varchar(64) DEFAULT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `credential_id` varchar(128) NOT NULL,
  `function_scope_source` varchar(128) DEFAULT NULL,
  `model_config_id` varchar(128) DEFAULT NULL,
  `physical_worker_id` varchar(128) DEFAULT NULL,
  `result` varchar(128) DEFAULT NULL,
  `sanitized_error_code` varchar(128) DEFAULT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `tool_scope_kind` varchar(128) DEFAULT NULL,
  `tool_scope_source` varchar(128) DEFAULT NULL,
  `upstream_system_id` varchar(128) NOT NULL,
  `agent_code` varchar(255) DEFAULT NULL,
  `model_variant` varchar(255) DEFAULT NULL,
  `safe_error_summary` varchar(255) DEFAULT NULL,
  `upstream_user_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_runtime_audit_request` (`client_request_id`),
  KEY `idx_runtime_audit_scope_time` (`tenant_id`,`upstream_system_id`,`client_app_id`,`received_at`),
  KEY `idx_runtime_audit_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `runtime_request_audit_stage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `occurred_at` datetime(6) NOT NULL,
  `status` varchar(32) NOT NULL,
  `client_request_id` varchar(36) NOT NULL,
  `stage` varchar(64) NOT NULL,
  `sanitized_error_code` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_runtime_audit_stage_request_time` (`client_request_id`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `session_lifecycle_snapshots` (
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `canonical_phase` varchar(16) NOT NULL,
  `ownership_mode` varchar(16) NOT NULL,
  `foreground_lane_state` varchar(24) NOT NULL,
  `availability` varchar(32) NOT NULL,
  `conflict_state` varchar(48) NOT NULL,
  `foreground_task_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `writer_generation_id` varchar(96) DEFAULT NULL,
  `physical_worker_id` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `session_message_payloads` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `original_bytes` bigint NOT NULL,
  `stored_bytes` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL,
  `backend` varchar(32) NOT NULL,
  `content_encoding` varchar(32) NOT NULL,
  `id` varchar(64) NOT NULL,
  `message_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `sha256` varchar(64) NOT NULL,
  `content_type` varchar(128) NOT NULL,
  `storage_key` varchar(512) DEFAULT NULL,
  `status` enum('EXPIRED','PENDING','READY','UNAVAILABLE') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_smp_message_id` (`message_id`),
  KEY `idx_smp_session_id` (`session_id`),
  KEY `idx_smp_status_expires_at` (`status`,`expires_at`),
  KEY `idx_smp_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `session_messages` (
  `created_at` datetime(6) NOT NULL,
  `role` varchar(32) NOT NULL,
  `id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) DEFAULT NULL,
  `content` mediumtext,
  `metadata` mediumtext,
  PRIMARY KEY (`id`),
  KEY `idx_msg_session_id` (`session_id`),
  KEY `idx_msg_created_at` (`created_at`),
  KEY `idx_msg_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `session_relations` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `relation_type` varchar(32) NOT NULL,
  `target_mode` varchar(32) NOT NULL,
  `target_provider_type` varchar(32) DEFAULT NULL,
  `source_directory_id` varchar(64) DEFAULT NULL,
  `source_message_id` varchar(64) DEFAULT NULL,
  `source_milestone_id` varchar(64) DEFAULT NULL,
  `source_session_id` varchar(64) NOT NULL,
  `source_worker_id` varchar(64) DEFAULT NULL,
  `target_directory_id` varchar(64) DEFAULT NULL,
  `target_milestone_id` varchar(64) DEFAULT NULL,
  `target_model_config_id` varchar(64) DEFAULT NULL,
  `target_session_id` varchar(64) NOT NULL,
  `target_worker_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `metadata_json` text,
  PRIMARY KEY (`id`),
  KEY `idx_sr_user_id` (`user_id`),
  KEY `idx_sr_source_session_id` (`source_session_id`),
  KEY `idx_sr_target_session_id` (`target_session_id`),
  KEY `idx_sr_source_message_id` (`source_message_id`),
  KEY `idx_sr_relation_type` (`relation_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `session_tasks` (
  `cost_usd` decimal(10,6) DEFAULT NULL,
  `last_acked_seq` int DEFAULT NULL,
  `num_turns` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `input_tokens` bigint DEFAULT NULL,
  `last_alive_at` datetime(6) DEFAULT NULL,
  `last_output_at` datetime(6) DEFAULT NULL,
  `output_tokens` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `source` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) DEFAULT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `model` varchar(128) DEFAULT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  `error_message` text,
  `prompt` text,
  `result_text` mediumtext,
  `task_state_json` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_st_task_id` (`task_id`),
  KEY `idx_st_session_id` (`session_id`),
  KEY `idx_st_user_id` (`user_id`),
  KEY `idx_st_worker_id` (`worker_id`),
  KEY `idx_st_directory_id` (`directory_id`),
  KEY `idx_st_status` (`status`),
  KEY `idx_st_provider_type` (`provider_type`),
  KEY `idx_st_agent_id` (`agent_id`),
  KEY `idx_st_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `sessions` (
  `pinned` bit(1) NOT NULL,
  `auth_bound_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `last_activity_at` datetime(6) DEFAULT NULL,
  `pinned_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auth_mode` varchar(32) DEFAULT NULL,
  `binding_source` varchar(32) DEFAULT NULL,
  `interaction_state` varchar(32) DEFAULT NULL,
  `provider_type` varchar(32) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `auth_model_config_id` varchar(64) DEFAULT NULL,
  `current_directory_id` varchar(64) DEFAULT NULL,
  `current_worker_id` varchar(64) DEFAULT NULL,
  `id` varchar(64) NOT NULL,
  `latest_task_id` varchar(64) DEFAULT NULL,
  `milestone_id` varchar(64) DEFAULT NULL,
  `parent_session_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) DEFAULT NULL,
  `latest_model` varchar(128) DEFAULT NULL,
  `title` varchar(256) DEFAULT NULL,
  `auth_base_url` varchar(512) DEFAULT NULL,
  `auth_token_ciphertext` text,
  `participating_agent_ids` text,
  `provider_state_json` text,
  `summary` text,
  `tags_json` text,
  PRIMARY KEY (`id`),
  KEY `idx_session_user_id` (`user_id`),
  KEY `idx_session_tenant_id` (`tenant_id`),
  KEY `idx_session_agent_id` (`agent_id`),
  KEY `idx_session_status` (`status`),
  KEY `idx_session_parent_id` (`parent_session_id`),
  KEY `idx_session_interaction_state` (`interaction_state`),
  KEY `idx_session_current_worker_id` (`current_worker_id`),
  KEY `idx_session_last_activity_at` (`last_activity_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `sharing_keys` (
  `call_date` date DEFAULT NULL,
  `enabled` bit(1) NOT NULL,
  `max_daily_calls` int NOT NULL,
  `max_turns` int NOT NULL,
  `today_calls` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `last_used_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `agent_id` varchar(64) NOT NULL,
  `id` varchar(64) NOT NULL,
  `owner_user_id` varchar(64) NOT NULL,
  `label` varchar(128) DEFAULT NULL,
  `sharing_key` varchar(128) NOT NULL,
  `allowed_operations` varchar(512) DEFAULT NULL,
  `system_prompt` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_sk_key` (`sharing_key`),
  KEY `idx_sk_owner` (`owner_user_id`),
  KEY `idx_sk_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `skill` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `context_visibility` varchar(32) NOT NULL,
  `status` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `description` text,
  `markdown_body` longtext,
  `name` varchar(255) NOT NULL,
  `resources_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK5ndx5tlq9v0936qgkgjnx3j4` (`tenant_id`,`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `skill_bundle` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `context_visibility` varchar(32) NOT NULL,
  `scope` varchar(64) NOT NULL,
  `status` varchar(64) NOT NULL,
  `account_id` varchar(128) NOT NULL,
  `bundle_id` varchar(128) NOT NULL,
  `client_app_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `description` text,
  `functions_json` longtext,
  `markdown_body` longtext,
  `name` varchar(255) NOT NULL,
  `resources_json` longtext,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKexijhpeoksg5s09s79nojs5hr` (`tenant_id`,`client_app_id`,`scope`,`account_id`,`skill_id`),
  UNIQUE KEY `UKkibly7d51n115luns6fv08wgu` (`bundle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `skill_configs` (
  `priority` int DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `name` varchar(128) NOT NULL,
  `delegation_condition` text,
  `description` text,
  `execution_logic` text,
  `intents` text,
  `markdown_content` longtext,
  `output_format` text,
  `trigger_keywords` text,
  `scope` enum('AGENT','GLOBAL','SYSTEM','TENANT') NOT NULL,
  `status` enum('DISABLED','DRAFT','ENABLED') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_skill_tenant_id` (`tenant_id`),
  KEY `idx_skill_scope` (`scope`),
  KEY `idx_skill_agent_id` (`agent_id`),
  KEY `idx_skill_status` (`status`),
  KEY `idx_skill_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `skill_function_allowlist` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `status` varchar(64) NOT NULL,
  `allowlist_id` varchar(128) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `skill_id` varchar(128) NOT NULL,
  `tenant_id` varchar(128) NOT NULL,
  `function_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK615x0iqmhuoc1ndmxla4lu25r` (`tenant_id`,`skill_id`,`function_id`),
  UNIQUE KEY `UKpfa4kv1jvh8q2iy0ba3yv2a11` (`allowlist_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `task_assistant_configs` (
  `auto_summary_enabled` bit(1) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `directory_id` varchar(64) DEFAULT NULL,
  `foggy_session_id` varchar(64) DEFAULT NULL,
  `model_config_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `claude_session_id` varchar(128) DEFAULT NULL,
  `model` varchar(128) DEFAULT NULL,
  `cwd` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKkodi7f4dlx7os5eq5qn9stq2r` (`user_id`),
  KEY `idx_tac_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `task_lifecycle_snapshots` (
  `fact_cursor` bigint NOT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `canonical_phase` varchar(16) NOT NULL,
  `ownership_mode` varchar(16) NOT NULL,
  `cleanup_state` varchar(24) NOT NULL,
  `availability` varchar(32) NOT NULL,
  `safe_binding_digest_version` varchar(32) DEFAULT NULL,
  `terminal_outcome` varchar(32) DEFAULT NULL,
  `conflict_state` varchar(48) NOT NULL,
  `policy_version` varchar(48) NOT NULL,
  `terminal_source` varchar(48) DEFAULT NULL,
  `operation_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `dispatch_id` varchar(96) DEFAULT NULL,
  `writer_generation_id` varchar(96) DEFAULT NULL,
  `instance_epoch` varchar(128) DEFAULT NULL,
  `physical_worker_id` varchar(128) DEFAULT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  `safe_binding_digest` varchar(128) DEFAULT NULL,
  `state_generation` varchar(128) DEFAULT NULL,
  `snapshot_json` mediumtext NOT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `task_terminal_cleanup_plan` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `applicability` varchar(24) NOT NULL,
  `checkpoint_state` varchar(24) NOT NULL,
  `participant` varchar(48) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `checkpoint_fact_id` varchar(96) DEFAULT NULL,
  `not_applicable_reason` varchar(96) DEFAULT NULL,
  PRIMARY KEY (`participant`,`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `task_terminal_tombstones` (
  `recorded_at` datetime(6) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `terminal_outcome` varchar(32) NOT NULL,
  `terminal_source` varchar(48) NOT NULL,
  `operation_id` varchar(64) DEFAULT NULL,
  `provider_task_user_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `source_agent_id` varchar(64) DEFAULT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `client_request_id` varchar(96) DEFAULT NULL,
  `terminal_fact_id` varchar(96) NOT NULL,
  `writer_generation_id` varchar(96) NOT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `UKkcui2nyw3vm44e2xtatblaoti` (`terminal_fact_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `task_terminal_cleanup_repairs` (
  `recorded_at` datetime(6) NOT NULL,
  `repair_accepted` bit(1) NOT NULL,
  `terminal_tombstone_present` bit(1) NOT NULL,
  `cleanup_complete` bit(1) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `client_request_id` varchar(96) NOT NULL,
  `safe_reason_code` varchar(96) NOT NULL,
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_ttcr_client_request` (`client_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `termination_operations` (
  `expected_pid` int DEFAULT NULL,
  `schema_version` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `dispatched_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `observed_at` datetime(6) DEFAULT NULL,
  `requested_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `actor_type` varchar(32) NOT NULL,
  `dispatch_state` varchar(32) NOT NULL,
  `kind` varchar(32) NOT NULL,
  `origin` varchar(32) NOT NULL,
  `provider_type` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `actor_id` varchar(64) NOT NULL,
  `operation_id` varchar(64) NOT NULL,
  `owner_user_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `worker_id` varchar(64) NOT NULL,
  `authorization_decision_id` varchar(128) DEFAULT NULL,
  `correlation_id` varchar(128) DEFAULT NULL,
  `provider_task_id` varchar(128) DEFAULT NULL,
  `attention_code` varchar(160) DEFAULT NULL,
  `expected_process_identity` varchar(160) DEFAULT NULL,
  `failure_code` varchar(160) DEFAULT NULL,
  `reason_code` varchar(160) NOT NULL,
  PRIMARY KEY (`operation_id`),
  KEY `idx_to_task_id` (`task_id`),
  KEY `idx_to_provider_task_id` (`provider_task_id`),
  KEY `idx_to_session_id` (`session_id`),
  KEY `idx_to_owner_scope` (`owner_user_id`,`tenant_id`),
  KEY `idx_to_worker_id` (`worker_id`),
  KEY `idx_to_status` (`status`),
  KEY `idx_to_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `upstream_bootstrap_audit` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_code_suffix` varchar(16) DEFAULT NULL,
  `actor_type` varchar(32) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `request_status` varchar(32) NOT NULL,
  `audit_id` varchar(64) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `actor_id` varchar(128) DEFAULT NULL,
  `message` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK3ndnc0157o25hg2m9oqgxnr88` (`audit_id`),
  KEY `idx_uba_request` (`request_id`),
  KEY `idx_uba_event` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `upstream_bootstrap_request` (
  `multi_tenant` bit(1) NOT NULL,
  `admin_credential_expires_at` datetime(6) DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `claim_expires_at` datetime(6) DEFAULT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `denied_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_expires_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `request_code_suffix` varchar(16) NOT NULL,
  `status` varchar(32) NOT NULL,
  `approved_by_operator_credential_id` varchar(64) DEFAULT NULL,
  `approved_by_user_id` varchar(64) DEFAULT NULL,
  `request_id` varchar(64) NOT NULL,
  `requested_tenant_id` varchar(64) NOT NULL,
  `applicant_label` varchar(128) DEFAULT NULL,
  `authorized_client_app_namespace` varchar(128) DEFAULT NULL,
  `claim_token_hash` varchar(128) NOT NULL,
  `request_code_hash` varchar(128) NOT NULL,
  `source_ip_hash` varchar(128) DEFAULT NULL,
  `upstream_system_id` varchar(128) NOT NULL,
  `authorized_tenant_ids_json` text,
  `denied_reason` text,
  `reason` text,
  `scopes_json` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8lk12bn5l55dr7slyixpypvxh` (`request_id`),
  UNIQUE KEY `UKhv5nh0kpj9keh9mokktkow3h7` (`request_code_hash`),
  KEY `idx_ubr_status` (`status`),
  KEY `idx_ubr_requested_tenant` (`requested_tenant_id`),
  KEY `idx_ubr_upstream_system` (`upstream_system_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `upstream_client_app_admin_credential` (
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_used_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `credential_key_prefix` varchar(16) NOT NULL,
  `credential_key_suffix` varchar(16) NOT NULL,
  `status` varchar(32) NOT NULL,
  `credential_id` varchar(64) NOT NULL,
  `issued_by_operator_credential_id` varchar(64) DEFAULT NULL,
  `issued_by_user_id` varchar(64) DEFAULT NULL,
  `source_request_id` varchar(64) NOT NULL,
  `authorized_client_app_namespace` varchar(128) NOT NULL,
  `credential_key_hash` varchar(128) NOT NULL,
  `upstream_system_id` varchar(128) NOT NULL,
  `authorized_tenant_ids_json` text NOT NULL,
  `scopes_json` text NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKschuwn4gs4hxb468tacr36e2w` (`credential_id`),
  UNIQUE KEY `UKhu5wvqpg278r8viia4f6w4f5n` (`credential_key_hash`),
  KEY `idx_ucaac_upstream_system` (`upstream_system_id`),
  KEY `idx_ucaac_status` (`status`),
  KEY `idx_ucaac_source_request` (`source_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `user_memories` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `content` text NOT NULL,
  `category` enum('FACT','NOTE','PREFERENCE') NOT NULL,
  `source` enum('AUTO','MANUAL') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_um_user_id` (`user_id`),
  KEY `idx_um_user_category` (`user_id`,`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `users` (
  `created_at` datetime(6) NOT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(64) NOT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `display_name` varchar(128) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `username` varchar(128) NOT NULL,
  `roles` varchar(256) NOT NULL,
  `password_hash` varchar(512) NOT NULL,
  `status` enum('ACTIVE','DELETED','DISABLED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_username` (`username`),
  KEY `idx_email` (`email`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `worker_lifecycle_sentinel_leases` (
  `expires_at` datetime(6) NOT NULL,
  `fence_token` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `holder_instance_id` varchar(128) NOT NULL,
  `physical_worker_id` varchar(128) NOT NULL,
  PRIMARY KEY (`physical_worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `worker_lifecycle_snapshots` (
  `fact_cursor` bigint NOT NULL,
  `row_version` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `ownership_mode` varchar(16) NOT NULL,
  `availability` varchar(32) NOT NULL,
  `conflict_state` varchar(48) NOT NULL,
  `policy_version` varchar(48) NOT NULL,
  `writer_generation_id` varchar(96) DEFAULT NULL,
  `instance_epoch` varchar(128) DEFAULT NULL,
  `physical_worker_id` varchar(128) NOT NULL,
  `state_generation` varchar(128) DEFAULT NULL,
  `snapshot_json` mediumtext NOT NULL,
  PRIMARY KEY (`physical_worker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `working_directories` (
  `enabled` bit(1) DEFAULT NULL,
  `read_only` bit(1) DEFAULT NULL,
  `worktree` bit(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_synced_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `default_auth_mode` varchar(32) DEFAULT NULL,
  `directory_type` varchar(32) NOT NULL,
  `git_provider` varchar(32) DEFAULT NULL,
  `git_status` varchar(32) DEFAULT NULL,
  `client_app_id` varchar(64) DEFAULT NULL,
  `default_model_config_id` varchar(64) DEFAULT NULL,
  `directory_id` varchar(64) NOT NULL,
  `parent_project_id` varchar(64) DEFAULT NULL,
  `source_directory_id` varchar(64) DEFAULT NULL,
  `tenant_id` varchar(64) DEFAULT NULL,
  `user_id` varchar(64) NOT NULL,
  `worker_id` varchar(64) NOT NULL,
  `git_branch` varchar(128) DEFAULT NULL,
  `project_name` varchar(128) NOT NULL,
  `resolver_key` varchar(128) DEFAULT NULL,
  `upstream_user_id` varchar(128) DEFAULT NULL,
  `owner_id` varchar(256) DEFAULT NULL,
  `default_base_url` varchar(512) DEFAULT NULL,
  `git_remote_url` varchar(512) DEFAULT NULL,
  `path` varchar(512) NOT NULL,
  `root_ref` varchar(512) DEFAULT NULL,
  `agent_teams_config` text,
  `allowed_path_prefixes_json` text,
  `concurrency_policy_json` text,
  `custom_env_vars` text,
  `default_auth_token` text,
  `milestones_json` text,
  `project_task_prompt` text,
  `quota_json` text,
  `retention_policy_json` text,
  `owner_type` enum('CLIENT_APP','PLATFORM','UPSTREAM_SYSTEM','UPSTREAM_USER') DEFAULT NULL,
  `resolver_type` enum('DELEGATED','MANAGED') DEFAULT NULL,
  `workspace_scope` enum('CLIENT_APP_SHARED','UPSTREAM_SYSTEM_SHARED','USER_PRIVATE') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1knjro2i3pk5ujy2rpddshcdn` (`directory_id`),
  KEY `idx_wd_worker_id` (`worker_id`),
  KEY `idx_wd_user_id` (`user_id`),
  KEY `idx_wd_parent_project` (`parent_project_id`),
  KEY `idx_wd_owner` (`owner_type`,`owner_id`),
  KEY `idx_wd_upstream_scope` (`tenant_id`,`client_app_id`,`upstream_user_id`,`workspace_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
