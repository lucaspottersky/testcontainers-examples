package com.github.kaiwinter.testsupport.arquillian;

import com.github.kaiwinter.testsupport.arquillian.WildflyArquillianRemoteConfiguration.ContainerConfiguration;
import com.github.kaiwinter.testsupport.arquillian.WildflyArquillianRemoteConfiguration.ContainerConfiguration.ServletProtocolDefinition;
import org.jboss.arquillian.config.descriptor.api.ContainerDef;
import org.jboss.arquillian.config.descriptor.api.ProtocolDef;
import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.ContainerRegistry;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.LoadableExtension;
import org.jboss.arquillian.core.spi.ServiceLoader;
import org.jboss.as.arquillian.container.Authentication;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.ModelControllerClientConfiguration;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.ext.ScriptUtils;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.jdbc.ContainerLessJdbcDelegate;
import org.testcontainers.shaded.com.google.common.base.Charsets;
import org.testcontainers.shaded.com.google.common.io.Resources;
import org.testcontainers.utility.DockerImageName;
import org.wildfly.plugin.core.Deployment;
import org.wildfly.plugin.core.DeploymentManager;

import javax.script.ScriptException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Starts a docker container and configures Arquillian to use Wildfly in the docker container.
 */
public final class WildflyPostgresDBDockerExtension implements LoadableExtension {

   @Override
   public void register(ExtensionBuilder builder) {
      builder.observer(LoadContainerConfiguration.class);
   }

   /**
    * Helper class to register an Arquillian observer.
    */
   public static final class LoadContainerConfiguration {

      private static final String POSTGRES_DB_NETWORK_HOSTNAME = "postgresdbcontainer";
      private static final String WILDFLY_PWD = "Admin#007";
      private static final String WILDFLY_USER = "admin";
      private static final String POSTGRESDB_DOCKER_IMAGE = "postgres:16-alpine";
      private static final String WILDFLY_DOCKER_IMAGE = "quay.io/wildfly/wildfly:27.0.0.Final-jdk17";

      private static final int WILDFLY_HTTP_PORT = 8080;
      private static final int WILDFLY_MANAGEMENT_PORT = 9990;
      private static final int POSTGRESDB_PORT = 5432;

      private static final String DDL_FILE = "DDL.sql";

      /**
       * Method which observes {@link ContainerRegistry}. Gets called by Arquillian at startup time.
       * 
       * @param registry
       *           contains containers defined in arquillian.xml
       * @param serviceLoader
       */
      public void registerInstance(@Observes ContainerRegistry registry, ServiceLoader serviceLoader) throws IOException {
         Network postgresDBAppserverNetwork = Network.newNetwork();
         DockerImageName postgresDBImageName = DockerImageName.parse(POSTGRESDB_DOCKER_IMAGE);
         @SuppressWarnings("resource")
         PostgreSQLContainer<?> postgresDBContainer = new PostgreSQLContainer<>(postgresDBImageName)
               .withNetwork(postgresDBAppserverNetwork)
               .withNetworkAliases(POSTGRES_DB_NETWORK_HOSTNAME)
               .withExposedPorts(POSTGRESDB_PORT)
               .withDatabaseName("test")
               .withUsername("admin")
               .withPassword("admin");
         postgresDBContainer.start();
         @SuppressWarnings("resource")
         GenericContainer<?> wildflyContainer = new GenericContainer<>(
               new ImageFromDockerfile()
                     .withDockerfileFromBuilder(builder -> builder.from(WILDFLY_DOCKER_IMAGE)
                           .user("jboss")
                           .run("/opt/jboss/wildfly/bin/add-user.sh " + WILDFLY_USER + " " + WILDFLY_PWD + " --silent")
                           .cmd("/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0")
                           .build()))
               .withNetwork(postgresDBAppserverNetwork)
               .withExposedPorts(WILDFLY_MANAGEMENT_PORT, WILDFLY_HTTP_PORT)
               .withStartupTimeout(Duration.ofSeconds(30));
         wildflyContainer.start();
         addDatasourceToWildflyContainer(wildflyContainer);

         configureArquillianForRemoteWildfly(wildflyContainer, registry);

         setupDb(postgresDBContainer);
      }

      private void addDatasourceToWildflyContainer(GenericContainer<?> wildflyContainer) throws IOException {
         Authentication.username = WILDFLY_USER;
         Authentication.password = WILDFLY_PWD;

         ModelControllerClientConfiguration clientConfig = new ModelControllerClientConfiguration.Builder()
               .setHostName(wildflyContainer.getHost())
               .setPort(wildflyContainer.getMappedPort(WILDFLY_MANAGEMENT_PORT))
               .setHandler(Authentication.getCallbackHandler())
               .build();

         File driverFile = Maven.resolver()
               .loadPomFromFile("pom.xml")
               .resolve("org.postgresql:postgresql")
               .withoutTransitivity()
               .asSingleFile();

         ModelControllerClient client = ModelControllerClient.Factory.create(clientConfig);
         DeploymentManager deploymentManager = DeploymentManager.Factory.create(client);
         deploymentManager.forceDeploy(Deployment.of(new FileInputStream(driverFile), "postgresdb.jar"));

         ModelNode request = new ModelNode();
         request.get(ClientConstants.OP).set(ClientConstants.ADD);
         request.get(ClientConstants.OP_ADDR).add("subsystem", "datasources");
         request.get(ClientConstants.OP_ADDR).add("data-source", "java:/MyApplicationDS");
         request.get("jndi-name").set("java:/MyApplicationDS");
         request.get("connection-url").set(
               "jdbc:postgresql://" + POSTGRES_DB_NETWORK_HOSTNAME + "/test?autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8");
         request.get("driver-class").set("org.postgresql.Driver");
         request.get("driver-name").set("postgresdb.jar");
         request.get("user-name").set("admin");
         request.get("password").set("admin");
         request.get("pool-name").set("pool_MyApplicationDS");
         client.execute(new OperationBuilder(request).build());
      }

      private void configureArquillianForRemoteWildfly(GenericContainer<?> paramWildflyContainer,
            ContainerRegistry registry) {
         Integer wildflyHttpPort = paramWildflyContainer.getMappedPort(WILDFLY_HTTP_PORT);
         Integer wildflyManagementPort = paramWildflyContainer.getMappedPort(WILDFLY_MANAGEMENT_PORT);

         String containerIpAddress = paramWildflyContainer.getHost();
         Container arquillianContainer = registry.getContainers().iterator().next();
         ContainerDef containerConfiguration = arquillianContainer.getContainerConfiguration();
         containerConfiguration.property(ContainerConfiguration.MANAGEMENT_ADDRESS_KEY, containerIpAddress);
         containerConfiguration.property(ContainerConfiguration.MANAGEMENT_PORT_KEY,
               String.valueOf(wildflyManagementPort));
         containerConfiguration.property(ContainerConfiguration.USERNAME_KEY, WILDFLY_USER);
         containerConfiguration.property(ContainerConfiguration.PASSWORD_KEY, WILDFLY_PWD);

         ProtocolDef protocolConfiguration = arquillianContainer
               .getProtocolConfiguration(new ProtocolDescription(ServletProtocolDefinition.NAME));
         protocolConfiguration.property(ServletProtocolDefinition.HOST_KEY, containerIpAddress);
         protocolConfiguration.property(ServletProtocolDefinition.PORT_KEY, String.valueOf(wildflyHttpPort));
      }

      private void setupDb(PostgreSQLContainer<?> dockerContainer) {
         String containerIpAddress = dockerContainer.getHost();
         Integer mappedDatabasePort = dockerContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
         String connectionString = "jdbc:postgresql://" + containerIpAddress + ":" + mappedDatabasePort + "/test";

         try (Connection connection = DriverManager.getConnection(connectionString, "admin", "admin");) {
            URL resource = Resources.getResource(DDL_FILE);
            String sql = Resources.toString(resource, Charsets.UTF_8);
            ScriptUtils.executeDatabaseScript(new ContainerLessJdbcDelegate(connection), "", sql);
         } catch (SQLException | ScriptException | IOException e) {
            throw new RuntimeException(e);
         }
      }
   }
}
