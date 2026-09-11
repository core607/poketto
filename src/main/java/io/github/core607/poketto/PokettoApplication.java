package io.github.core607.poketto;

import io.github.core607.poketto.auth.AdministratorSetup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PokettoApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("admin")) {
            System.exit(AdministratorSetup.run(args));
            return;
        }
        SpringApplication.run(PokettoApplication.class, args);
    }
}
