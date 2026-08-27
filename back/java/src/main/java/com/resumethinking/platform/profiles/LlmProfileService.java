package com.resumethinking.platform.profiles;

import com.resumethinking.platform.crypto.AesGcmCryptoService; import org.springframework.stereotype.Service; import org.springframework.beans.factory.annotation.Value; import java.net.*; import java.net.http.*; import java.time.*; import java.security.*; import java.util.*; import java.util.regex.*;

@Service public class LlmProfileService {
 private final LlmProfileRepository repository; private final AesGcmCryptoService crypto; private final boolean allowLocal;
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto,@Value("${app.allow-local-model-endpoints:false}") boolean allowLocal){this.repository=repository;this.crypto=crypto;this.allowLocal=allowLocal;}
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto){this(repository,crypto,false);}
 public LlmProfile create(UUID owner,CreateLlmProfileCommand command){URI uri=validate(command.endpointUrl()); var encrypted=crypto.encrypt(command.apiKey()); return repository.save(new LlmProfile(owner,command.displayName(),uri.toString(),command.modelName(),encrypted.ciphertext(),encrypted.nonce(),command.selected()));}
 public LlmProfile update(UUID owner,UUID id,CreateLlmProfileCommand command){LlmProfile profile=get(owner,id); URI uri=validate(command.endpointUrl()); var encrypted=crypto.encrypt(command.apiKey()); profile.update(command.displayName(),uri.toString(),command.modelName(),encrypted.ciphertext(),encrypted.nonce(),command.selected()); return repository.save(profile);}
 public List<LlmProfile> list(UUID owner){return repository.findAllByOwnerId(owner);}
 public LlmProfile get(UUID owner,UUID id){return repository.findByIdAndOwnerId(id,owner).orElseThrow(ResourceNotFoundException::new);}
 public DispatchLlmProfile decryptForDispatch(UUID actorId,UUID profileId){LlmProfile profile=get(actorId,profileId); URI base=validate(profile.getBaseUrl(),true); return new DispatchLlmProfile(base,profile.getModel(),crypto.decrypt(profile.getCiphertext(),profile.getNonce()));}
 public void delete(UUID owner,UUID id){repository.delete(get(owner,id));}
 public LlmProfileTestResponse testConnection(UUID owner, UUID id) {
  LlmProfile profile=get(owner,id); URI base=validate(profile.getBaseUrl(), true); Instant tested=java.time.Instant.now();
  try { HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   HttpRequest request=HttpRequest.newBuilder(modelsUri(base)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+crypto.decrypt(profile.getCiphertext(),profile.getNonce())).GET().build();
   HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2){profile.markTest("FAILED",tested);repository.save(profile);return new LlmProfileTestResponse(false,tested,List.of(),"Provider returned HTTP "+response.statusCode());}
   Matcher matcher=Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]{1,200})\\\"").matcher(response.body()); List<String> models=new ArrayList<>(); while(matcher.find()&&models.size()<100) models.add(matcher.group(1)); profile.markTest("SUCCEEDED",tested);repository.save(profile); return new LlmProfileTestResponse(true,tested,models,null);
  } catch(Exception e){ profile.markTest("FAILED",tested);repository.save(profile); return new LlmProfileTestResponse(false,tested,List.of(),"Provider unavailable"); }
 }
 private URI validate(String raw){return validate(raw,false);}
 private URI validate(String raw,boolean requestTime){try{URI uri=URI.create(raw); String host=uri.getHost(); if(host!=null&&host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1); boolean local=allowLocal&&"http".equalsIgnoreCase(uri.getScheme())&&"127.0.0.1".equals(host); if(!"https".equalsIgnoreCase(uri.getScheme())&&!local) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); if(host==null) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); try { for(InetAddress address:InetAddress.getAllByName(host)) if(!local&&unsafeAddress(address)) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED"); } catch (UnknownHostException e) { if(requestTime) throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED",e); } return uri;}catch(IllegalArgumentException e){throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED",e);}}
 private boolean unsafeAddress(InetAddress address){if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return true; byte[] bytes=address.getAddress(); if(bytes.length==4){long value=((bytes[0]&255L)<<24)|((bytes[1]&255L)<<16)|((bytes[2]&255L)<<8)|(bytes[3]&255L); return inRange(value,0x00000000L,8)||inRange(value,0x64400000L,10)||inRange(value,0xC0000000L,24)||inRange(value,0xC0000200L,24)||inRange(value,0xC6120000L,15)||inRange(value,0xC6336400L,24)||inRange(value,0xCB007100L,24)||inRange(value,0xF0000000L,4);} return (bytes[0]&0xFE)==0xFC||(bytes[0]&255)==0x20&&bytes[1]==1&&bytes[2]==13&&bytes[3]==(-72);}
 private boolean inRange(long value,long network,int prefix){long mask=prefix==0?0:0xFFFFFFFFL<<(32-prefix); return (value&mask)==network;}
 private URI modelsUri(URI base){String path=base.getRawPath(); if(path==null||path.isEmpty())path="/"; if(!path.endsWith("/"))path+="/"; return URI.create(base.getScheme()+"://"+base.getRawAuthority()+path+"models");}
}
