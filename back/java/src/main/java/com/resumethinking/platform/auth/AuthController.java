package com.resumethinking.platform.auth;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.time.Instant; import java.util.UUID;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 private final AuthService service; public AuthController(AuthService service){this.service=service;}
 @PostMapping("/register") public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(response(service.register(new RegisterCommand(request.username(),request.email(),request.password(),request.role()))));}
 @PostMapping("/login") public AuthResponse login(@RequestBody LoginRequest request){return response(service.login(new LoginCommand(request.identifier(),request.password())));}
 private AuthResponse response(AuthResult r){return new AuthResponse(r.accessToken(),r.tokenType(),r.expiresIn(),new UserResponse(r.id(),r.username(),r.email(),r.role(),r.createdAt()));}
 public record RegisterRequest(String username,String email,String password,UserRole role){} public record LoginRequest(String identifier,String password){} public record UserResponse(UUID id,String username,String email,UserRole role,Instant createdAt){} public record AuthResponse(String accessToken,String tokenType,long expiresIn,UserResponse user){}
}
