package com.resumethinking.platform.profiles;
import java.net.URI;
public record DispatchLlmProfile(URI baseUrl,String model,String apiKey) {
 @Override public String toString(){return "DispatchLlmProfile[baseUrl="+baseUrl+", model="+model+", apiKey=<redacted>]";}
}
