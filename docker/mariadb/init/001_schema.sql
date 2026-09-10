CREATE DATABASE IF NOT EXISTS dsh CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dsh;

CREATE TABLE IF NOT EXISTS dsh_conversation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS dsh_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    turn_no INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NULL,
    tool_calls_json LONGTEXT NULL,
    tool_call_id VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_dsh_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES dsh_conversation (id),
    INDEX idx_dsh_message_conversation (conversation_id, turn_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_tool_definition (
    name VARCHAR(128) NOT NULL PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NULL,
    description TEXT NOT NULL,
    input_schema_json LONGTEXT NOT NULL,
    result_text LONGTEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
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
    environment_json TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
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
    event_type VARCHAR(64) NOT NULL,
    payload LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_dsh_run_event_run FOREIGN KEY (run_id) REFERENCES dsh_run (id) ON DELETE CASCADE,
    INDEX idx_dsh_run_event_run (run_id, event_id)
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
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_model_active (active, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dsh_agent_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    model_id VARCHAR(64) NULL,
    system_prompt TEXT NULL,
    max_turns INT NOT NULL DEFAULT 8,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_agent_active (active, enabled)
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
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_dsh_sub_agent_enabled (enabled)
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
