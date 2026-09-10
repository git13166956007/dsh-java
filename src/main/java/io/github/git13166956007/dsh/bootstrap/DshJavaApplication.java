package io.github.git13166956007.dsh.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.git13166956007.dsh")
public class DshJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(DshJavaApplication.class, args);
    }

}
