package com.resumethinking.platform.auth;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.bind.annotation.RequestAttribute; import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.time.Instant; import java.util.UUID;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 private final AuthService service; public AuthController(AuthService service){this.service=service;}
 @PostMapping("/register") public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(response(service.register(new RegisterCommand(request.username(),request.email(),request.password(),request.role()))));}
 @PostMapping("/login") public AuthResponse login(@Valid @RequestBody LoginRequest request){return response(service.login(new LoginCommand(request.identifier(),request.password())));}
 @GetMapping("/me") public UserResponse me(@RequestAttribute("actorId") String actor){return UserResponse.from(service.current(actor));}
 private AuthResponse response(AuthResult r){return new AuthResponse(r.accessToken(),r.tokenType(),r.expiresIn(),new UserResponse(r.id(),r.username(),r.email(),r.role(),r.createdAt()));}
 @JsonIgnoreProperties(ignoreUnknown = false) public record RegisterRequest(@NotBlank @Size(min=3,max=64) @Pattern(regexp="^[A-Za-z0-9._-]+$") String username,@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(min=12,max=128) String password,@NotNull UserRole role){} @JsonIgnoreProperties(ignoreUnknown = false) public record LoginRequest(@NotBlank @Size(max=254) String identifier,@NotBlank @Size(max=128) String password){} public record UserResponse(String id,String username,String email,UserRole role,Instant createdAt){static UserResponse from(User u){return new UserResponse(u.getId(),u.getUsername(),u.getEmail(),u.getRole(),u.getCreatedAt());}} public record AuthResponse(String accessToken,String tokenType,long expiresIn,UserResponse user){}
}
