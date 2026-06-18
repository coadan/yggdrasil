#include "panel_service.hpp"
#include <vector>

#define PANEL_SERVICE_VERSION 1

namespace panels {
using PanelId = std::string;

std::string PanelService::load_panel(const std::string& id) {
  return id;
}

int build_panel() {
  return 1;
}
}
