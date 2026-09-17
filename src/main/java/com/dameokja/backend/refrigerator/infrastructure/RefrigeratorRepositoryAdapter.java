package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefrigeratorRepositoryAdapter implements RefrigeratorRepository {
    private final RefrigeratorJpaRepository repository;

    @Override public Refrigerator save(Refrigerator refrigerator) { return repository.save(refrigerator); }
    @Override public Optional<Refrigerator> findById(Long id) { return repository.findById(id); }
    @Override public Optional<Refrigerator> findByIdForUpdate(Long id) { return repository.findByIdForUpdate(id); }
}
