package com.example.panels

import groovy.transform.CompileStatic
import static com.example.panels.PanelStatus.READY

@CompileStatic
class PanelService {
  String name = "panels"

  Panel loadPanel(String id) {
    return new Panel(id: id, status: READY)
  }
}

trait Auditable {
  void audit(String id) {}
}

enum PanelStatus {
  READY
}

@interface PanelBinding {}
