package com.resumethinking.platform.profiles;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID; import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity @Table(name="llm_profiles")
public class LlmProfile {
 @Id @Column(columnDefinition="BINARY(16)") private UUID id; @Column(name="owner_id", nullable=false, columnDefinition="BINARY(16)") private UUID ownerId;
 @Column(name="display_name",nullable=false) private String displayName; @Column(name="endpoint_url",nullable=false,length=2048) private String baseUrl; @Column(name="model_name",nullable=false) private String model;
 @Column(name="api_key_ciphertext",nullable=false) private byte[] ciphertext; @Column(name="api_key_nonce",nullable=false,length=12) private byte[] nonce; @Column(name="key_version",nullable=false) private int keyVersion;
 private boolean selected; @Column(name="last_test_status") private String lastTestStatus; @Column(name="last_tested_at") private Instant lastTestedAt; @Column(name="created_at") private Instant createdAt; @Column(name="updated_at") private Instant updatedAt;
 protected LlmProfile() {}
 public LlmProfile(UUID ownerId,String displayName,String baseUrl,String model,byte[] ciphertext,byte[] nonce,boolean selected){this.id=UUID.randomUUID();this.ownerId=ownerId;this.displayName=displayName;this.baseUrl=baseUrl;this.model=model;this.ciphertext=ciphertext;this.nonce=nonce;this.keyVersion=1;this.selected=selected;this.createdAt=Instant.now();this.updatedAt=this.createdAt;}
 public UUID getId(){return id;} public UUID id(){return id;} @JsonIgnore public UUID getOwnerId(){return ownerId;} public String getDisplayName(){return displayName;} public String getBaseUrl(){return baseUrl;} public String getModel(){return model;} @JsonIgnore public byte[] getCiphertext(){return ciphertext;} @JsonIgnore public byte[] getNonce(){return nonce;} @JsonIgnore public int getKeyVersion(){return keyVersion;} public boolean isSelected(){return selected;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public boolean hasApiKey(){return ciphertext!=null&&ciphertext.length>0;}
 public String getLastTestStatus(){return lastTestStatus;} public Instant getLastTestedAt(){return lastTestedAt;}
 public void update(String displayName,String baseUrl,String model,byte[] ciphertext,byte[] nonce,boolean selected){this.displayName=displayName;this.baseUrl=baseUrl;this.model=model;this.ciphertext=ciphertext;this.nonce=nonce;this.selected=selected;this.updatedAt=Instant.now();}
 public void markTest(String status, Instant testedAt){this.lastTestStatus=status;this.lastTestedAt=testedAt;this.updatedAt=testedAt;}
}
