CREATE DATABASE IF NOT EXISTS dsh CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dsh;

CREATE TABLE IF NOT EXISTS dsh_workspace_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    directory VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    write_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    max_read_bytes BIGINT NOT NULL DEFAULT 1000000,
    max_write_bytes BIGINT NOT NULL DEFAULT 1000000,
    max_process_timeout_seconds INT NOT NULL DEFAULT 120,
    max_process_output_bytes BIGINT NOT NULL DEFAULT 1000000,
    allowed_commands TEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_workspace_active (active, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_conversation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    summary_text LONGTEXT NULL,
    summary_message_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS dsh_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    turn_no INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NULL,
    reasoning_content LONGTEXT NULL,
    tool_calls_json LONGTEXT NULL,
    tool_call_id VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_dsh_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES dsh_conversation (id),
    INDEX idx_dsh_message_conversation (conversation_id, turn_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_session_event (
    id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    UNIQUE KEY uq_dsh_session_event_sequence (session_id, sequence_no),
    INDEX idx_dsh_session_event_session (session_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_session_event_head (
    session_id CHAR(36) NOT NULL PRIMARY KEY,
    sequence_no BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_tool_definition (
    name VARCHAR(128) NOT NULL PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NULL,
    description TEXT NOT NULL,
    input_schema_json LONGTEXT NOT NULL,
    result_text LONGTEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_tool_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_mcp_server (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    transport VARCHAR(16) NOT NULL,
    endpoint VARCHAR(1000) NULL,
    command VARCHAR(1000) NULL,
    arguments_json TEXT NULL,
    headers_json LONGTEXT NULL,
    environment_json LONGTEXT NULL,
    query_params_json LONGTEXT NULL,
    credential_ref VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_memory (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    namespace VARCHAR(128) NOT NULL,
    subject_key VARCHAR(255) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    metadata_json TEXT NULL,
    importance DECIMAL(5, 4) NOT NULL DEFAULT 0.5000,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_memory_subject (namespace, subject_key, updated_at),
    FULLTEXT INDEX ft_dsh_memory_content (content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_skill_state (
    skill_id VARCHAR(255) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_run (
    id CHAR(36) NOT NULL PRIMARY KEY,
    parent_run_id CHAR(36) NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    conversation_id CHAR(36) NULL,
    plan_id VARCHAR(64) NULL,
    step_id VARCHAR(64) NULL,
    agent_id VARCHAR(64) NULL,
    model_id VARCHAR(64) NULL,
    started_at TIMESTAMP(3) NOT NULL,
    completed_at TIMESTAMP(3) NULL,
    error_text LONGTEXT NULL,
    output_text LONGTEXT NULL,
    INDEX idx_dsh_run_parent (parent_run_id),
    INDEX idx_dsh_run_plan (plan_id),
    INDEX idx_dsh_run_conversation (conversation_id),
    INDEX idx_dsh_run_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_run_event (
    event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    event_key VARCHAR(191) NULL,
    event_type VARCHAR(64) NOT NULL,
    payload LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_dsh_run_event_run FOREIGN KEY (run_id) REFERENCES dsh_run (id) ON DELETE CASCADE,
    UNIQUE KEY uq_dsh_run_event_key (run_id, event_key),
    INDEX idx_dsh_run_event_run (run_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_agent_continuation (
    run_id CHAR(36) NOT NULL PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_dsh_agent_continuation_run FOREIGN KEY (run_id) REFERENCES dsh_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_model_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    api_key LONGTEXT NULL,
    proxy_host VARCHAR(255) NULL,
    proxy_port INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    fallback_model_id VARCHAR(64) NULL,
    supports_tools BOOLEAN NOT NULL DEFAULT TRUE,
    supports_streaming BOOLEAN NOT NULL DEFAULT TRUE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    context_window INT NOT NULL DEFAULT 0,
    temperature DOUBLE NULL,
    top_p DOUBLE NULL,
    max_tokens INT NULL,
    frequency_penalty DOUBLE NULL,
    presence_penalty DOUBLE NULL,
    timeout_seconds INT NOT NULL DEFAULT 120,
    request_options_json LONGTEXT NULL,
    failover_policy VARCHAR(32) NOT NULL DEFAULT 'any_failure',
    input_price_per_million_tokens DOUBLE NULL,
    output_price_per_million_tokens DOUBLE NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_model_active (active, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_model_health (
    model_id VARCHAR(64) NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    last_latency_ms BIGINT NULL,
    last_checked_at TIMESTAMP(3) NULL,
    last_success_at TIMESTAMP(3) NULL,
    last_error TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_model_usage (
    model_id VARCHAR(64) NOT NULL PRIMARY KEY,
    request_count BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd DOUBLE NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_mcp_health (
    server_id VARCHAR(64) NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    last_latency_ms BIGINT NULL,
    last_checked_at TIMESTAMP(3) NULL,
    last_connected_at TIMESTAMP(3) NULL,
    last_disconnected_at TIMESTAMP(3) NULL,
    last_error TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_mcp_resource_subscription (
    server_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    uri TEXT NOT NULL,
    uri_hash BINARY(32) NOT NULL,
    subscribed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (server_id, uri_hash),
    CONSTRAINT fk_dsh_mcp_subscription_server FOREIGN KEY (server_id)
        REFERENCES dsh_mcp_server(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_agent_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    model_id VARCHAR(64) NULL,
    system_prompt TEXT NULL,
    max_turns INT NOT NULL DEFAULT 8,
    max_tool_calls INT NOT NULL DEFAULT 64,
    timeout_seconds INT NOT NULL DEFAULT 300,
    max_depth INT NOT NULL DEFAULT 4,
    allowed_tools_json TEXT NULL,
    skill_ids_json TEXT NULL,
    permissions_json TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_agent_active (active, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_event_journal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_name VARCHAR(255) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    value_json LONGTEXT NULL,
    accepted BOOLEAN NOT NULL,
    reason TEXT NULL,
    error TEXT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    INDEX idx_dsh_event_journal_time (occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_sub_agent_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    model_id VARCHAR(64) NULL,
    system_prompt TEXT NULL,
    max_turns INT NOT NULL DEFAULT 8,
    allowed_tools TEXT NULL,
    skill_ids TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_tool_calls INT NOT NULL DEFAULT 64,
    timeout_seconds INT NOT NULL DEFAULT 300,
    max_depth INT NOT NULL DEFAULT 4,
    priority INT NOT NULL DEFAULT 50,
    cost_weight DOUBLE NOT NULL DEFAULT 1.0,
    max_concurrent_runs INT NOT NULL DEFAULT 4,
    capability_tags TEXT NULL,
    permissions_json TEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_sub_agent_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_sub_agent_session (
    id CHAR(36) NOT NULL PRIMARY KEY,
    profile_id VARCHAR(64) NOT NULL,
    conversation_id CHAR(36) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    INDEX idx_dsh_sub_agent_session_profile (profile_id),
    INDEX idx_dsh_sub_agent_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_plan (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    goal TEXT NOT NULL,
    agent_id VARCHAR(64) NULL,
    model_id VARCHAR(64) NULL,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrency INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    INDEX idx_dsh_plan_status (status),
    INDEX idx_dsh_plan_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_plan_step (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    step_no INT NOT NULL,
    sub_agent_id VARCHAR(64) NULL,
    depends_on VARCHAR(255) NULL,
    title VARCHAR(255) NOT NULL,
    instruction TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_text LONGTEXT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_dsh_plan_step_plan FOREIGN KEY (plan_id) REFERENCES dsh_plan (id) ON DELETE CASCADE,
    UNIQUE KEY uq_dsh_plan_step_no (plan_id, step_no),
    INDEX idx_dsh_plan_step_status (plan_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
