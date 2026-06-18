library panels.store;

import 'package:flutter/widgets.dart';
import 'package:panels/client.dart';

class PanelStore {
  PanelStore(this.client);

  final PanelClient client;
  final String cacheName = 'panels';

  Future<void> loadPanel(String id) async {
    await client.fetch(id);
  }

  String get selected => cacheName;

  set selected(String value) {
  }
}
