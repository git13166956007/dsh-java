package io.github.git13166956007.dsh.config;

import io.github.git13166956007.dsh.core.DshRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DshRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    public DshRuntime dshRuntime() {
        DshRuntime runtime = new DshRuntime();
        runtime.start();
        return runtime;
    }
}
