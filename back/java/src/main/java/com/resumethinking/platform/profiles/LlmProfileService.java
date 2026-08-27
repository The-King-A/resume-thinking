package com.resumethinking.platform.profiles;

import com.resumethinking.platform.crypto.AesGcmCryptoService; import org.springframework.stereotype.Service; import org.springframework.beans.factory.annotation.Value; import java.net.*; import java.net.http.*; import java.time.Duration; import java.security.*; import java.util.*; import java.util.regex.*;

@Service public class LlmProfileService {
 private final LlmProfileRepository repository; private final AesGcmCryptoService crypto; private final boolean allowLocal;
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto,@Value("${app.allow-local-model-endpoints:false}") boolean allowLocal){this.repository=repository;this.crypto=crypto;this.allowLocal=allowLocal;}
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto){this(repository,crypto,false);}
 public LlmProfile create(UUID owner,CreateLlmProfileCommand command){URI uri=validate(command.endpointUrl()); var encrypted=crypto.encrypt(command.apiKey()); return repository.save(new LlmProfile(owner,command.displayName(),uri.toString(),command.modelName(),encrypted.ciphertext(),encrypted.nonce(),command.selected()));}
 public List<LlmProfile> list(UUID owner){return repository.findAllByOwnerId(owner);}
 public LlmProfile get(UUID owner,UUID id){return repository.findByIdAndOwnerId(id,owner).orElseThrow(ResourceNotFoundException::new);}
 public DispatchLlmProfile decryptForDispatch(UUID actorId,UUID profileId){LlmProfile profile=get(actorId,profileId); return new DispatchLlmProfile(URI.create(profile.getBaseUrl()),profile.getModel(),crypto.decrypt(profile.getCiphertext(),profile.getNonce()));}
 public void delete(UUID owner,UUID id){repository.delete(get(owner,id));}
 public LlmProfileTestResponse testConnection(UUID owner, UUID id) {
  LlmProfile profile=get(owner,id); URI base=validate(profile.getBaseUrl());
  try { HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   HttpRequest request=HttpRequest.newBuilder(base.resolve("models")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+crypto.decrypt(profile.getCiphertext(),profile.getNonce())).GET().build();
   HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2) return new LlmProfileTestResponse(false,java.time.Instant.now(),List.of(),"Provider returned HTTP "+response.statusCode());
   Matcher matcher=Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]{1,200})\\\"").matcher(response.body()); List<String> models=new ArrayList<>(); while(matcher.find()&&models.size()<100) models.add(matcher.group(1)); return new LlmProfileTestResponse(true,java.time.Instant.now(),models,null);
  } catch(Exception e){ return new LlmProfileTestResponse(false,java.time.Instant.now(),List.of(),"Provider unavailable"); }
 }
 private URI validate(String raw){try{URI uri=URI.create(raw); if(!"https".equalsIgnoreCase(uri.getScheme()) && !(allowLocal && "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()))) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); if(uri.getHost()==null) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); try { for(InetAddress address:InetAddress.getAllByName(uri.getHost())) if(!allowLocal || !address.isLoopbackAddress() || !"127.0.0.1".equals(uri.getHost())) if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); } catch (UnknownHostException ignored) { /* Resolution is repeated immediately before network dispatch. */ } return uri;}catch(IllegalArgumentException e){throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED",e);}}
}
