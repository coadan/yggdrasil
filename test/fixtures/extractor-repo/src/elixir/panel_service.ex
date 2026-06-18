defmodule Acme.Panels.PanelService do
  alias Acme.Panels.PanelClient
  require Logger

  @behaviour Acme.Panels.Loader
  @callback load_panel(String.t()) :: String.t()
  Record.defrecord(:panel, id: nil)

  def load_panel(id) do
    PanelClient.fetch(id)
  end

  defp normalize_id(id) do
    id
  end
end
