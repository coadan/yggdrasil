local json = require("json")
local client = require "panel_client"

local M = {}

function M.load_panel(id)
  return client.fetch(id)
end

local function normalize_id(id)
  return id
end

return M
