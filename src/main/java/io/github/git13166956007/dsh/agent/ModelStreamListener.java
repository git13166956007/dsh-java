package io.github.git13166956007.dsh.agent;

@FunctionalInterface
public interface ModelStreamListener {
    void onText(String delta);
}
