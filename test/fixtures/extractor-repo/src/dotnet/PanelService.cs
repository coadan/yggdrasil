using System;
using Acme.Contracts;

namespace Acme.Panels;

public record Panel(string Id);

public class PanelService
{
    private readonly PanelClient client;

    public PanelService(PanelClient client)
    {
        this.client = client;
    }

    public Panel LoadPanel(string id)
    {
        return client.Fetch(new Uri($"/panels/{id}", UriKind.Relative));
    }

    private string Normalize(string id) => id.Trim();

    public string Name { get; init; } = "panels";
}
