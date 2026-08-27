package com.resumethinking.platform.profiles;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.time.Instant; import java.util.*;
@RestController @RequestMapping("/api/v1/llm-profiles")
public class LlmProfileController {
 private final LlmProfileService service; public LlmProfileController(LlmProfileService service){this.service=service;}
 @GetMapping public List<Response> list(@RequestAttribute("actorId") UUID actor){return service.list(actor).stream().map(Response::from).toList();}
 @PostMapping public ResponseEntity<Response> create(@RequestAttribute("actorId") UUID actor,@RequestBody CreateLlmProfileCommand command){return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(service.create(actor,command)));}
 @GetMapping("/{id}") public Response get(@RequestAttribute("actorId") UUID actor,@PathVariable UUID id){return Response.from(service.get(actor,id));}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@RequestAttribute("actorId") UUID actor,@PathVariable UUID id){service.delete(actor,id);}
 @PostMapping("/{id}/test") public LlmProfileTestResponse test(@RequestAttribute("actorId") UUID actor,@PathVariable UUID id){return service.testConnection(actor,id);}
 public record Response(UUID id,String displayName,String endpointUrl,String modelName,boolean hasApiKey,boolean selected,String lastTestStatus,Instant lastTestedAt,Instant createdAt,Instant updatedAt){static Response from(LlmProfile p){return new Response(p.getId(),p.getDisplayName(),p.getBaseUrl(),p.getModel(),p.hasApiKey(),p.isSelected(),null,null,p.getCreatedAt(),p.getUpdatedAt());}}
}
