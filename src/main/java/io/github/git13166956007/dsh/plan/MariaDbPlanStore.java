package io.github.git13166956007.dsh.plan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class MariaDbPlanStore implements PlanStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MariaDbPlanStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        ensureSchema();
    }

    @Override
    public List<PlanData> listPlans() throws SQLException {
        List<PlanData> result = new ArrayList<PlanData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, title, goal, agent_id, model_id, approval_required, max_concurrency, status, created_at, updated_at "
                             + "FROM dsh_plan ORDER BY created_at DESC, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(readPlan(rows));
        }
        return result;
    }

    @Override
    public List<PlanStepData> listSteps(String planId) throws SQLException {
        List<PlanStepData> result = new ArrayList<PlanStepData>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, plan_id, step_no, sub_agent_id, depends_on, title, instruction, status, result_text, attempts, max_attempts "
                             + "FROM dsh_plan_step WHERE plan_id = ? ORDER BY step_no, id")) {
            statement.setString(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readStep(rows));
            }
        }
        return result;
    }

    @Override
    public void savePlan(PlanData plan) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_plan (id, title, goal, agent_id, model_id, approval_required, max_concurrency, status, created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title=VALUES(title), "
                             + "goal=VALUES(goal), agent_id=VALUES(agent_id), model_id=VALUES(model_id), "
                             + "approval_required=VALUES(approval_required), max_concurrency=VALUES(max_concurrency), "
                             + "status=VALUES(status), updated_at=VALUES(updated_at)")) {
            statement.setString(1, plan.id());
            statement.setString(2, plan.title());
            statement.setString(3, plan.goal());
            statement.setString(4, plan.agentId());
            statement.setString(5, plan.modelId());
            statement.setBoolean(6, plan.approvalRequired());
            statement.setInt(7, plan.maxConcurrency());
            statement.setString(8, plan.status().value());
            statement.setTimestamp(9, Timestamp.from(plan.createdAt()));
            statement.setTimestamp(10, Timestamp.from(plan.updatedAt()));
            statement.executeUpdate();
        }
    }

    @Override
    public void saveStep(PlanStepData step) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO dsh_plan_step (id, plan_id, step_no, sub_agent_id, depends_on, title, instruction, status, result_text, attempts, max_attempts) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE step_no=VALUES(step_no), "
                             + "sub_agent_id=VALUES(sub_agent_id), "
                             + "depends_on=VALUES(depends_on), "
                             + "title=VALUES(title), instruction=VALUES(instruction), status=VALUES(status), "
                             + "result_text=VALUES(result_text), attempts=VALUES(attempts), max_attempts=VALUES(max_attempts)")) {
            statement.setString(1, step.id());
            statement.setString(2, step.planId());
            statement.setInt(3, step.stepNo());
            statement.setString(4, step.subAgentId());
            statement.setString(5, joinIntegers(step.dependsOn()));
            statement.setString(6, step.title());
            statement.setString(7, step.instruction());
            statement.setString(8, step.status().value());
            statement.setString(9, step.result());
            statement.setInt(10, step.attempts());
            statement.setInt(11, step.maxAttempts());
            statement.executeUpdate();
        }
    }

    @Override
    public void deletePlan(String id) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM dsh_plan WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void ensureSchema() {
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS dsh_plan ("
                            + "id VARCHAR(64) NOT NULL PRIMARY KEY, title VARCHAR(255) NOT NULL, goal TEXT NOT NULL, "
                            + "agent_id VARCHAR(64) NULL, model_id VARCHAR(64) NULL, approval_required BOOLEAN NOT NULL DEFAULT TRUE, "
                            + "max_concurrency INT NOT NULL DEFAULT 1, "
                            + "status VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, "
                            + "INDEX idx_dsh_plan_status (status), INDEX idx_dsh_plan_updated (updated_at)) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS dsh_plan_step ("
                            + "id VARCHAR(64) NOT NULL PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, step_no INT NOT NULL, sub_agent_id VARCHAR(64) NULL, depends_on VARCHAR(255) NULL, "
                            + "title VARCHAR(255) NOT NULL, instruction TEXT NOT NULL, status VARCHAR(32) NOT NULL, "
                            + "result_text LONGTEXT NULL, attempts INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 1, "
                            + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                            + "CONSTRAINT fk_dsh_plan_step_plan FOREIGN KEY (plan_id) REFERENCES dsh_plan (id) ON DELETE CASCADE, "
                            + "UNIQUE KEY uq_dsh_plan_step_no (plan_id, step_no), INDEX idx_dsh_plan_step_status (plan_id, status)) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "ALTER TABLE dsh_plan ADD COLUMN IF NOT EXISTS max_concurrency INT NOT NULL DEFAULT 1")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "ALTER TABLE dsh_plan_step ADD COLUMN IF NOT EXISTS sub_agent_id VARCHAR(64) NULL")) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "ALTER TABLE dsh_plan_step ADD COLUMN IF NOT EXISTS depends_on VARCHAR(255) NULL")) {
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize plan schema", exception);
        }
    }

    private PlanData readPlan(ResultSet rows) throws SQLException {
        return new PlanData(rows.getString("id"), rows.getString("title"), rows.getString("goal"),
                rows.getString("agent_id"), rows.getString("model_id"), rows.getBoolean("approval_required"),
                rows.getInt("max_concurrency"),
                PlanStatus.parse(rows.getString("status")), instant(rows.getTimestamp("created_at")),
                instant(rows.getTimestamp("updated_at")));
    }

    private PlanStepData readStep(ResultSet rows) throws SQLException {
        return new PlanStepData(rows.getString("id"), rows.getString("plan_id"), rows.getInt("step_no"),
                rows.getString("sub_agent_id"), splitIntegers(rows.getString("depends_on")), rows.getString("title"), rows.getString("instruction"), PlanStepStatus.parse(rows.getString("status")),
                rows.getString("result_text"), rows.getInt("attempts"), rows.getInt("max_attempts"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String joinIntegers(List<Integer> values) {
        return values == null || values.isEmpty() ? null : values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> splitIntegers(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .map(Integer::valueOf).toList();
    }
}
