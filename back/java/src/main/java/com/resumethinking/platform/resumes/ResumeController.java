package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant; import java.util.*;

@RestController @RequestMapping("/api/v1")
public class ResumeController {
 private final ResumeLifecycleService service; public ResumeController(ResumeLifecycleService service){this.service=service;}
 @GetMapping("/resumes") public Page list(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){var items=service.listActive(actorId,role).stream().map(Response::from).toList(); return Page.of(items,page,pageSize);}
 @GetMapping("/resumes/{id}") public Response get(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@PathVariable UUID id){return Response.from(service.getActive(id,actorId,role));}
 @DeleteMapping("/resumes/{id}") public Response delete(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@PathVariable UUID id,@Valid @RequestBody DeleteRequest body){return Response.from(service.softDelete(new DeleteResumeCommand(id,actorId,role,body.confirmationText(),body.expectedVersion())));}
 @PostMapping("/recovery/resumes/{id}/restore") public Response restore(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@PathVariable UUID id,@Valid @RequestBody RestoreRequest body){if(role!=UserRole.USER) throw new org.springframework.security.access.AccessDeniedException("FORBIDDEN"); return Response.from(service.recover(id,actorId,role,body.expectedVersion()));}
 @PostMapping("/admin/recovery/resumes/{id}/restore") public Response adminRestore(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@PathVariable UUID id,@Valid @RequestBody RestoreRequest body){if(role!=UserRole.ADMIN) throw new org.springframework.security.access.AccessDeniedException("FORBIDDEN"); return Response.from(service.recover(id,actorId,role,body.expectedVersion()));}
 @DeleteMapping("/admin/recovery/resumes/{id}") public Response adminDelete(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@PathVariable UUID id,@Valid @RequestBody DeleteRequest body){if(role!=UserRole.ADMIN) throw new org.springframework.security.access.AccessDeniedException("FORBIDDEN"); return Response.from(service.softDelete(new DeleteResumeCommand(id,actorId,role,body.confirmationText(),body.expectedVersion())));}
 @GetMapping("/recovery/resumes") public Page recovery(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){if(role!=UserRole.USER) throw new org.springframework.security.access.AccessDeniedException("FORBIDDEN"); return Page.of(service.listRecoverable(actorId,role).stream().map(Response::from).toList(),page,pageSize);}
 @GetMapping("/admin/recovery/resumes") public Page adminRecovery(@RequestAttribute("actorId") UUID actorId,@RequestAttribute("role") UserRole role,@RequestParam(required=false) UUID ownerId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){if(role!=UserRole.ADMIN) throw new org.springframework.security.access.AccessDeniedException("FORBIDDEN"); return Page.of(service.listRecoverable(actorId,role,ownerId).stream().map(Response::from).toList(),page,pageSize);}
 public record DeleteRequest(@NotBlank String confirmationText,@Min(0) long expectedVersion){} public record RestoreRequest(@Min(0) long expectedVersion){}
 public record Page(List<Response> items,int page,int pageSize,long totalItems,int totalPages){static Page of(List<Response> all,int page,int size){int p=Math.max(1,page),s=Math.max(1,Math.min(100,size)); int from=Math.min(all.size(),(p-1)*s),to=Math.min(all.size(),from+s); return new Page(all.subList(from,to),p,s,all.size(),(all.size()+s-1)/s);}}
 public record Response(UUID id,UUID ownerId,String title,Resume.SourceType sourceType,int status,VisibilityState visibilityState,long version,Instant visibleUntil,Instant softDeletedAt,Instant archivedAt,Instant restoredAt,Instant createdAt,Instant updatedAt){static Response from(Resume r){return new Response(r.getId(),r.getOwnerId(),r.getTitle(),r.getSourceType(),r.getStatus(),r.getVisibilityState(),r.getVersion(),r.getVisibleUntil(),r.getSoftDeletedAt(),r.getArchivedAt(),r.getRestoredAt(),r.getCreatedAt(),r.getUpdatedAt());} static Response from(ResumeView r){return new Response(r.id(),r.ownerId(),r.title(),r.sourceType(),r.status(),r.visibilityState(),r.version(),r.visibleUntil(),r.softDeletedAt(),r.archivedAt(),r.restoredAt(),r.createdAt(),r.updatedAt());}}
}
