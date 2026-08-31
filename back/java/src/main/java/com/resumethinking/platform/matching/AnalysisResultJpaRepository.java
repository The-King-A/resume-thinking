package com.resumethinking.platform.matching;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
interface AnalysisResultJpaRepository extends JpaRepository<AnalysisResultEntity,String>{Optional<AnalysisResultEntity> findByTaskId(String taskId);}
