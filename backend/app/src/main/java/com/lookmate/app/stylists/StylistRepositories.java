package com.lookmate.app.stylists;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// The module's repositories, in one file: they are small and only this package uses them.

interface SalonLocationRepository extends JpaRepository<SalonLocation, UUID> {

    List<SalonLocation> findAllByOrderByMarketAscNameAsc();

    List<SalonLocation> findByMarketOrderByNameAsc(String market);

    List<SalonLocation> findByMarketAndActiveTrueOrderByNameAsc(String market);
}

interface StylistProfileRepository extends JpaRepository<StylistProfile, UUID> {

    /** Row-locked, so a publish and an edit can't interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StylistProfile p where p.orgId = :orgId")
    Optional<StylistProfile> findLocked(@Param("orgId") UUID orgId);

    List<StylistProfile> findByMarketAndStatusOrderByDisplayNameAsc(String market, ProfileStatus status);
}

interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Optional<Photo> findByIdAndOrgId(UUID id, UUID orgId);

    /** Row-locked: a repeated confirm waits for the first and sees its result. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id = :id and p.orgId = :orgId")
    Optional<Photo> findLocked(@Param("id") UUID id, @Param("orgId") UUID orgId);

    List<Photo> findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(UUID orgId, Collection<Photo.Status> statuses);

    long countByOrgIdAndStatusIn(UUID orgId, Collection<Photo.Status> statuses);

    long countByOrgIdAndStatusAndCreatedAtAfter(UUID orgId, Photo.Status status, Instant after);

    long countByOrgIdAndCreatedAtAfter(UUID orgId, Instant after);

    @Query("select count(p) from Photo p where p.orgId = :orgId and p.status = 'READY' and p.service is not null and p.hairType is not null")
    long countTaggedReady(@Param("orgId") UUID orgId);

    @Query("select coalesce(max(p.position), -1) from Photo p where p.orgId = :orgId and p.status <> 'DELETED'")
    int maxPosition(@Param("orgId") UUID orgId);

    boolean existsByOrgIdAndSha256AndStatusInAndIdNot(UUID orgId, String sha256, Collection<Photo.Status> statuses, UUID id);

    Optional<Photo> findByIdAndStatus(UUID id, Photo.Status status);

    @Query("""
            select p from Photo p, StylistProfile s
            where s.orgId = p.orgId and s.status = 'LIVE' and s.market = :market
              and p.status = 'READY' and p.service = :service and p.hairType is not null
              and p.hairType in :hairTypes
            order by p.orgId, p.position, p.createdAt""")
    List<Photo> findMatchable(@Param("market") String market, @Param("service") ServiceType service,
                              @Param("hairTypes") Collection<HairType> hairTypes);
}
