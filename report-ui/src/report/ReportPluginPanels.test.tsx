import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PluginPanel } from "./ReportPluginPanels";

describe("PluginPanel", () => {
  it("does not render implicit core report plugin source pills", () => {
    render(
      <PluginPanel
        panel={{
          id: "core-actions",
          label: "Core Actions",
          slot: "atlas",
          order: 0,
          mdx: "## Core Actions\n\n<ActionList dataKey=\"actions\" />",
          data: {
            actions: [
              {
                kind: "maintenance",
                label: "Process maintenance work queue",
                command: "agraph sync work list --project fixture"
              }
            ]
          },
          plugin: {
            id: "agraph-core-report",
            version: "1",
            authority: "core"
          }
        }}
      />
    );

    expect(screen.getByText("Process maintenance work queue")).toBeInTheDocument();
    expect(screen.queryByText("agraph-core-report")).not.toBeInTheDocument();
    expect(screen.queryByText("agraph-core-report.core-actions")).not.toBeInTheDocument();
  });
});
