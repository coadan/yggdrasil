-module(panel_service).
-include("panel.hrl").
-import(panel_client, [fetch/1]).
-export([load_panel/1]).
-behaviour(gen_server).
-record(panel, {id}).
-callback load_panel(binary()) -> binary().

load_panel(Id) ->
  panel_client:fetch(Id).

normalize_id(Id) ->
  Id.
