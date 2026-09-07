package com.resumethinking.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class LocalProfileNetworkBoundaryTest {

 @Test
 void localProfileBindsServerToIpv4Loopback() {
  var yaml = new YamlPropertiesFactoryBean();
  yaml.setResources(new ClassPathResource("application-local.yml"));

  assertThat(yaml.getObject()).containsEntry("server.address", "127.0.0.1");
 }

 @Test
 void localProfileImportsDotEnvFromProjectRootForCommonWorkingDirectories() {
  var yaml = new YamlPropertiesFactoryBean();
  yaml.setResources(new ClassPathResource("application-local.yml"));

  assertThat(yaml.getObject())
          .containsEntry("spring.config.import", "optional:file:.env[.properties],optional:file:../../.env[.properties]");
 }

 @Test
 void applicationUsesLocalProfileByDefaultForTheMvp() {
  var yaml = new YamlPropertiesFactoryBean();
  yaml.setResources(new ClassPathResource("application.yml"));

  assertThat(yaml.getObject()).containsEntry("spring.profiles.default", "local");
 }
}
