defmodule Panels.MixProject do
  use Mix.Project

  def project do
    [
      app: :panels,
      deps: deps()
    ]
  end

  defp deps do
    [
      {:plug, "~> 1.14"},
      {:jason, "~> 1.4"}
    ]
  end
end
