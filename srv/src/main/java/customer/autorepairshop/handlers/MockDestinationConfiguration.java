package customer.autorepairshop.handlers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;

@Component
@Profile("mocked")
public class MockDestinationConfiguration {

    private final Environment environment;

    @Value("${server.port:8080}")
    private String configuredPort;

    public MockDestinationConfiguration(Environment environment) {
        this.environment = environment;
    }

    @EventListener
    void applicationReady(ApplicationReadyEvent event) {
        String port = environment.getProperty("local.server.port", configuredPort);

        DefaultHttpDestination mockDestination = DefaultHttpDestination
                .builder("http://localhost:" + port + "/odata/v4")
                .name("vehicle-specs-destination-mocked")
                .basicCredentials("alice", "alice")
                .build();

        DefaultDestinationLoader loader = new DefaultDestinationLoader();
        loader.registerDestination(mockDestination);
        DestinationAccessor.prependDestinationLoader(loader);
    }
}
