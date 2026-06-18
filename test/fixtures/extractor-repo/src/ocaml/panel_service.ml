open Core
include Panel_sig

module Client = Panel_client
module type STORE = sig
  val load_panel : string -> panel
end

type panel = {
  id : string;
}

exception Panel_not_found of string

class panel_cache = object
end

external hash_panel : string -> int = "hash_panel"

let normalize_id id =
  String.strip id

let rec load_panel id =
  Client.fetch (normalize_id id)
