package com.resumethinking.platform.config;

import com.resumethinking.platform.matching.AnalysisEvidence;
import com.resumethinking.platform.matching.AnalysisEvidenceRepository;
import com.resumethinking.platform.resumes.Resume;
import com.resumethinking.platform.resumes.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.RepositoryDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryBeanMetadataTest {
    @Test
    void lifecycleRepositoryInterfacesDeclareTheirSpringDataDomainTypes() {
        var resumeDefinition = ResumeRepository.class.getAnnotation(RepositoryDefinition.class);
        var evidenceDefinition = AnalysisEvidenceRepository.class.getAnnotation(RepositoryDefinition.class);

        assertThat(resumeDefinition).isNotNull();
        assertThat(resumeDefinition.domainClass()).isEqualTo(Resume.class);
        assertThat(resumeDefinition.idClass()).isEqualTo(String.class);
        assertThat(evidenceDefinition).isNotNull();
        assertThat(evidenceDefinition.domainClass()).isEqualTo(AnalysisEvidence.class);
        assertThat(evidenceDefinition.idClass()).isEqualTo(String.class);
    }
}
