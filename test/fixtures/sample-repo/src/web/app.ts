import type { PanelConfig } from "./types";
import React from "react";
import "./theme.scss";

const helper = () => "ready";

export class Panel {
  render() {
    return helper();
  }
}

export async function loadPanel() {
  return import("./loader");
}

export const route = "/panels";

const data = require("./data.json");
