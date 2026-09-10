package io.github.git13166956007.dsh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DshJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(DshJavaApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public DshRuntime dshRuntime() {
        DshRuntime runtime = new DshRuntime();
        runtime.start();
        return runtime;
    }
}
