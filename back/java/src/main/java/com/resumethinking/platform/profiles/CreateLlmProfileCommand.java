package com.resumethinking.platform.profiles;
public record CreateLlmProfileCommand(String displayName,String endpointUrl,String modelName,String apiKey,boolean selected) {
 public CreateLlmProfileCommand(String displayName,String endpointUrl,String modelName,String apiKey){this(displayName,endpointUrl,modelName,apiKey,false);}
}
