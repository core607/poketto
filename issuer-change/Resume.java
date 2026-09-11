import io.github.core607.poketto.PokettoApplication;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import org.springframework.boot.SpringApplication;

class Resume {
    public static void main(String[] args) {
        new SpringApplication(PokettoApplication.class, RemoteRepositoryIntegrationConfiguration.class).run(args);
    }
}
