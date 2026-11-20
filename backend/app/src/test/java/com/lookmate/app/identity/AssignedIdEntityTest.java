package com.lookmate.app.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Saving a new entity always inserts: no SELECT first, and a reused id can't overwrite a row. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AssignedIdEntityTest {

    @Autowired
    private OrgRepository orgs;

    @Test
    void everyEntityKnowsWhenItIsNew() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        var entities = scanner.findCandidateComponents("com.lookmate.app");
        // Guards against a scan that silently finds nothing; raise it as entities are added.
        assertThat(entities).hasSizeGreaterThanOrEqualTo(5);
        for (var entity : entities) {
            assertThat(AssignedIdEntity.class.isAssignableFrom(Class.forName(entity.getBeanClassName())))
                    .as(entity.getBeanClassName()).isTrue();
        }
    }

    @Test
    void aReusedIdFailsInsteadOfOverwritingTheRow() {
        Org first = orgs.save(new Org("First", OrgType.STYLIST));
        assertThat(first.isNew()).isFalse();

        Org second = new Org("Second", OrgType.STYLIST);
        ReflectionTestUtils.setField(second, "id", first.getId());

        assertThatThrownBy(() -> orgs.save(second)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orgs.findById(first.getId()).orElseThrow().getName()).isEqualTo("First");
    }
}
