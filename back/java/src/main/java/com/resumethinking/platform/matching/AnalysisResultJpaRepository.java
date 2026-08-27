package com.resumethinking.platform.matching;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
interface AnalysisResultJpaRepository extends JpaRepository<AnalysisResultEntity,Long>{Optional<AnalysisResultEntity> findByTaskId(UUID taskId);}
