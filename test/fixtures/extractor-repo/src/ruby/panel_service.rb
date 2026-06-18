require "json"
require_relative "panel_client"

module Panels
  DEFAULT_PANEL = "panel"

  class PanelService
    def load_panel(id)
      PanelClient.new.fetch(id)
    end

    def self.audit(event)
      JSON.generate(event)
    end
  end
end
