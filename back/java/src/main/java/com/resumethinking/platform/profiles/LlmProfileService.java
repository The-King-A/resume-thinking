package com.resumethinking.platform.profiles;

import com.resumethinking.platform.crypto.AesGcmCryptoService; import org.springframework.stereotype.Service; import org.springframework.beans.factory.annotation.Value; import java.net.*; import java.net.http.*; import java.time.*; import java.security.*; import java.util.*; import java.util.regex.*;

@Service public class LlmProfileService {
 private final LlmProfileRepository repository; private final AesGcmCryptoService crypto; private final boolean allowLocal;
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto,@Value("${app.allow-local-model-endpoints:false}") boolean allowLocal){this.repository=repository;this.crypto=crypto;this.allowLocal=allowLocal;}
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto){this(repository,crypto,false);}
 public LlmProfile create(UUID owner,CreateLlmProfileCommand command){
  if(command==null||command.apiKey()==null||command.apiKey().isBlank()) throw new IllegalArgumentException("VALIDATION_ERROR");
  URI uri=validate(command.endpointUrl()); var encrypted=crypto.encrypt(command.apiKey());
  return repository.save(new LlmProfile(owner,command.displayName(),uri.toString(),command.modelName(),encrypted.ciphertext(),encrypted.nonce(),command.selected()));
 }
 public LlmProfile update(UUID owner,UUID id,UpdateLlmProfileCommand command){
  if(command==null) throw new IllegalArgumentException("VALIDATION_ERROR");
  LlmProfile profile=get(owner,id); URI uri=validate(command.endpointUrl());
  byte[] ciphertext=profile.getCiphertext(); byte[] nonce=profile.getNonce();
  if(command.apiKey()!=null){
   if(command.apiKey().isBlank()) throw new IllegalArgumentException("VALIDATION_ERROR");
   var encrypted=crypto.encrypt(command.apiKey()); ciphertext=encrypted.ciphertext(); nonce=encrypted.nonce();
  }
  profile.update(command.displayName(),uri.toString(),command.modelName(),ciphertext,nonce,command.selected());
  return repository.save(profile);
 }
 /** Compatibility overload for domain callers compiled against the create DTO. */
 public LlmProfile update(UUID owner,UUID id,CreateLlmProfileCommand command){
  return update(owner,id,new UpdateLlmProfileCommand(command.displayName(),command.endpointUrl(),command.modelName(),command.apiKey(),command.selected()));
 }
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
 private URI validate(String raw,boolean requestTime){
  try {
   if(raw==null||raw.isBlank()||raw.length()>2048||hasUnsafeCharacters(raw)) throw rejected();
   URI uri=URI.create(raw); String scheme=uri.getScheme(); String host=uri.getHost();
   if(host!=null&&host.startsWith("[")&&host.endsWith("]")) host=host.substring(1,host.length()-1);
   int port=uri.getPort();
   if(scheme==null||host==null||uri.getRawAuthority()==null||uri.getRawAuthority().contains("@")||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawAuthority().contains("\\")||port==0||port < -1||port > 65535) throw rejected();
   host=host.toLowerCase(Locale.ROOT);
   if(host.endsWith(".")) throw rejected();
   String path=uri.getRawPath(); if(path==null||path.isEmpty()) path="/";
   validatePath(path);
   boolean local=allowLocal&&"http".equalsIgnoreCase(scheme)&&"127.0.0.1".equals(host);
   if(!"https".equalsIgnoreCase(scheme)&&!local) throw rejected();
   try {
    InetAddress[] addresses=InetAddress.getAllByName(host);
    if(addresses.length==0) throw rejected();
    for(InetAddress address:addresses) if(!local&&unsafeAddress(address)) throw rejected();
   } catch(UnknownHostException e) { if(requestTime) throw rejected(); }
   return uri;
  } catch(IllegalArgumentException e) { throw new IllegalArgumentException("MODEL_ENDPOINT_REJECTED",e); }
 }
 private static boolean hasUnsafeCharacters(String value){ for(int i=0;i<value.length();i++){char c=value.charAt(i); if(Character.isWhitespace(c)||c<0x20||c==0x7f) return true;} return false; }
 private static void validatePath(String rawPath){
  if(rawPath.indexOf('\\')>=0) throw rejected();
  final String decoded;
  try { decoded=java.net.URLDecoder.decode(rawPath,java.nio.charset.StandardCharsets.UTF_8); } catch(IllegalArgumentException e){ throw rejected(); }
  if(decoded.indexOf('\u0000')>=0) throw rejected();
  for(String segment:decoded.split("/",-1)) if(segment.equals(".")||segment.equals("..")) throw rejected();
 }
 private boolean unsafeAddress(InetAddress address){
  if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()) return true;
  byte[] bytes=address.getAddress();
  if(bytes.length==4){
   long value=((bytes[0]&255L)<<24)|((bytes[1]&255L)<<16)|((bytes[2]&255L)<<8)|(bytes[3]&255L);
   return inRange(value,0x00000000L,8)||inRange(value,0x64400000L,10)||inRange(value,0x7F000000L,8)||inRange(value,0xA9FE0000L,16)||inRange(value,0xC0000000L,24)||inRange(value,0xC0000200L,24)||inRange(value,0xC0586300L,24)||inRange(value,0xC0A80000L,16)||inRange(value,0xC6120000L,15)||inRange(value,0xC6336400L,24)||inRange(value,0xCB007100L,24)||inRange(value,0xE0000000L,4)||inRange(value,0xF0000000L,4);
  }
  // IPv4-mapped IPv6 addresses must be subjected to the IPv4 policy too.
  if(isIpv4Mapped(bytes)){ byte[] mapped=java.util.Arrays.copyOfRange(bytes,12,16); try { return unsafeAddress(InetAddress.getByAddress(mapped)); } catch (UnknownHostException impossible) { return true; } }
  // Unique-local and documentation IPv6 ranges are not public provider targets.
  return (bytes[0]&0xFE)==0xFC || (bytes[0]&255)==0x20&&bytes[1]==1&&bytes[2]==13&&bytes[3]==(-72);
 }
 private static boolean isIpv4Mapped(byte[] bytes){ if(bytes.length!=16) return false; for(int i=0;i<10;i++) if(bytes[i]!=0) return false; return bytes[10]==(byte)0xff&&bytes[11]==(byte)0xff; }
 private static long mask(int prefix){return prefix==0?0:0xFFFFFFFFL<<(32-prefix);}
 private static boolean inRange(long value,long network,int prefix){return (value&mask(prefix))==(network&mask(prefix));}
 private static IllegalArgumentException rejected(){return new IllegalArgumentException("MODEL_ENDPOINT_REJECTED");}
 private URI modelsUri(URI base){String path=base.getRawPath(); if(path==null||path.isEmpty())path="/"; if(!path.endsWith("/"))path+="/"; return URI.create(base.getScheme()+"://"+base.getRawAuthority()+path+"models");}
}
