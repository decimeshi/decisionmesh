package com.decisionmesh.billing.repository;

import com.decisionmesh.billing.model.CreditLedgerEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CreditLedgerRepository
        implements PanacheRepositoryBase<CreditLedgerEntity, UUID> {

    /** Current balance = SUM(amount) for this org. */
    public Uni<Long> sumByOrgId(UUID orgId) {
        return find("orgId", orgId)
                .list()
                .map(entries -> entries.stream()
                        .mapToLong(e -> e.amount)
                        .sum());
    }

    /** Paginated ledger history — newest first. */
    public Uni<List<CreditLedgerEntity>> findPageByOrgId(UUID orgId, int page, int size) {
        return find("orgId = ?1 ORDER BY createdAt DESC", orgId)
                .page(page, size)
                .list();
    }
}
