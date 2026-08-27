package com.resumethinking.platform.matching;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="analysis_evidence")
public class AnalysisEvidence {
 @Id @Column(columnDefinition="BINARY(16)") private UUID id;
 @Column(name="task_id",nullable=false,columnDefinition="BINARY(16)") private UUID taskId;
 @Column(name="source_location",nullable=false,length=500) private String sourceLocation;
 @Column(name="source_type",nullable=false,length=8) private String sourceType;
 @Column(name="source_start",nullable=false) private int sourceStart;
 @Column(name="source_end",nullable=false) private int sourceEnd;
 @Column(name="source_excerpt",columnDefinition="TEXT") private String sourceExcerpt;
 protected AnalysisEvidence() {}
 public AnalysisEvidence(UUID id,UUID taskId,String location,int start,int end){this(id,taskId,location.startsWith("paragraph:")?"DOCX":"TXT",location,start,end,null);} public AnalysisEvidence(UUID id,UUID taskId,String sourceType,String location,int start,int end){this(id,taskId,sourceType,location,start,end,null);} public AnalysisEvidence(UUID id,UUID taskId,String sourceType,String location,int start,int end,String excerpt){this.id=id;this.taskId=taskId;this.sourceType=sourceType;this.sourceLocation=location;this.sourceStart=start;this.sourceEnd=end;this.sourceExcerpt=excerpt;}
 public UUID getId(){return id;} public UUID getTaskId(){return taskId;} public String getSourceType(){return sourceType;} public String getSourceLocation(){return sourceLocation;} public int getSourceStart(){return sourceStart;} public int getSourceEnd(){return sourceEnd;} public String getSourceExcerpt(){return sourceExcerpt;}
}
