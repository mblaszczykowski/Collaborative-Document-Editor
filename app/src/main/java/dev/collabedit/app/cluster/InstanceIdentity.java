package dev.collabedit.app.cluster;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InstanceIdentity {
    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return "instance " + id;
    }
}
