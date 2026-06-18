module PanelService

using DataFrames
import JSON3

struct Panel
    id::String
end

function load_panel(id)
    Panel(id)
end

normalize_id(id) = id

end
