package com.lookmate.app.stylists;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public stylist directory: every LIVE stylist in a market, for clients to browse and pick from whatever they were
 * recommended (CLAUDE.md: recommendations are suggestions). Only what a client may see: no email, booking link or
 * internal id but the org id; photos are short-lived signed links, never public URLs.
 */
@Service
class StylistDirectory {

    static final int PAGE_SIZE = 12;
    static final int PHOTOS_PER_STYLIST = 3;

    /** {@code demo}: a made-up stylist (proof of concept), labelled "Sample stylist" wherever clients see it. */
    record DirectoryStylist(UUID orgId, String displayName, String locationName, String city, List<ServiceType> specialties,
                            List<String> photoUrls, boolean demo, String instagram) {
    }

    record DirectoryPage(String market, ServiceType service, HairType hairType, int page, int pageSize, long total,
                         List<DirectoryStylist> stylists) {
    }

    private record Row(UUID orgId, String displayName, String locationName, String city, List<ServiceType> specialties, boolean demo,
                       String instagram) {
    }

    /**
     * Who matches the filters: a READY photo tagged with the service and the hair type (whichever are given), or, for a
     * service alone, the service among the stylist's specialties.
     */
    private static final String WHERE = """
            from stylist_profile p left join salon_location l on l.id = p.location_id
            where p.status = 'LIVE' and p.market = :market
              and ((cast(:service as text) is null and cast(:hair as text) is null)
                   or exists (select 1 from portfolio_photo ph
                              where ph.org_id = p.org_id and ph.status = 'READY'
                                and (cast(:service as text) is null or ph.service = :service)
                                and (cast(:hair as text) is null or ph.hair_type = :hair))
                   or (cast(:hair as text) is null and cast(:service as text) = any(p.specialties)))
            """;

    private final JdbcClient jdbc;
    private final BlobStore blobs;
    private final StorageProperties storage;

    StylistDirectory(JdbcClient jdbc, BlobStore blobs, StorageProperties storage) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.storage = storage;
    }

    /** One page (from 0) of LIVE stylists, real ones before sample ones, then by name. */
    @Transactional(readOnly = true)
    DirectoryPage page(String market, ServiceType service, HairType hairType, int page) {
        String s = service == null ? null : service.name();
        String h = hairType == null ? null : hairType.name();
        long total = jdbc.sql("select count(*) " + WHERE).param("market", market).param("service", s).param("hair", h)
                .query(Long.class).single();
        List<Row> rows = jdbc.sql("""
                        select p.org_id, p.display_name, l.name as location_name, l.city, p.specialties, p.demo, p.instagram
                        """ + WHERE + """
                        order by p.demo, lower(p.display_name), p.org_id
                        limit :size offset :offset
                        """)
                .param("market", market).param("service", s).param("hair", h)
                .param("size", PAGE_SIZE).param("offset", (long) page * PAGE_SIZE)
                .query((rs, n) -> new Row(rs.getObject("org_id", UUID.class), rs.getString("display_name"), rs.getString("location_name"),
                        rs.getString("city"), services(rs.getArray("specialties")), rs.getBoolean("demo"), rs.getString("instagram")))
                .list();
        Map<UUID, List<String>> photos = photoUrls(rows.stream().map(Row::orgId).toList(), s, h);
        List<DirectoryStylist> stylists = rows.stream()
                .map(r -> new DirectoryStylist(r.orgId(), r.displayName(), r.locationName(), r.city(), r.specialties(),
                        photos.getOrDefault(r.orgId(), List.of()), r.demo(), r.instagram()))
                .toList();
        return new DirectoryPage(market, service, hairType, page, PAGE_SIZE, total, stylists);
    }

    /** A few READY photos per stylist, those matching the filters first, then in the stylist's own order: signed links. */
    private Map<UUID, List<String>> photoUrls(List<UUID> orgIds, String service, String hairType) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (orgIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select org_id, object_key from (
                          select ph.org_id, ph.object_key,
                                 row_number() over (partition by ph.org_id order by
                                   ((cast(:service as text) is null or ph.service = :service)
                                    and (cast(:hair as text) is null or ph.hair_type = :hair)) desc,
                                   ph.position, ph.created_at, ph.id) as n
                          from portfolio_photo ph
                          where ph.org_id in (:orgs) and ph.status = 'READY') x
                        where n <= :per
                        order by org_id, n
                        """)
                .param("orgs", orgIds).param("service", service).param("hair", hairType).param("per", PHOTOS_PER_STYLIST)
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getObject("org_id", UUID.class), k -> new ArrayList<>())
                            .add(blobs.presignDownload(storage.photoBucket(), rs.getString("object_key")).toString());
                    return null;
                })
                .list();
        return out;
    }

    private static List<ServiceType> services(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((String[]) array.getArray()).map(ServiceType::valueOf).toList();
    }
}
