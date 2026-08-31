package com.resumethinking.platform.matching;
import jakarta.persistence.*;
@Entity @Table(name="analysis_evidence")
public class AnalysisEvidence {
 @Id @Column(nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String id;
 @Column(name="task_id",nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String taskId;
 @Column(name="source_location",nullable=false,length=500) private String sourceLocation;
 @Column(name="source_type",nullable=false,length=8) private String sourceType;
 @Column(name="source_start",nullable=false) private int sourceStart;
 @Column(name="source_end",nullable=false) private int sourceEnd;
 @Column(name="source_excerpt",columnDefinition="TEXT") private String sourceExcerpt;
 protected AnalysisEvidence() {}
 public AnalysisEvidence(String id,String taskId,String location,int start,int end){this(id,taskId,location.startsWith("paragraph:")?"DOCX":"TXT",location,start,end,null);} public AnalysisEvidence(String id,String taskId,String sourceType,String location,int start,int end){this(id,taskId,sourceType,location,start,end,null);} public AnalysisEvidence(String id,String taskId,String sourceType,String location,int start,int end,String excerpt){this.id=id;this.taskId=taskId;this.sourceType=sourceType;this.sourceLocation=location;this.sourceStart=start;this.sourceEnd=end;this.sourceExcerpt=excerpt;}
 public String getId(){return id;} public String getTaskId(){return taskId;} public String getSourceType(){return sourceType;} public String getSourceLocation(){return sourceLocation;} public int getSourceStart(){return sourceStart;} public int getSourceEnd(){return sourceEnd;} public String getSourceExcerpt(){return sourceExcerpt;}
}
