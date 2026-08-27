package com.resumethinking.platform.auth;

import com.resumethinking.platform.profiles.LlmProfile;
import com.resumethinking.platform.profiles.LlmProfileRepository;
import java.util.*;

public final class InMemoryRepositories {
 static class UserRepositoryStub implements UserRepository {
  private final Map<UUID,User> values=new HashMap<>();
  public User save(User u){values.put(u.getId(),u);return u;}
  public Optional<User> findById(UUID id){return Optional.ofNullable(values.get(id));}
  public Optional<User> findByUsername(String n){return values.values().stream().filter(u->u.getUsername().equals(n)).findFirst();}
  public Optional<User> findByEmail(String e){return values.values().stream().filter(u->u.getEmail().equals(e)).findFirst();}
 }
 public static class LlmProfileRepositoryStub implements LlmProfileRepository {
  private final Map<UUID,LlmProfile> values=new HashMap<>();
  public LlmProfile save(LlmProfile p){values.put(p.getId(),p);return p;}
  public Optional<LlmProfile> findByIdAndOwnerId(UUID id,UUID owner){return Optional.ofNullable(values.get(id)).filter(p->p.getOwnerId().equals(owner));}
  public List<LlmProfile> findAllByOwnerId(UUID owner){return values.values().stream().filter(p->p.getOwnerId().equals(owner)).toList();}
  public void delete(LlmProfile p){values.remove(p.getId());}
 }
}
