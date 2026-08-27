package com.resumethinking.platform.matching;
import java.time.Instant; import java.util.*;
public record MatchResultResponse(UUID taskId, UUID resumeId, long resumeVersion, String jobDescriptionText, Score score, List<Requirement> requirements, List<Suggestion> suggestions, Instant completedAt) {
 private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER=new com.fasterxml.jackson.databind.ObjectMapper();
 public record Score(double skills,double projectExperience,double workContent,double educationExperience,double softSkills,double composite){}
 public record Requirement(UUID requirementId,String requirementText,String requirementType,String matchStatus,String matchType,String component,double componentScore,List<Evidence> evidence,String gap,String suggestionState){}
 public record Evidence(UUID id,String sourceType,String sourceLocation,String excerpt,double confidence,String strength){}
 public record Suggestion(UUID id,UUID requirementId,String state,String proposedText,List<UUID> evidenceIds,boolean requiresUserConfirmation){}
 static MatchResultResponse from(AnalysisResult r){
   var s=r.score() instanceof AnalysisCallbackRequest.ScoreBreakdown x?x:MAPPER.convertValue(r.score(),AnalysisCallbackRequest.ScoreBreakdown.class); var score=s==null?new Score(0,0,0,0,0,0):new Score(s.skills(),s.projectExperience(),s.workContent(),s.educationExperience(),s.softSkills(),s.composite());
   var req=r.requirements().stream().map(x->{var q=x instanceof AnalysisCallbackRequest.RequirementMatch y?y:MAPPER.convertValue(x,AnalysisCallbackRequest.RequirementMatch.class); var ev=q.evidence().stream().map(e->new Evidence(e.evidenceId(),"TXT","txt:0",e.excerpt(),e.confidence(),q.evidenceStrength())).toList(); return new Requirement(q.requirementId(),q.jobRequirementText(),q.requirementType(),q.matchStatus(),q.matchType(),q.component(),q.componentScore(),ev,q.gap(),q.suggestionState());}).toList();
   var sug=r.suggestions().stream().map(x->{var q=x instanceof AnalysisCallbackRequest.Suggestion y?y:MAPPER.convertValue(x,AnalysisCallbackRequest.Suggestion.class); return new Suggestion(q.suggestionId(),q.requirementId(),q.state(),q.proposedText(),q.evidenceIds(),q.state().equals("NEEDS_USER_CONFIRMATION"));}).toList();
   return new MatchResultResponse(r.taskId(),r.resumeId(),r.resumeVersion(),r.jobDescriptionText(),score,req,sug,r.completedAt());
 }
}
