package com.resumethinking.platform.matching;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="analysis_evidence")
public class AnalysisEvidence {
 @Id @Column(columnDefinition="BINARY(16)") private UUID id;
 @Column(name="task_id",nullable=false,columnDefinition="BINARY(16)") private UUID taskId;
 @Column(name="source_location",nullable=false,length=500) private String sourceLocation;
 @Column(name="source_start",nullable=false) private int sourceStart;
 @Column(name="source_end",nullable=false) private int sourceEnd;
 protected AnalysisEvidence() {}
 public AnalysisEvidence(UUID id,UUID taskId,String location,int start,int end){this.id=id;this.taskId=taskId;this.sourceLocation=location;this.sourceStart=start;this.sourceEnd=end;}
 public UUID getId(){return id;} public UUID getTaskId(){return taskId;} public String getSourceLocation(){return sourceLocation;} public int getSourceStart(){return sourceStart;} public int getSourceEnd(){return sourceEnd;}
}
