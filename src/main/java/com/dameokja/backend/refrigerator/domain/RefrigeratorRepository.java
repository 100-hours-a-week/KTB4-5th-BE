package com.dameokja.backend.refrigerator.domain;

import java.util.Optional;

public interface RefrigeratorRepository {
    Refrigerator save(Refrigerator refrigerator);
    Optional<Refrigerator> findById(Long id);
}
