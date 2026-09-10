package io.github.git13166956007.dsh;

import java.util.List;
import java.util.Map;

public interface ToolProvider {
    List<String> tools();

    String call(String name, Map<String, String> arguments) throws Exception;
}
