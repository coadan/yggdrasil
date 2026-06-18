open Core

module type STORE

type panel

val normalize_id : string -> string
val load_panel : string -> panel
