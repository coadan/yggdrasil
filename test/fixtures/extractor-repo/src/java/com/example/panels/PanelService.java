package com.example.panels;

import java.net.URI;
import static java.util.Objects.requireNonNull;

public class PanelService {
    private final PanelClient client;

    public PanelService(PanelClient client) {
        this.client = requireNonNull(client);
    }

    public Panel loadPanel(String id) {
        audit(id);
        return client.fetch(URI.create("/panels/" + id));
    }

    private void audit(String id) {
    }

    public record Panel(String id) {
    }

    interface PanelClient {
        Panel fetch(URI uri);
    }

    enum PanelStatus {
        ACTIVE
    }

    public @interface PanelBinding {
    }
}
