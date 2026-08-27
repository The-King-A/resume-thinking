package com.resumethinking.platform.profiles;
import jakarta.validation.constraints.*;
public record CreateLlmProfileCommand(@NotBlank @Size(max=100) String displayName,@NotBlank @Size(max=2048) String endpointUrl,@NotBlank @Size(max=200) String modelName,@NotBlank @Size(max=4096) String apiKey,boolean selected) {
 public CreateLlmProfileCommand(String displayName,String endpointUrl,String modelName,String apiKey){this(displayName,endpointUrl,modelName,apiKey,false);}
}
