package com.example.panels

import cats.effect.IO
import com.example.panels.client.PanelClient

trait PanelRepository {
  def findPanel(id: String): IO[String]
}

final class PanelService(client: PanelClient) {
  def loadPanel(id: String): IO[String] = {
    client.fetch(id)
  }

  private val cacheName = "panels"
}

object PanelRoutes {
  def route(service: PanelService): String = "panel"
}
