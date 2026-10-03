package dev.entity.core.persistence;

import dev.entity.core.model.Mission;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface MissionStore {
    List<Mission> load() throws IOException;

    void save(Collection<Mission> missions) throws IOException;
}
