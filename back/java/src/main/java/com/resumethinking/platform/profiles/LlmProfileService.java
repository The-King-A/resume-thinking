package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import com.resumethinking.platform.crypto.AesGcmCryptoService; import org.springframework.stereotype.Service; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.beans.factory.annotation.Value; import org.springframework.transaction.annotation.Transactional; import java.io.*; import java.net.*; import java.net.http.*; import java.nio.charset.StandardCharsets; import java.time.*; import java.security.*; import java.util.*; import java.util.regex.*;

@Service public class LlmProfileService {
 private final LlmProfileRepository repository; private final AesGcmCryptoService crypto; private final boolean allowLocal; private final ReadableIdGenerator ids;
 private final ObjectMapper objectMapper = new ObjectMapper();
 private static final int MAX_PROVIDER_RESPONSE_BYTES = 64 * 1024;
 @Autowired public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto,@Value("${app.allow-local-model-endpoints:false}") boolean allowLocal, ReadableIdGenerator ids){this.repository=repository;this.crypto=crypto;this.allowLocal=allowLocal;this.ids=ids;}
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto){this(repository,crypto,false,new InMemoryReadableIdGenerator());}
 public LlmProfileService(LlmProfileRepository repository,AesGcmCryptoService crypto,boolean allowLocal){this(repository,crypto,allowLocal,new InMemoryReadableIdGenerator());}
 @Transactional public LlmProfile create(String owner,CreateLlmProfileCommand command){
  if(command==null||command.apiKey()==null||command.apiKey().isBlank()) throw new IllegalArgumentException("VALIDATION_ERROR");
  URI uri=validate(command.endpointUrl()); var encrypted=crypto.encrypt(command.apiKey());
  return repository.save(new LlmProfile(ids.next(BusinessIdType.PROFILE),owner,command.displayName(),uri.toString(),command.modelName(),encrypted.ciphertext(),encrypted.nonce(),command.selected()));
 }
 @Transactional public LlmProfile update(String owner,String id,UpdateLlmProfileCommand command){
  if(command==null) throw new IllegalArgumentException("VALIDATION_ERROR");
  LlmProfile profile=get(owner,id); URI uri=validate(command.endpointUrl());
  byte[] ciphertext=profile.getCiphertext(); byte[] nonce=profile.getNonce();
   if(command.apiKey()!=null&&!command.apiKey().isBlank()){
    var encrypted=crypto.encrypt(command.apiKey()); ciphertext=encrypted.ciphertext(); nonce=encrypted.nonce();
   }
  profile.update(command.displayName(),uri.toString(),command.modelName(),ciphertext,nonce,command.selected());
  return repository.save(profile);
 }
 /** Compatibility overload for domain callers compiled against the create DTO. */
 public LlmProfile update(String owner,String id,CreateLlmProfileCommand command){
  return update(owner,id,new UpdateLlmProfileCommand(command.displayName(),command.endpointUrl(),command.modelName(),command.apiKey(),command.selected()));
 }
 public List<LlmProfile> list(String owner){return repository.findAllByOwnerId(owner);}
 public LlmProfile get(String owner,String id){return repository.findByIdAndOwnerId(id,owner).orElseThrow(ResourceNotFoundException::new);}
 public DispatchLlmProfile decryptForDispatch(String actorId,String profileId){LlmProfile profile=get(actorId,profileId); URI base=validate(profile.getBaseUrl(),true); return new DispatchLlmProfile(base,profile.getModel(),crypto.decrypt(profile.getCiphertext(),profile.getNonce()));}
 @Transactional public void delete(String owner,String id){repository.delete(get(owner,id));}
 public LlmProfileTestResponse scanModels(ScanLlmProfileCommand command) {
  if(command==null||command.endpointUrl()==null||command.endpointUrl().isBlank()||command.endpointUrl().length()>2048||command.apiKey()==null||command.apiKey().isBlank()||command.apiKey().length()>4096) throw new IllegalArgumentException("VALIDATION_ERROR");
  URI base=validate(command.endpointUrl(),true); Instant tested=java.time.Instant.now();
  try {
   ProviderResponse response=sendModelsRequest(base,command.apiKey());
   if(response.statusCode()/100!=2) return providerHttpFailure(tested,response.statusCode());
   Optional<List<String>> models=parseModelIds(response.body(),command.apiKey());
   if(models.isEmpty()) return providerResponseInvalid(tested);
   return new LlmProfileTestResponse(true,tested,models.get(),null);
  } catch(ProviderResponseTooLargeException e){ return providerResponseInvalid(tested); }
    catch(Exception e){ return providerUnavailable(tested); }
 }
 public LlmProfileTestResponse testConnection(String owner, String id) {
  LlmProfile profile=get(owner,id); URI base=validate(profile.getBaseUrl(), true); Instant tested=java.time.Instant.now();
  try {
   String apiKey=crypto.decrypt(profile.getCiphertext(),profile.getNonce());
   List<String> models=List.of();
   ProviderResponse response=sendModelsRequest(base,apiKey);
   if(response.statusCode()/100==2){
    Optional<List<String>> scannedModels=parseModelIds(response.body(),apiKey);
    if(scannedModels.isEmpty()){profile.markTest("FAILED",tested);repository.save(profile);return providerResponseInvalid(tested);}
    models=scannedModels.get();
   }
   else if(response.statusCode()!=404){profile.markTest("FAILED",tested);repository.save(profile);return providerHttpFailure(tested,response.statusCode());}
    ProviderResponse probeResponse=sendChatCompletionProbe(base,apiKey,profile.getModel());
    if(probeResponse.statusCode()/100!=2){profile.markTest("FAILED",tested);repository.save(profile);return providerHttpFailure(tested,probeResponse.statusCode());}
    if(!isSuccessfulProbe(probeResponse.body())){
     profile.markTest("FAILED",tested);repository.save(profile);return providerResponseInvalid(tested);
    }
    profile.markTest("SUCCEEDED",tested);repository.save(profile); return new LlmProfileTestResponse(true,tested,models,null);
   } catch(ProviderResponseTooLargeException e){ profile.markTest("FAILED",tested);repository.save(profile); return providerResponseInvalid(tested); }
     catch(Exception e){ profile.markTest("FAILED",tested);repository.save(profile); return providerUnavailable(tested); }
  }
 private static LlmProfileTestResponse providerHttpFailure(Instant tested,int statusCode){
  LlmProfileDiagnosticCode diagnosticCode=switch(statusCode){
   case 401 -> LlmProfileDiagnosticCode.INVALID_API_KEY;
   case 403 -> LlmProfileDiagnosticCode.PROVIDER_FORBIDDEN;
   case 404 -> LlmProfileDiagnosticCode.PROVIDER_ENDPOINT_NOT_FOUND;
   case 429 -> LlmProfileDiagnosticCode.PROVIDER_RATE_LIMITED;
   default -> LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE;
  };
  return new LlmProfileTestResponse(false,tested,List.of(),"Provider returned HTTP "+statusCode,diagnosticCode);
 }
 private static LlmProfileTestResponse providerResponseInvalid(Instant tested){
  return new LlmProfileTestResponse(false,tested,List.of(),"Provider returned invalid response",LlmProfileDiagnosticCode.PROVIDER_RESPONSE_INVALID);
 }
 private static LlmProfileTestResponse providerUnavailable(Instant tested){
  return new LlmProfileTestResponse(false,tested,List.of(),"Provider unavailable",LlmProfileDiagnosticCode.PROVIDER_UNAVAILABLE);
 }
  private Optional<List<String>> parseModelIds(String body,String secret){
  if(body==null||body.isBlank()) return Optional.empty();
  try { JsonNode root=objectMapper.readTree(body); if(root==null||!root.isObject()||!root.path("data").isArray()) return Optional.empty(); List<String> models=new ArrayList<>(); collectModelIds(root.path("data"),secret,models); return Optional.of(List.copyOf(models)); }
  catch(Exception ignored){ return Optional.empty(); }
 }
 private static void collectModelIds(JsonNode node,String secret,List<String> models){
  if(node==null||models.size()>=100) return;
  if(node.isObject()){
   node.fields().forEachRemaining(entry->{
    if(models.size()>=100) return;
    if("id".equals(entry.getKey())&&entry.getValue().isTextual()){
     String value=entry.getValue().textValue();
      if(value.length()<=200&&!containsSecret(value,secret)&&!models.contains(value)) models.add(value);
    }
    collectModelIds(entry.getValue(),secret,models);
   });
  } else if(node.isArray()) node.forEach(child->collectModelIds(child,secret,models));
 }
  private static boolean containsSecret(String value,String secret){
   if(secret==null||secret.isEmpty()) return false;
   if(value.contains(secret)) return true;
   String trimmed=secret.trim();
   return !trimmed.isEmpty()&&!trimmed.equals(secret)&&value.contains(trimmed);
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
 private ProviderResponse sendModelsRequest(URI base,String apiKey) throws IOException, InterruptedException {
  HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  HttpRequest request=HttpRequest.newBuilder(modelsUri(base)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+apiKey).GET().build();
  return sendBoundedResponse(client,request);
 }
 private ProviderResponse sendChatCompletionProbe(URI base,String apiKey,String model) throws IOException, InterruptedException {
  try {
   String payload=objectMapper.writeValueAsString(Map.of(
           "model",model,
           "messages",List.of(Map.of("role","user","content","Reply with exactly OK.")),
           "max_tokens",32));
   HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   HttpRequest request=HttpRequest.newBuilder(chatCompletionsUri(base)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+apiKey).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
   return sendBoundedResponse(client,request);
  } catch(com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
 }
 private static ProviderResponse sendBoundedResponse(HttpClient client,HttpRequest request) throws IOException, InterruptedException {
  HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
  try(InputStream body=response.body()){
   if(response.statusCode()/100!=2) return new ProviderResponse(response.statusCode(),"");
   OptionalLong declaredLength=response.headers().firstValueAsLong("Content-Length");
   if(declaredLength.isPresent()&&declaredLength.getAsLong()>MAX_PROVIDER_RESPONSE_BYTES) throw new ProviderResponseTooLargeException();
   return new ProviderResponse(response.statusCode(),readBoundedUtf8(body));
  }
 }
 private static String readBoundedUtf8(InputStream input) throws IOException {
  ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(MAX_PROVIDER_RESPONSE_BYTES,8192)); byte[] buffer=new byte[4096]; int total=0;
  for(int read;(read=input.read(buffer))!=-1;){
   if(read>MAX_PROVIDER_RESPONSE_BYTES-total) throw new ProviderResponseTooLargeException();
   output.write(buffer,0,read); total+=read;
  }
  return output.toString(StandardCharsets.UTF_8);
 }
 private record ProviderResponse(int statusCode,String body){}
 private static final class ProviderResponseTooLargeException extends IOException {}
 private boolean isSuccessfulProbe(String body){
  if(body==null||body.isBlank()) return false;
  try {
   JsonNode root=objectMapper.readTree(body); JsonNode content=root.path("choices").path(0).path("message").path("content");
   return content.isTextual()&&!content.textValue().isBlank();
  } catch(Exception ignored) { return false; }
 }
 private URI modelsUri(URI base){String path=base.getRawPath(); if(path==null||path.isEmpty())path="/"; if(!path.endsWith("/"))path+="/"; return URI.create(base.getScheme()+"://"+base.getRawAuthority()+path+"models");}
 private URI chatCompletionsUri(URI base){String path=base.getRawPath(); if(path==null||path.isEmpty())path="/"; if(!path.endsWith("/"))path+="/"; return URI.create(base.getScheme()+"://"+base.getRawAuthority()+path+"chat/completions");}
}
