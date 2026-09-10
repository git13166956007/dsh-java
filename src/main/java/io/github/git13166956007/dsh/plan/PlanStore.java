package io.github.git13166956007.dsh.plan;

import java.util.List;

public interface PlanStore {
    List<PlanData> listPlans() throws Exception;

    List<PlanStepData> listSteps(String planId) throws Exception;

    void savePlan(PlanData plan) throws Exception;

    void saveStep(PlanStepData step) throws Exception;

    void deletePlan(String id) throws Exception;
}
