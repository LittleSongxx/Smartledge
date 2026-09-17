package org.smartledge.ai.manage.service;

import org.smartledge.ai.manage.model.SystemConfigSnapshot;

public interface SystemConfigProvider {

    SystemConfigSnapshot currentSnapshot();

    default Integer instanceStartVersion() {
        return null;
    }

    default void invalidate() {
    }
}
