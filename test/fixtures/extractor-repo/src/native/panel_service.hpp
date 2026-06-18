#pragma once

#include <string>

typedef const char* PanelName;

namespace panels {
class PanelService {
public:
  std::string load_panel(const std::string& id);
};
}
