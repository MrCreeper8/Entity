package dev.entity.core.persistence;

import dev.entity.core.plan.TaskPlan;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** Persistence boundary for durable mission task plans. */
public interface PlanStore {
    List<TaskPlan> load() throws IOException;

    void save(Collection<TaskPlan> plans) throws IOException;
}
